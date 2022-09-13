package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    private IVoucherOrderService proxy;

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
//    //线程任务 => orderTasks阻塞队列
//    private class VoucherOrderHander implements Runnable{
//        @Override
//        public void run() {
//            while (true){
//                try {
//                    //2）. 获取队列中信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    log.error("处理订单异常",e);
//                }
//            }
//        }
//    }

//线程任务 => stream消息队列
private class VoucherOrderHander implements Runnable {
    String queueName = "stream.orders";

    @Override
    public void run() {
        while (true) {
            try {
                //2）. 获取消息队列中信息  XREADGROUP GROUP
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(queueName, ReadOffset.lastConsumed())//<
                );
                //判断成功与否
                if (list == null || list.isEmpty()) {
                    continue;
                }
                //解析消息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                //创建订单
                handleVoucherOrder(voucherOrder);
                //ack确认
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
            } catch (Exception e) {
                //ack未确认
                log.error("处理订单异常", e);
                handlePendingList();
            }
        }
    }


    private void handlePendingList() {
        while (true) {
            try {
                //2）. 获取pendingList中信息  XREADGROUP GROUP
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))//0
                );
                //判断成功与否
                if (list == null || list.isEmpty()) {
                    //pendingList中没有异常消息 => 结束循环
                    break;
                }
                //解析消息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder);
                //ack确认
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
            } catch (Exception e) {
                log.error("处理pendinglist异常", e);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }
}

    /**
     * 1）. 网页入口 => 先执行此方法
     * 基于redis实现秒杀资格判断
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //---------------redis = stream消息队列----------------
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //1. lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));
        // 2. 判断结果
        int r = result.intValue();
        if (r != 0){
            return Result.fail(r == 1 ? "库存不足":"不能重复下单");
        }
        //在脚本完成
        //获取代理对象  => 成员变量
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
        //-------------------原始阻塞队列----------------------
//        Long userId = UserHolder.getUser().getId();
//        //lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString());
//        // 判断结果
//        int r = result.intValue();
//        if (r != 0){
//            return Result.fail(r == 1 ? "库存不足":"不能重复下单");
//        }
//        //0 有资格
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setId(orderId);
//        //阻塞队列
//        orderTasks.add(voucherOrder);
//        //获取代理对象  => 成员变量
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        return Result.ok(orderId);
    }

    /**
     * 3）. 订单处理
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();//redission ： 默认，失败不等待，30s超时
        if (!isLock){
            //失败
            log.error("一人一单");
            return;
        }
        try {
            //成功 => 订单创建
            proxy.creatVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 4）. 订单创建
     * @param voucherOrder
     */
    @Transactional
    public void creatVoucherOrder(VoucherOrder voucherOrder){
        //一人一单 再判断
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
        //创建订单 => database
        this.save(voucherOrder);
    }
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
//        synchronized (userId.toString().intern()){
//            //获取事务代理对象，确保事务生效  是this.的方式调用的，事务想要生效，还得利用代理来生效，所以这个地方，我们需要获得原始的事务对象， 来操作事务
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.creatVoucherOrder(voucherId);
//        }
////        -----------------------------------自定义分布式锁--------------------------------------------
////        分布式锁
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        boolean isLock = lock.tryLock(1200);
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
////        ---------------------------------使用redisson分布式锁----------------------------------------
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


