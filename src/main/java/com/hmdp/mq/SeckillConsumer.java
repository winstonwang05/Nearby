package com.hmdp.mq;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Service
@Slf4j
public class SeckillConsumer {

    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(queues = RabbitMQConfig.SECKILL_QUEUE)
    public void handleSeckillMessage(VoucherOrder voucherOrder) {
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
            // 调用事务方法创建订单
            voucherOrderService.creatVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }


}
