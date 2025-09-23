-- 判断用户是否点赞
local key = KEYS[1]
local userId = ARGV[1]
local blogId = ARGV[2]
local score = redis.call('ZSCORE', key, userId)

if score then
    -- 已点赞，取消点赞
    redis.call('ZREM', key, userId)
    -- 调用Java方法更新数据库（实际通过Redis消息队列异步处理）
    redis.call('PUBLISH', 'blog:like:cancel', blogId)
    return 0
else
    -- 未点赞，添加点赞
    redis.call('ZADD', key, ARGV[3], userId)
    redis.call('PUBLISH', 'blog:like:add', blogId)
    return 1
end
