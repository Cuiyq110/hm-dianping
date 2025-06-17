package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.CacheClient1;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import jodd.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
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

    @Resource
    private CacheClient1 cacheClient1;


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
//        Shop shop = queryWithLogicalExpire(id);


        //      redis写入空值解决缓存穿透
        Shop shop = cacheClient1.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, 30L, TimeUnit.MINUTES);

//         // 互斥锁解决缓存击穿
//        cacheClient1.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, 30L, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
//         Shop shop = cacheClient1.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);
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

    /**
     * 根据商户查询附近坐标功能
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否根据坐标查询
        if (x == null || y == null) {

            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        //2.计算分页参数
        long from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        long end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //3.查询数据根据距离排序，分页，结果：shopId、distance
        String key = KEY_PRE_FIX + SHOP_GEO_KEY + typeId;
            //GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
        GeoResults<RedisGeoCommands.GeoLocation<String>> search = str.opsForGeo().search(key,
                GeoReference.fromCoordinate(x,y),
                new Distance(5000), RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (search == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = search.getContent();
            //3.1保存为shopId，和距离数组
        List<String> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());

        if (list.size() <= from) {
        //没有下一页了结束
            return Result.ok(Collections.emptyList());
        }
        //4.解析id
        list.stream().skip(from).forEach(result ->
        {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(shopIdStr);
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //5.根据id查询店铺
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
            //5.1店铺跟距离结合起来
        for (Shop shop : shops) {
            Double distance = distanceMap.get(shop.getId().toString()).getValue();
            shop.setDistance(distance);
        }
        //6.返回
        return Result.ok(shops);
    }
}
