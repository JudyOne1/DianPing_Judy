package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author:Judy
 * @date:2022/9/6 16:12
 * ClassName:RedisIdWorker
 * package:com.hmdp.utils
 */
@Component
public class RedisIdWorker {

    public static final long BEGIN_TIMESTAMP = 1640995200L;
    public static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 全局唯一id生成器
     * @param keyPrefix
     * @return
     */
    public Long nextId(String keyPrefix){
        //时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond-BEGIN_TIMESTAMP;
        //序列号
        //获取当前日期
        String yyyyMMdd = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + nowSecond);
        //拼接返回
        return timeStamp << COUNT_BITS  | count;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0,0 );
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }
}
