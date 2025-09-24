--[[准备优惠券id，用户id，获取库存的key，获取订单的key]]
local voucherId = ARGV[1]
local userId = ARGV[2]
--[[订单id]]
local orderId = ARGV[3]
--[[库存key]]
local stockKey = 'seckill:stock:' .. voucherId
--[[订单key]]
local orderKey = 'seckill:order:' .. voucherId
--[[首先判断库存是否充足，不充足，返回1]]
--[[由于得到的库存是字符串，需要转化为数字才能比较]]
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end
    --[[判断用户id在set集合里面是否存在，存在，放回2]]
if (redis.call('sismember', orderKey,userId) == 1) then
    return 2
end
    --[[不存在，需要减去库存，并且将这个用户id存入set集合中]]
redis.call('incrby', stockKey , -1)
redis.call('sadd', orderKey,userId)
--[[发送消息到队列中让消费者执行， XADD stream.orders * k1 v1 k2 v2...]]--[[

redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0]]
