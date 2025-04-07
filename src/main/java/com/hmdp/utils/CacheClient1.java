package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.events.Event;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static java.lang.Thread.sleep;

/**
 * @version V1.0
 * @Title:
 * @Description: 基于StringRedisTemplate封装一个缓存工具类，满足下列需求：
 * <p>
 * * 方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
 * * 方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓
 * <p>
 * 存击穿问题
 * <p>
 * * 方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
 * * 方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
 * @Copyright 2024 Cuiyq
 * @author: Cuiyq
 * @date: 2025/4/7 15:23
 */
@Slf4j
@Component
public class CacheClient1 {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient1(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //开启线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public  <R,ID> R queryWithLogicalExpire(
            String keyPrefix,ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit
    ) {
        String key = keyPrefix + id;
//        1.从redis查商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
//        2.如果不存在直接返回json
        if (StrUtil.isBlank(json)) {
            // 3.存在，直接返回
            return null;
        }
        log.debug("查询商户缓存:{}", key);
//        命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

//        3.判断缓存是否过期
        boolean after = expireTime.isAfter(LocalDateTime.now());
        if (after) {
//        3.2 没过期返回信息 直接返回店铺信息
            return r;
        }

//        3.3 过期尝试获取互斥锁
        String lock = "LOCK_KEY" + id;
        boolean isLock = tryLock(lock);
//       4. 判断是否获取锁

        if (isLock) {
            //        4.2 获取成功开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //           4.3 重建缓存
//                    4.3.1.新建数据库
                    R newR = dbFallback.apply(id);
//                    4.3.2 重建缓存
                    setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lock);//释放锁
                }
            });
        }
        return r;
    }


    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        // 设置逻辑过期
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
//            写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //    根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        log.debug("查询商户缓存:{}", key);
        //        1.从redis查商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //        2.如果存在直接返回json
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
    //         如果不存在
    //        判断命中是否是空值
    //        3.1 如果是空字符串返回redis
        if (json != null) {
    //              返回一个错误信息
            return null;
        }
    //        3.不存在，根据id从数据库查，
        R r = dbFallback.apply(id);
    //        3.2.如果数据库查不到，返回错误
        if (r == null) {
    //            redis写入空字符串
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
    //        3.1转入json存入
        String jsonStr = JSONUtil.toJsonStr(r);
    //        4.再写入到redis

        stringRedisTemplate.opsForValue().set(key, jsonStr, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return r;
    }

    /**
     * 释放锁
     *
     * @param key
     */

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 加锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }


    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用互斥锁解决缓存击穿问题
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
//        1.从redis查商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
//        2.如果存在直接返回json
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);

        }
        log.debug("查询商户缓存:{}", key);
//        判断命中是否是空值
//        3.如果是空字符串返回redis
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

//        4.缓存没命中获取互斥锁
        String lock = "lock:shop:" + id;
        R r = null;

        try {
//        4.1。判断是否获取锁成功 否休眠一段时间，重新查询缓存
            if (!tryLock(lock)) {
                sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
//        4.2.如果获取成功，查询数据库
//        5.不存在，根据id从数据库查，
            r = dbFallback.apply(id);
//        5.1.如果数据库查不到，返回错误
            if (r == null) {
                //            redis写入空字符串
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
//        5.2转入json存入
            String jsonStr = JSONUtil.toJsonStr(r);
//        6.再写入到redis
            this.set(key, jsonStr, time, unit);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //        7.释放互斥锁
            unlock(lock);
        }
        return r;
    }

}
