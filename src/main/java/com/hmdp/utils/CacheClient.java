package com.hmdp.utils;

import ch.qos.logback.classic.pattern.Util;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

// 定义一个工具类，由spring容器管理，用来解决缓存问题（穿透和击穿）
@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 方法一：将Java对象序列化为json字符串并存储在String类型的key中，设置过期时间TTL（该方法为后面方法三解决缓存穿透）
    // value就是需要进行缓存的Java对象，并设置TTL,timeUnit是用来设置time单位
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        // 写入redis缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }
    // 方法二：将Java对象序列化为json字符串存储在以String类型的key中，设置逻辑过期时间,为后面方法四解决缓存击穿
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        // 获取逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    // 方法三：根据key去缓存查询数据，没查询到，通过数据库写入空值到缓存完成缓存穿透,也就是缓存击穿查询
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> resultPojo,
                                         Function<ID, R> dbFallback,Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        // 1.从redis缓存查询店铺
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.如果查询到，说明命中直接反序列化返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, resultPojo);
        }
        // 判断是否为空
        if (json != null) {
            return null;
        }
        // 3.没查询到，去数据库根据id查询
        R r = dbFallback.apply(id);
        // 4.数据库没查询到，写入空值给redis缓存
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5.数据库查询得到，写入缓存
        this.set(key, r, time, timeUnit);
        // 6.返回
        return r;
    }

    // 定义一个线程池，为后面获取互斥锁，通过异步线程重新查询新数据并写入缓存redis中
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    // 获取互斥锁
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 需要自动拆箱，因此我们直接用工具效率高
        return BooleanUtil.isTrue(flag);
    }
    // 释放互斥锁
    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
    // 方法四：根据key去缓存查询数据，通过逻辑过期解决缓存击穿问题
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> typePojo
            , Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
         /*1.查询缓存，如果未命中，得去数据库查询，然后写入缓存，不要返回null，不然redis缓存里面没有的店铺缓存就不会被查询到（店铺不存在！）
                 if (shop == null) {
            return Result.fail("店铺不存在！");
        }
         比如我在redis缓存店铺1和2，但是当我查询其他店铺除去1和2，就不会查询到，因为下面判断
             if (StrUtil.isBlank(json)) {
            return null;
        } 未命中直接返回null,然后在ShopServiceImpl中判断如果为null,就返回店铺不存在。除非在redis缓存所有店铺缓存就不管，因为永远都会命中缓存
        ，不可能不会命中，然后就会看到逻辑过期，因为下面的语句就是命中后的逻辑过期判断，可以查看redisData。
        所以可以修改成下面的代码：
                if (StrUtil.isBlank(json)) {
            // 缓存未命中，查询数据库
            R r = dbFallback.apply(id);
            if (r == null) {
                return null; // 数据库也不存在
            }
            // 将数据库查询结果写入缓存
            this.setWithLogicalExpire(key, r, time, timeUnit);
            return r;
        }

        */
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 2，命中，将json反序列化获取RedisDate（缓存击穿本质就是命中得到数据值和TTL，然后对TTL进行逻辑过期）
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
       /*  2.1 获取旧数据(但是返回的是Object类，需要将其强转为JsonObject，
            不能直接强转为shop，因为所得到的Object可以包含String类型等等,再将JsonObject反序列化为shop类)*/
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), typePojo);
        // 2.2 获取TTL
        LocalDateTime expireTime = redisData.getExpireTime();
        // 3.查看expire是否到期
        // 4.未到期，直接从缓存拿取旧数据（expireTime在当前时间后面，说明没有过期）
        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // 5，到期，尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.如果获取到锁，异步线程，从线程池拿，重新去数据库查询新数据，并写入缓存中
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R apply = dbFallback.apply(id);
                    //查询得到新数据并写入缓存redis
                    this.setWithLogicalExpire(key, apply, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 7.释放锁
                    unLock(lockKey);
                }
            });
        }

        // 8.返回旧数据值(获取锁的和未获取锁的都是返回旧数据值)
        return r;
    }

}
