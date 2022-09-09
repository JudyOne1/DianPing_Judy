package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT ;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckillLUA.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //spring提供的注解，当前类初始化完毕后执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHander());
    }
    //线程任务
    private class VoucherOrderHander implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    //获取队列中信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);
                }finally {

                }
            }
        }
    }

    /**
     * 订单创建
     *
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();//默认，失败不等待，30s超时
        if (!isLock){
            //失败
            log.error("一人一单");
            return;
        }
        try {
            proxy.creatVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }
    private IVoucherOrderService proxy;

    /**
     * 基于redis实现秒杀资格判断
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));
        // 判断结果
        int r = result.intValue();
        if (r != 0){
            return Result.fail(r == 1 ? "库存不足":"不能重复下单");
        }
        //0 有资格
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);
        //阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象  => 成员变量
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //未开始
//            return Result.fail("未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //结束
//            return Result.fail("结束");
//        }
//        if (voucher.getStock() < 1) {
//            Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//
////        synchronized (userId.toString().intern()){
////            //获取事务代理对象，确保事务生效  是this.的方式调用的，事务想要生效，还得利用代理来生效，所以这个地方，我们需要获得原始的事务对象， 来操作事务
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.creatVoucherOrder(voucherId);
////        }
//        //-----------------------------------自定义分布式锁--------------------------------------------
//        //分布式锁
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
////        boolean isLock = lock.tryLock(1200);
////        if (!isLock){
////            return Result.fail("一人一单");
////        }
////        try {
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.creatVoucherOrder(voucherId);
////        } catch (IllegalStateException e) {
////            e.printStackTrace();
////        } finally {
////            lock.unlock();
////        }
////        return null;
//        //---------------------------------使用redisson分布式锁----------------------------------------
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();//默认，失败不等待，30s超时
//        if (!isLock){
//            return Result.fail("一人一单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.creatVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            e.printStackTrace();
//        } finally {
//            lock.unlock();
//        }
//        return null;
//    }

    @Transactional
    public void creatVoucherOrder(VoucherOrder voucherOrder){
        //一人一单
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买过一次！");
            return;
        }
        //扣减
        boolean succes = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0).update();

        if (!succes) {
            log.error("库存不足！");
            return;
        }
        //创建订单
        this.save(voucherOrder);

    }
}
