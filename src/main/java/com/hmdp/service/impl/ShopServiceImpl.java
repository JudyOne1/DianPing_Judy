package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        //------------------------
        //缓存穿透:
//        Shop shop = queryWithPassThrough(id);

        //工具类cacheClient
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, CACHE_SHOP_TTL, TimeUnit.MINUTES, this::getById);
        //------------------------
        //互斥锁解决缓存击穿问题:
//        Shop shop = queryWithMutex(id);
//        if (shop == null){
//            return Result.fail("无此店铺!!!");
//        }
//        return Result.ok(shop);
        //-------------------------
        //工具类cacheClient
        //逻辑过期解决缓存击穿问题
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, CACHE_SHOP_TTL, TimeUnit.MINUTES, this::getById);
        if (shop == null){
            return Result.fail("无此店铺!!!");
        }
        return Result.ok(shop);
    }


    /**
     * 封装逻辑过期方法
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //redis
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            //不存在
            return null;
        }
        //命中，json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期
            return shop;
        }
        //已过期 => 缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        //获取互斥锁
        boolean isLock = tryLock(lockKey);
        //成功and返回过期  +  开启独立线程
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(key);
                }
            });
        }
        //成功 => lock = 0 ; doubleCheck  这里略......
        //失败and返回过期
        return shop;
    }

    /**
     * 封装互斥锁的方法
     * @param id
     * @return shop
     */
    public Shop queryWithMutex(Long id){
        //redis
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);

        }
        // 是否为空  缓存null值  防止缓存穿透
        if (shopJson != null) {
            return null;
        }
        //未命中  实现缓存重建
        //互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                //获取锁失败
                Thread.sleep(50);
                //重试
                return queryWithMutex(id);
            }
            //成功 => lock = 0 ; doubleCheck
            boolean lock = tryLock(lockKey);
            if (lock){
                //检查后结果=获取锁失败
                Thread.sleep(50);
                //重试
                return queryWithMutex(id);
            }
            shop = this.getById(id);
            Thread.sleep(200);
            if (shop==null) {
                //redis + null
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unlock(lockKey);
        }
        return shop;
    }

    /**
     * 封装缓存穿透方法
     */
    public Shop queryWithPassThrough(Long id){
        //redis
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return Result.ok(shop);
            return shop;
        }
        // 是否为空  缓存null值  防止缓存穿透
        if (shopJson != null) {
//            return Result.fail("店铺信息不存在");
            return null;
        }
        //database
        Shop shop = getById(id);
        if (shop==null) {
            //redis + null
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return Result.fail("店铺不存在");
            return null;
        }
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return Result.ok(shop);
        return shop;
    }


    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //查询
        Shop shop = getById(id);
        Thread.sleep(200);//模拟
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        //update database
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id有误");
        }
        //delete redis
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
