package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Winston
 * @since 2025-8-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取当前登录的用户id
        Long userId = UserHolder.getUser().getId();
        // 2.判断用户是否关注,去数据库查看是否存在这条数据就知道
        //  select * from tb_follow where user_id = ? and follow_user_id = ?
        long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 3.返回，与前端响应，所以返回关注结果
        // 如果大于0说明存在，也就表明存在
        return Result.ok(count > 0);
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取当前登录的用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        // 2.判断用户是否关注
        if (isFollow) {
            // 2.1 关注了，保存到数据库
            // 获取博主的id和当前用户id存储到数据库
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 如果关注成功存入数据库，并且将当前用户作为key，它关注的人作为value存入redis的缓存中
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 2.2 没关注，从数据库中移除 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isFailed = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if (isFailed) {
                // 如果并没有关注，将当前用户关注的移除
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }
    // 获取共同关注
    @Override
    public Result followCommon(Long followId) {
        // 1.获取当前用户userId以及当前页面的id
        Long userId = UserHolder.getUser().getId();
        String keyUserId = "follow:" + userId;
        String keyFollowUserId = "follow:" + followId;
        // 2.去redis数据库查询这两者（key），value就是他们各自关注的，然后取交集得到共同id
        Set<String> intersectIds = stringRedisTemplate.opsForSet().intersect(keyUserId, keyFollowUserId);
        // 还需判断交集是否为空，不然为空导致后面的stream流为空
        if (intersectIds == null || intersectIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 3.解析(将String转化为Long)得到的共同id
        List<Long> ids = intersectIds.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.通过转化为UserDTO返回
        // 还需通过这些id查询用户对应信息
        List<UserDTO> userDTOS = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
