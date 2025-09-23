package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshLoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/*拦截器的的配置*/
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    // 注册一个拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器，需要登陆才能查看需要登陆的路径
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        // 排除之外，拦截器是拦截，排除之外后就不会走拦截器被拦截，可以直接查看
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                                    ).order(1);
        // 注册刷新拦截器
        registry.addInterceptor(new RefreshLoginInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
