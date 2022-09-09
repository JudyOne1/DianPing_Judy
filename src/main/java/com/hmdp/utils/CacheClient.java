package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author:Judy
 * @date:2022/9/6 15:09
 * ClassName:CacheClient
 * package:com.hmdp.utils
 */

/**
 * 封装redis工具类
 * @author Judy
 */
@Component
public class CacheClient {
//    @Resource
    private final StringRedisTemplate stringRedisTemplate;

    //也可以通过注解@Resource注入，这里通过构造函数进行注入
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData),time,unit);
    }
    //缓存穿透
    public <R,ID> R  queryWithPassThrough(String keyPrefix , ID id, Class<R> type , Long time , TimeUnit unit, Function<ID,R> dbFallback){
        String key = keyPrefix + id;
        //redis
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {//存在  反序列化
            return JSONUtil.toBean(json, type);
        }
        // 是否为空  缓存null值  防止缓存穿透  !=null => 空值
        if (json != null) {
            return null;
        }
        //database
        R r = dbFallback.apply(id);
        if (r==null) {
            //redis + 存空值null
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //redis
        this.set(key,r,time,unit);
        return r;
    }


    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //缓存击穿
    public <R,ID> R queryWithLogicalExpire(String keyPrefix , ID id, Class<R> type , Long time , TimeUnit unit, Function<ID,R> dbFallback){
        String key = keyPrefix + id;
        //redis
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            //不存在
            return null;
        }
        //命中，json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期
            return r;
        }
        //已过期 => 缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        //获取互斥锁
        boolean isLock = tryLock(lockKey);
        //成功and返回过期  +  开启独立线程
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存 1.查database  2.存入redis
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);

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
        return r;
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
