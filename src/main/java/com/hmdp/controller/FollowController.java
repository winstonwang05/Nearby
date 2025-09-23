package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author Winston
 * @since 2025-8-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;

    /**
     * 是否关注
     * @param id 被关注者
     * @param isFollow 是否关注
     * @return 返回关注结果
     * 一旦当前用户isFollow说明关注了，就存储到mysql以及redis中，反之，不存储
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable Long id, @PathVariable Boolean isFollow) {
            return followService.follow(id, isFollow);
    }

    /**
     * 从数据库查询
     * 判断用户是否关注
     * @param id 被关注者id
     * @return 返回当前用户是否关注
     */
    @GetMapping("/or/not/{id}")
    public Result follow(@PathVariable Long id) {
        return followService.isFollow(id);
    }

    /**
     * 查看共同关注对象
     * @param id 当前用户关注的人id
     * @return 返回共同关注者
     * 通过当前用户（UserHolder获取）为key，value是它关注的对象，以及它关注的一个对象为key，value为这个对象的关注的对象；存入redis缓存是由前面接口存储的，不存在就存储
     * 通过这两个value取交集得到共同关注对象，并封装为UserDTO返回，
     *
     */
    @GetMapping("/common/{id}")
    public Result common(@PathVariable Long id) {
        return followService.followCommon(id);
    }

}
