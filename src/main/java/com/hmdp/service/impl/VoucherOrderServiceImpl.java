package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import io.lettuce.core.RedisException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Winston
 * @since 2025-8-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    // 用阻塞队列来完成异步执行（注释隐藏掉了）
    /*    // 创建阻塞队列对象，用来执行下单操作
        private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
        // 阻塞队列是由线程执行，因此定义一个线程池,这里定义了一个单个线程的，所以不用传参
        private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
        // 还需要让线程执行任务
        // 但是我们需要这个执行的任务要在这个类初始化就马上执行，所以需要spring的注解方法首先
        @PostConstruct
        private void init() {
            SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
        }
        // 执行线程任务
        public  class VoucherOrderHandler implements Runnable {
            @Override
            public void run() {

            }
        }*/
    // 注入秒杀券的接口
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    // 注入全局唯一id生成器
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    // 执行lua脚本，先写静态代码块
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    // 定义线程池，来处理消息的
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


    // 添加线程池销毁方法
    @PreDestroy
    private void destroy() {
        // 尝试优雅关闭线程池
        SECKILL_ORDER_EXECUTOR.shutdown();
        try {
            // 等待3秒让任务完成，超时则强制关闭
            if (!SECKILL_ORDER_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                SECKILL_ORDER_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            SECKILL_ORDER_EXECUTOR.shutdownNow();
        }
    }
    // 还需要让线程执行任务
    // 但是我们需要这个执行的任务要在这个类初始化就马上执行，所以需要spring的注解方法首先
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    // 执行线程任务
    public  class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 1.首先，需要通过消费者读取也就是获取消息中的订单信息
                    // redis语句：XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),// 消费组
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),// 每次读的消息数，以及阻塞时长
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())// 也就是消费者组读取的位置，以及从哪里开始读，肯定设置上一次的（消费标识）
                    );
                    // 2.判断消息是否存在，不存在，就continue，因为在循环里面，就可以一直查询存不存在
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    // 3. 存在，可以直接下单
                    // 3.1 还需要解析消息中的订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // 4.通过ACK确认SACK
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (RedisException e) {
                    log.error("Redis操作异常", e);
                    // 短暂休眠避免CPU空转
                    try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                }
            }
        }
        // 未被确认的消息会放入pending-list里面，我们需要将其再次取出来
        private void handlePendingList() {
            while (true) {
                try {
                    // 1.首先，异常信息会放入pending-list，我们需要从里面再次取出来
                    // redis语句：XREADGROUP GROUP g1 c1 COUNT 1 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),// 消费组
                            StreamReadOptions.empty().count(1), // 每次读的消息数，以及阻塞时长
                            StreamOffset.create(queueName, ReadOffset.from("0"))//0是被消费者消费过了，但没被确认
                    );
                    // 2.判断消息是否存在，不存在，就continue，因为在循环里面，就可以一直查询存不存在
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    // 3. 存在，可以直接下单
                    // 3.1 还需要解析消息中的订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // 4.通过ACK确认SACK
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e)  {
                    log.error("处理pending-list订单异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e1) {
                        throw new RuntimeException(e1);
                    }

                }
            }
        }
    }
    // 创建订单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean tryLock = lock.tryLock();
        // 尝试获取锁  获取失败
        if (!tryLock) {
            log.error("不允许重复下单！");
            return;
        }
        // 获取成功 上锁
        try {
            // 由spring管理的代理对象
            proxy.creatVoucherOrder(voucherOrder);
            return ;
        } finally {
            lock.unlock();
        }
    }

    // 获取代理对象
    private IVoucherOrderService proxy;
    @Override
    public Result secKillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");
        // 执行lua脚本并且会将订单消息发送给队列执行
        Long execute = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        // 判断lua脚本结果是否为0.不为0，没有购买资格
        int result = execute.intValue();
        if (result != 0) {
            return Result.fail(execute == 1 ? "库存不足！":"不能重复下单！");
        }
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单id
        return Result.ok(orderId);

    }
    @Transactional // 由于有订单库存扣减，以及创建一个新订单，所以需要一个事务
    public void creatVoucherOrder (VoucherOrder voucherOrder) {
        /*一人一单*/
        Long userId = voucherOrder.getUserId();
        /*查询订单*/
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        /*超过数量不能购买*/
        if (count > 0) {
            log.error("用户已经购买过一次！");
            return;
        }
        // 5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1 ")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0) // 乐观锁解决超卖问题
                .update();
        if (!success) {
            log.error("库存不足！");
            return ;
        }

        // 6.创建订单
        save(voucherOrder);

    }

}
/*    @Override
    public Result secKillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀券是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀券是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        // 4.在秒杀券时间内，库存是否够，不够返回错误
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
*//*        // 获取锁对象,并设置过期时间释放
        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);*//*
        // 通过Redisson获取锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean tryLock = lock.tryLock();
        // 尝试获取锁  获取失败
        if (!tryLock) {
            return Result.fail("您已经购买过一次！");
        }
        // 获取成功 上锁
        try {
            // 由spring管理的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.creatVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }*/

