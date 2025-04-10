package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.IVoucherOrderMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author cuiyq
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private IVoucherOrderService voucherOrderService;


    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 保存秒杀库存到Redis中
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
    }


    /**
     * 实现优惠券秒杀下单功能
     *
     * @param voucherId
     * @return
     */
    @Transactional
    @Override
    public Result seckillVoucher(Long voucherId) {

//        1.根据id查询秒杀数据库
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("秒杀商品不存在");
        }

//        2.判断秒杀是否开始
//        2.1没有开始，直接返回异常
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
//        2.2 已经结束，直接返回异常
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
//        3.判断库存是否充足
        if (voucher.getStock() < 1) {
            //3.1 如果库存不足，直接返回异常
            return Result.fail("库存不足");
        }
        // 5.一人一单逻辑
        // 5.1.用户id
        Long userId = UserHolder.getUser().getId();
        int count = voucherOrderService.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }

//        4.减少库存
        boolean sucess = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .gt("stock", 0)
                .eq("voucher_id", voucherId).update();

        if (!sucess) {
            return Result.fail("库存不足");
        }
//        5.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
//        优惠券id
        voucherOrder.setVoucherId(voucherId);
//         用户id
        Long id = UserHolder.getUser().getId();
        voucherOrder.setUserId(id);
//       订单id 全局唯一id
        long orderId= redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        boolean save = voucherOrderService.save(voucherOrder);
        if (!save) {
            return Result.fail("下单失败");
        }
//        6.返回订单id
        return Result.ok(orderId);
    }
}
