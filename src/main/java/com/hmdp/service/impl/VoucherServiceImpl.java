package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

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
    private IVoucherOrderServiceImpl voucherOrderService;
    @Resource
    private RedissonClient redissonClient;


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
        stringRedisTemplate.opsForValue().set(RedisConstants.SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
    }

/*    //    开启阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);*/


    //获取代理对象(事务)
    private VoucherServiceImpl proxy;

    // 消息队列的名字“orders”
     private String queueName = "stream.orders";

    //    开启线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //    在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(
                new VoucherOrderHandler()
        );
    }

    // 用于线程池处理的任务
// 当初始化完毕后，就会去从对列中去拿信息
    public class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
//                    2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        //如果为空，说明队列中没有信息，直接返回
                        continue;
                    }
//                    3.解析数据
                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), false);
//                    4.创建订单
                    handleVoucherOrder(voucherOrder);
//                    5.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", entries.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
//                    处理异常消息
                    handlePendingList();
                }
            }
        }

    }

    /**
     * 从pending-list中取出信息，重新读取这些未处理的消息，并确保它们能够被正确处理。
     */
    private void handlePendingList() {
        while (true) {
            try {
                // 1.获取pending-list中的订单信息。名字"stream.orders"    XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty(),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
//                    2.判断订单信息是否为空
                if (list == null || list.isEmpty()) {
                    //如果为空，说明队列中没有信息，直接返回
                    break;
                }
//                    3.解析数据
                MapRecord<String, Object, Object> entries = list.get(0);
                Map<Object, Object> value = entries.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), false);
//                    4.创建订单
                handleVoucherOrder(voucherOrder);
                //5.确认消息 XACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", entries.getId());
            } catch (Exception e) {
                log.error("处理pendding订单异常", e);
                try {
                    Thread.sleep(20);
                } catch (Exception ex) {
                    e.printStackTrace();
                }
            }
        }
    }

/*    // 用于线程池处理的任务
// 当初始化完毕后，就会去从对列中去拿信息
    public class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    //1.从队列中获取订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    /**
     * 处理订单 ,创建订单
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
//        ID 从voucherOrder 中取出来
        Long userId = voucherOrder.getUserId();
//        4.使用简易自定义分布式锁
        //4.1创建锁对象(新增代码)
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //4.2获取锁对象
        boolean b = lock.tryLock();
//      4.3如果获取不到证明已经购买了
        if (!b) {
            log.error("不能重复下单");
            return;
        }

        try {
//            proxy.createVoucherOrder(voucherOrder);
            createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
//            释放锁
            lock.unlock();
        }


    }


    //    调用脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua")); //指定脚本位置名字
        SECKILL_SCRIPT.setResultType(Long.class);
    }



    /**
     * 实现优惠券秒杀下单功能
     * 使用redis消息队列
     * @param voucherId
     * @return
     */

    @Override
    public Result seckillVoucher(Long voucherId) {
        //        获取用户
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");


//        1.执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString()
        );
        int r = result.intValue();
//        2.判断结果是否为0
        if (r != 0) {
            //        2.1如果不是0，返回对应信息
            return r == 1 ? Result.fail("库存不足") : Result.fail("不能重复下单");
        }

/*        //获取代理对象(事务)
        proxy = (VoucherServiceImpl) AopContext.currentProxy();*/
//        2.3 返回订单id
        return Result.ok(orderId);

    }


/*    *//**
     * 实现优惠券秒杀下单功能
     * 使用阻塞队列
     * @param voucherId
     * @return
     *//*

    @Override
    public Result seckillVoucher(Long voucherId) {
        //        获取用户
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
//        1.执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString()
        );
        int r = result.intValue();
//        2.判断结果是否为0
        if (r != 0) {
            //        2.1如果不是0，返回对应信息
            return r == 1 ? Result.fail("库存不足") : Result.fail("不能重复下单");
        }

//        2.2 如果是0，将用户id和订单id存入阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);

        //获取代理对象(事务)
        proxy = (VoucherServiceImpl) AopContext.currentProxy();
//        2.3 返回订单id
        return Result.ok(orderId);

    }*/


    /**
     * 实现优惠券秒杀下单功能
     *
     * @param voucherId
     * @return
     */

/*    @Override
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



        Long userId = UserHolder.getUser().getId();
//        4.使用简易自定义分布式锁
        //4.1创建锁对象(新增代码)
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("order:" + userId);

        //4.2获取锁对象
        boolean b = lock.tryLock();
//      4.3如果获取不到证明已经购买了
        if (!b) {
            return Result.fail("不允许重复下单");
        }

        try {
            //获取代理对象(事务)
            VoucherServiceImpl proxy = (VoucherServiceImpl) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
//            释放锁
            lock.unlock();
        }
    }*/

    /**
     * 创建订单
     *
     * @param voucherOrder
     * @return
     */

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单逻辑
        // 5.1.用户id
        Long userId = voucherOrder.getUserId();
        int count = voucherOrderService.query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
        }

//        4.减少库存
        boolean sucess = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .gt("stock", 0)
                .eq("voucher_id", voucherOrder.getVoucherId()).update();

        if (!sucess) {
            log.error("库存不足");
        }
//        5.插入数据
        voucherOrderService.save(voucherOrder);
    }
}
