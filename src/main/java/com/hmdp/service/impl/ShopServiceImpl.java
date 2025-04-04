package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
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
     * 添加商户缓存
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        log.debug("查询商户缓存:{}",key);
//        1.从redis查商户缓存
        String json = str.opsForValue().get(key);
//        2.如果存在直接返回json
        if (StrUtil.isNotEmpty(json)) {
            Shop shop = JSONUtil.toBean(json, Shop.class);
            return Result.ok(shop);
        }
//        3.不存在，根据id从数据库查，
        Shop shop = getById(id);

//        3.2.如果数据库查不到，返回错误
        if (shop == null) {
            return Result.fail("商户不存在");
        }
//        3.1转入json存入
        String jsonStr = JSONUtil.toJsonStr(shop);
//        4.再写入到redis
//        TODO 增加过期时间
        str.opsForValue().set(key,jsonStr,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }
}
