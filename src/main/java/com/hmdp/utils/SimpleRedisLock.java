package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.apache.ibatis.javassist.Loader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock{
    @Resource
    private StringRedisTemplate stringRedisTemplate;    
    private final String name;
    private static final String KEY_PREFIX = "lock:";
    // 通过UUID生成随机字符串并拼接线程id生成线程标识，因为存在多个jvm，防止执行jvm的线程标识重复
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    // 创建这个类对象的构造方法
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    // 定义lua执行的脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    // 执行脚本的静态代码块
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    // 尝试获取锁
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识l
        String threadId =ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);// 第二个参数value是线程名
        // 由于success是包装类，需要拆箱，为保证安全性，手动拆箱
        return Boolean.TRUE.equals(success);
    }
    // 调用lua脚本释放锁
    @Override
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                        Collections.singletonList(KEY_PREFIX + name),// 需要将字符串转为集合
                        ID_PREFIX + Thread.currentThread().getId());
    }
/*    // 释放锁
    @Override
    public void unlock() {
        // 1.获取线程标识
        String threadId =ID_PREFIX + Thread.currentThread().getId();
        // 2.获取redis锁中的线程标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 3.如果线程标识与redis存的对象一致，释放,不一致的不用管
        if (threadId.equals(id)) stringRedisTemplate.delete(KEY_PREFIX + name);
    }*/
}
