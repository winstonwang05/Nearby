package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;
// expireTime缓存击穿（通过逻辑过期），并和一些实现类对象，业务逻辑需要解决缓存问题（穿透和击穿），实现类就是下面的data
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
