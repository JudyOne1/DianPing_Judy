package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author:Judy
 * @date:2022/9/8 10:06
 * ClassName:RedissonConfig
 * package:com.hmdp.config
 */
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.152.130:6379")
                .setPassword("123123");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
