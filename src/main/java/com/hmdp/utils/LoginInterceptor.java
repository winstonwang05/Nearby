package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static net.sf.jsqlparser.util.validation.metadata.NamedObject.user;

/*创建第二个拦截器，这个拦截器在第一个拦截器基础上，拦截的是需要登陆界面，也就是在第一个
拦截器中查询得到的用户存储在Thread local中，存在里面的将他取出来就可以访问需要登陆界面
* */
public class LoginInterceptor implements HandlerInterceptor {




    // 前置拦截
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断是否需要拦截（看Thread local中是否存在用户）
        if (UserHolder.getUser() == null) {
            // 不存在，需要拦截，不能访问需要登陆的界面
            response.setStatus(401);
            return false;
        }
        // 存在用户，放行
        return true;
    }


}
