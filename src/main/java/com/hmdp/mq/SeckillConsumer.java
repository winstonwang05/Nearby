package com.hmdp.mq;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
// 监听者（消费者）
@Service
@Slf4j
public class SeckillConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    @RabbitListener(queues = RabbitMQConfig.SECKILL_QUEUE)
    public void handleSeckillMessage(VoucherOrder order) {
        try {
            // 分布式锁（防止同一用户重复下单）
            RLock lock = redissonClient.getLock("lock:order:" + order.getUserId());
            if (!lock.tryLock()) {
                log.error("不允许重复下单：{}", order.getUserId());
                return;
            }
            try {
                // 调用事务方法创建订单
                voucherOrderService.createVoucherOrder(order);
            } finally {
                lock.unlock();
            }
        } catch (Exception e) {
            log.error("处理订单异常", e);
        }
    }
}
