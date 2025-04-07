package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static java.lang.Thread.sleep;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author cuiyq
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate str;

    /**
     * 添加商户缓存 互斥锁解决缓存击穿问题
     *
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
//      redis写入空值解决缓存击穿
//        Shop shop = queryWithPassThrow(id);

//      互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

//       逻辑过期解决缓存击穿问题
        Shop shop = queryWithLogicalExpire(id);

        if (shop == null) {
           return Result.fail("商户不存在");
        }

//       返回
        return Result.ok(shop);
    }

    //开启线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿问题
     *
     * @param id
     * @return
     */
    private Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        log.debug("查询商户缓存:{}", key);
//        1.从redis查商户缓存
        String json = str.opsForValue().get(key);
//        2.如果不存在直接返回json
        if (StrUtil.isBlank(json)) {
            return null;
        }
//        命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Object data = redisData.getData();
        Shop shop = JSONUtil.toBean((JSONObject) data, Shop.class);

//        3.判断缓存是否过期
        boolean after = redisData.getExpireTime().isAfter(LocalDateTime.now());
        if (after) {
//        3.2 没过期返回信息
            return shop;
        }

//        3.3 过期尝试获取互斥锁
        String lock = LOCK_SHOP_KEY + id;
        boolean b = tryLock(lock);
//       4. 判断是否获取锁

        if (b) {
            //        4.2 获取成功开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
//            重建缓存
                try {
                    saveShop2Redis(id, 20L); //30s
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lock);//释放锁
                }
            });
        }
        return shop;
    }

    /**
     * 逻辑过期解决缓存击穿，缓存预热，把数据保存到redis中
     *
     * @param id。商户id
     * @param expireSeconds 过期时间 秒
     */
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        RedisData redisData = new RedisData();
//        1.查询数据库
        Shop shop = getById(id);
        redisData.setData(shop);
        sleep(200);

//        2.设置逻辑过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        3.转成String json写入到redis
        String jsonStr = JSONUtil.toJsonStr(redisData);
//        redis中是相当于永久的
        str.opsForValue().set(CACHE_SHOP_KEY + id, jsonStr);
    }

    /**
     * 释放锁
     *
     * @param key
     */

    private void unlock(String key) {
        str.delete(key);
    }

    /**
     * 加锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = str.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 封装缓存击穿方法，互斥锁解决方法
     *
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        log.debug("查询商户缓存:{}", key);
//        1.从redis查商户缓存
        String json = str.opsForValue().get(key);
//        2.如果存在直接返回json
        if (StrUtil.isNotBlank(json)) {
            Shop shop = JSONUtil.toBean(json, Shop.class);
            return null;
        }

//        判断命中是否是空值
//        3.如果是空字符串返回redis
        if (json != null) {
            return null;
        }

//        4.缓存没命中获取互斥锁
        String lock = LOCK_SHOP_KEY + id;
        Shop shop = null;

        try {
//        4.1。判断是否获取锁成功 否休眠一段时间，重新查询缓存
            if (!tryLock(lock)) {
                sleep(50);
                return queryWithMutex(id);
            }
//        4.2.如果获取成功，查询数据库
//        5.不存在，根据id从数据库查，
            shop = getById(id);
//        5.1.如果数据库查不到，返回错误
            if (shop == null) {
                //            redis写入空字符串
                str.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
//        5.2转入json存入
            String jsonStr = JSONUtil.toJsonStr(shop);
//        6.再写入到redis
            str.opsForValue().set(key, jsonStr, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //        7.释放互斥锁
            unlock(lock);
        }
        return shop;
    }


    /**
     * 封装缓存穿透方法，缓存空值解决方法
     *
     * @param id
     * @return
     */
    public Shop queryWithPassThrow(Long id) {
        String key = CACHE_SHOP_KEY + id;
        log.debug("查询商户缓存:{}", key);
//        1.从redis查商户缓存
        String json = str.opsForValue().get(key);
//        2.如果存在直接返回json
        if (StrUtil.isNotBlank(json)) {
            Shop shop = JSONUtil.toBean(json, Shop.class);
            return null;
        }

//        判断命中是否是空值
//        3.1 如果是空字符串返回redis
        if (json != null) {
            return null;
        }
//        3.不存在，根据id从数据库查，
        Shop shop = getById(id);
//        3.2.如果数据库查不到，返回错误
        if (shop == null) {
//            redis写入空字符串
            str.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
//        3.1转入json存入
        String jsonStr = JSONUtil.toJsonStr(shop);
//        4.再写入到redis

        str.opsForValue().set(key, jsonStr, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 修改商户信息
     *
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商户id为空");
        }
//        写入数据库
        updateById(shop);
//        2.删除缓存
        str.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
