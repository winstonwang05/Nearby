package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
// 交换机与队列的绑定
@Configuration
public class RabbitMQConfig {

    // 秒杀队列和交换机
    public static final String SECKILL_QUEUE = "hmdp.seckill.queue";
    public static final String SECKILL_EXCHANGE = "hmdp.seckill.exchange";
    public static final String SECKILL_ROUTING_KEY = "seckill";

    /**
     * 秒杀队列 - 持久化
     */
    @Bean
    public Queue seckillQueue() {
        return new Queue(SECKILL_QUEUE, true);
    }

    /**
     * 直连交换机 - 持久化
     */
    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE, true, false);
    }

    /**
     * 绑定队列到交换机
     */
    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillQueue())
                .to(seckillExchange())
                .with(SECKILL_ROUTING_KEY);
    }
}