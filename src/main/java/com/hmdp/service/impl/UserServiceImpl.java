package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;

import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;


import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Winston
 * @since 2025-08-16
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    // 生成验证码并发送
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
        // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.如果符合，生成随机验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 6，返回成功
        return Result.ok();

    }
    // 校验验证码（与随机生成的验证码比对）
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        // 获取手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.手机号不一致，返回错误
            return Result.fail("手机号输入格式错误");
        }
    /*  3.手机号一致
        4.获取随机生成的验证码*/
        // 5.检验验证码
        // 获取前端用户输入的验证码 和 随机生成的验证码（从redis的session获取）
        String code = loginForm.getCode();
        String catchCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        // 6，验证码不一致，返会错误
        if (catchCode == null || !catchCode.equals(code)) {
            return Result.fail("验证码输入错误！");
        }
        // 7.验证码一致
        // 8.根据手机号判断用户是否存在
        User user = query().eq("phone", phone).one();
        if (user == null) {
        // 9.不存在，保存在数据库中，然后并保存在session中
            user = createUserByPhone(phone);
        }
/*      9.将用户存储在redis中
         给每个手机号生成token令牌，前端通过令牌验证手机号是否存在（保证用户的安全性，不把手机号存储在每次session中，而是令牌）*/
        String token = UUID.randomUUID().toString(true);
        // 10.将用户转化为hashmap存储在redis中
/*        首先将User转成UserDTO保证安全性（只展示必要部分给前端）
        再将UserDTO转成Map*/
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        session.setAttribute("token", token);
         /*在第十一步的存储去redis中，由于StringRedisTemplate的泛型都是String类型
                     因此这里调用工具将第二个泛型Object变为String*/
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true) //忽略空值
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 11.存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, stringObjectMap);
        // 设置token的有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }
    // 实现用户登出
    @Override
    public Result logout(HttpSession session) {
        // 1.获取session中的token
        String token = (String) session.getAttribute("token");
        // 1.清理redis中的token
        if (token != null) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
        // 2.删除session中的token
        session.removeAttribute("user");
        session.removeAttribute("token");
        // 2.返回结果
        return Result.ok();
    }
    // 根据手机号创建一个新用户
    private User createUserByPhone(String phone) {
/*        创建新用户
        用户名随机  */
        User user = new User();
        user.setPhone(phone);
        //     public static final String USER_NICK_NAME_PREFIX = "user_";
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
    // 实现用户签到功能
    @Override
    public Result sign() {
        // 1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取当前时间并格式化为yyyyMM
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 3.将用户id和时间拼接作为key，value存的是是否签到，1为签到，0为未签到
        String signKey = USER_SIGN_KEY + userId + keySuffix;
        // 4.返回结果
        // 获取当前日
        int dayOfMonth = now.getDayOfMonth();
        // offset就是从哪天开始，而这里dayOfMonth从1~31天计算，下面offset是从0到30（因此第一天就是0 ）
        // 签到成功就是true，才会执行下面语句，写入redis
        stringRedisTemplate.opsForValue().setBit(signKey, dayOfMonth - 1, true);
        return Result.ok();
    }
    // 实现统计用户连续签到次数
    @Override
    public Result signCount() {
        // 1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取当前时间并格式化为yyyyMM
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 3.将用户id和时间拼接作为key，value存的是是否签到，1为签到，0为未签到
        String signKey = USER_SIGN_KEY + userId + keySuffix;
        // 获取当前日
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截至到现在的签到（通过当前登录用户key获取）去redis得到是一个是一个十进制数字
        List<Long> results = stringRedisTemplate.opsForValue().bitField(
                signKey,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
                        // unsigned表示无符号，然后这里我们是需要遍历每一个比特位，一个比特位对应一天，因为每一个比特位就代表
                        // 每一天，获取遍历得到这个范围得到是一个十进制。valueAt是从哪里开始，索引从开始

        );
        // 获取的是list集合，里面包含了set和get
        if (results ==  null || results.isEmpty()) {
            return Result.ok(0);
        }
        // 获取的第一个索引就是我们需要的十进制数字
        Long numberValue = results.get(0);
        if (numberValue == null) {
            return Result.ok(0);
        }
        // 6.将得到的结果循环遍历并计数
        int count = 0;
        while (true) {
            // 首先将十进制进行与1的与&运算
            if ((numberValue & 1) == 0) {
                // 如果结果是0，退出循环，表明连续签到中途掉了
                break;
            } else {
                // 如果是1，计数加1
                count++;
                // 每循环一次需要右移一位比特位
                numberValue >>>= 1; // 三个就是无符号右移，我们平常两个是有符号
            }
        }
        // 7.返回
        return Result.ok(count);
    }

}
