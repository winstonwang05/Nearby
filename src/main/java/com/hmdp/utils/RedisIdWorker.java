package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

// 全局唯一ID生成器
@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 开始时间戳，这里是2025.1.1
    private static final long BEGIN_TIMESTAMP = 1735689600L;
    private static final int COUNT_BITS = 32;

    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond -  BEGIN_TIMESTAMP;
        // 2.生成序列号
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String format = now.format(formatter);
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + format);
        // 3.拼接时间戳和序列号
        return timeStamp << COUNT_BITS | count;

    }
}
