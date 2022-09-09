package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import javax.annotation.Resource;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;


/**
 * @author:Judy
 * @date:2022/9/7 19:19
 * ClassName:SimpleRedisLock
 * package:com.hmdp.utils
 */
@Slf4j
public class SimpleRedisLock implements ILock{

    private String name;
    public static final String KEY_PREFIX = "lock:";

    public static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";

    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT ;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

//    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }


    /**
     * 获取锁
     * @param timeoutSec
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        //线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean ifAbsent = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name , threadId, timeoutSec, TimeUnit.SECONDS);
        //直接return success会自动拆箱，自动拆箱有可能是null
        System.out.println(ifAbsent);
        log.info("分布式锁...");
        return Boolean.TRUE.equals(ifAbsent);
    }
    /**
     * 释放锁 2.0
     * 改进=>lua脚本:确保原子性（获取锁和释放锁一起执行
     *
     */
    @Override
    public void unlock() {
        //调用lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

//    /**
//     * 释放锁  1.0
//     */
//    @Override
//    public void unlock() {
//        //获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        String realThreadId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (threadId.equals(realThreadId))
//        stringRedisTemplate.delete(KEY_PREFIX + name);
//    }

}
