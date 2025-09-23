package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/*创建第一个刷新拦截器，能访问所有的路径,并查询到用户存在的并存储了Thread local中
* */
public class RefreshLoginInterceptor implements HandlerInterceptor {
    /*这里拦截器并没有注解得到对redis操作的stringRedisTemplate，
    通过拦截器配置是spring容器的bean对象，通过注入得到redis的stringRedisTemplate*/
    private final StringRedisTemplate stringRedisTemplate;

    public RefreshLoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 前置拦截
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取session对象
        // 获取请求头中的token
        String token = request.getHeader("authorization");
        // 检验token是否存在
        if (StrUtil.isBlank(token)) {

            return true;
        }
        // 获取的token有效
        // 2.通过token从redis中获取用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        // 判断用户是否存在
        if (entries.isEmpty()) {

            return true;
        }
        /*redis获取的是map对象，所以还需要将其转化为UserDTO对象*/
        UserDTO userDTO = BeanUtil.fillBeanWithMap(entries, new UserDTO(), false);
        // 4.存在，将其放入ThreadLocal
        UserHolder.saveUser(userDTO);
        // 5.刷新token
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 6.放行
        return true;
    }
    // 去除线程（让其他进程能用到线程），后置拦截
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }


}
