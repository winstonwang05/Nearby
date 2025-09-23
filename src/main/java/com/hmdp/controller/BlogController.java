package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author Wiston
 *
 */
@RestController
@RequestMapping("/blog")
public class BlogController {
    @Resource
    private IUserService userService;
    @Resource
    private IBlogService blogService;

    /**
     * 添加blog，并推送到粉丝
     * @param blog 用户添加的blog信息
     * @return 返回blogId
     */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /**
     * 点赞功能
     * @param id blogId
     * @return 返回点赞结果
     * 通过blogId作为key，值是两部分，一个存储的是已经点赞的用户，另外一个部分是时间戳
     */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    /**
     * 得到前五位点赞用户
     * @param id blogId
     * @return 返回点赞用户信息，按照先后存储到redis的userId顺序
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    /**
     * 查询用户的blogs，登录用户本人
     * @param current 分页参数
     * @return 返回分页后blogs（登录的本人，查看本人的主页）
     */
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 分页查询点赞前五的blogs
     * @param current 分页参数，默认从第一页
     * @return 返回blogs分页结果
     */
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    /**
     * 根据作者id查询具体一个blog
     * @param id 作者id
     * @return 返回一个具体的blog，，并不是分页
     */
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    /**
     * 查看blog作者主页作品，分页查看
     * @param userId blog作者
     * @param current 分页，默认从第一页开始，size是在常量中定义好了的，所以不需要传
     * @return 返回作者发布的blogs
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam("id") Long userId,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        Page<Blog> blogPage = blogService.query()
                .eq("user_id", userId).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = blogPage.getRecords();
        return Result.ok(records);
    }

    /**
     * 推送blog给粉丝
     * @param max 最大，从最大时间戳开始查询
     * @param offset 偏移量
     * @return 返回blogs给用户
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
            return blogService.queryBlogOfFollow(max, offset);
    }

}
