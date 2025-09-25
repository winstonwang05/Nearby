# Nearby - 高并发秒杀练手项目

这是一个基于 **Spring Boot** 的高并发场景练习项目，主要用于学习和实践 **Redis、RabbitMQ、分布式锁、消息队列、秒杀下单** 等常见后端技术。

## 项目介绍

本项目模拟了电商平台中的 **秒杀功能**，通过引入缓存、分布式锁和消息队列来保证系统在高并发场景下依旧能够正确处理订单，避免超卖和并发安全问题。

核心目标是掌握在真实业务场景下如何设计和实现一个可扩展、可抗压的后端服务。

## 技术栈

- **后端框架**：Spring Boot
- **缓存中间件**：Redis
- **消息队列**：RabbitMQ
- **持久层框架**：MyBatis-Plus
- **数据库**：MySQL
- **构建工具**：Maven
- **虚拟化环境**：Docker（用于快速启动 Redis 和 RabbitMQ）

## 功能实现

- 用户下单与库存扣减
- 秒杀商品接口优化
- 分布式锁保证一人一单
- Redis + Lua 脚本实现原子性扣减
- RabbitMQ 异步下单，削峰填谷
- 多线程并发测试与优化

## 模块设计

- **config**：
  配置 RabbitMQ（交换机、队列、路由键等），以及 Redis 分布式锁。
- **consumer**：
  RabbitMQ 消费者，负责监听消息并执行下单逻辑。
- **service**：
  核心业务逻辑，例如库存校验、下单、Redis 脚本调用。
- **controller**：
  对外提供下单接口，接收请求后将消息投递到消息队列。

## 项目亮点

- 使用 **Lua 脚本** 确保 Redis 操作的原子性，避免超卖。
- 结合 **分布式锁** 保证一人一单。
- 使用 **RabbitMQ 异步消息** 将下单与支付解耦，提升系统吞吐量。
- 支持 **Docker 一键部署**，快速启动所需中间件。

## 本地运行

1. 克隆项目

   ```
   git clone https://github.com/winstonwang05/Nearby.git
   ```

2. 启动 RabbitMQ 和 Redis（推荐使用 Docker）

   ```
   docker run -d --name redis -p 6379:6379 redis:latest
   docker run -d --name mq -e RABBITMQ_DEFAULT_USER=YOUR_NAME -e RABBITMQ_DEFAULT_PASS=YOUR_PASSWORD -p 5672:5672 -p 15672:15672 rabbitmq:3.8-management
   ```

3. 修改 `application.yml` 配置 Redis 与 RabbitMQ 地址。

4. 启动 Spring Boot 项目。

5. 访问秒杀接口进行测试。

## TODO

- 接入 Spring Security 完善登录认证
- 接入 Nginx + 前端页面模拟真实秒杀场景
- 完善接口压测与性能优化
