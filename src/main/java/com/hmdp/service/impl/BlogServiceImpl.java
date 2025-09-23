package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Winston
 * @since 2025-8-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog); //页面展示部分用户信息
            this.isBlogLike(blog); // 查看当前用户是否点赞
        });
        return Result.ok(records);
    }
    // 查询笔记，数据库笔记表不包含用户名和头像，所以我们在实体类Blog手动添加这两个，并注释不在表存在的两个字段，因为这两个字段我们手动操作
    @Override
    public Result queryBlogById(Long id) {
        // 根据id查询blog
        Blog blogId = getById(id);
        if(blogId == null){
            // 如果不存在，返回错误
            return Result.fail("该博主不存在！");
        }
        // 查询blog相关的用户,首先先根据笔记发布的作者id得到，通过这个id然后用UserServiceImpl（包含我们想要的User实体类里面的属性）查询得到用户的名字和图片，再将这些内容放到blog里面
        queryBlogUser(blogId);
        isBlogLike(blogId);
        return Result.ok(blogId);
    }
    public void isBlogLike(Blog blog){
        // 未登录的账号会默认执行这里的点赞，而未登录的账号为空
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null){
            return;
        }
        // 1.获取登录用户的id
        Long userId = UserHolder.getUser().getId();
        // 2.判断登录的这个用户是否点过赞 ,set集合key是这个笔记，而value是不同
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

/*    @Override
    public Result likeBlog(Long id) { // id 是笔记id
        // 1.获取登录用户的id
        Long userId = UserHolder.getUser().getId();
        // 2.判断登录的这个用户是否点过赞 ,set集合key是这个笔记，而value是不同
        String key = BLOG_LIKED_KEY + id;
        Double member = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 3.如果该用户未点过赞，一旦点击这个按钮就会执行数据库点赞数+1，并且将用户存入redis的set集合中
        if(member == null){
            boolean update = update().setSql("liked = liked + 1").eq("id", id).update();
            if (update){
                // 如果更新成功，添加到集合
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
        // 4.如果用户点过赞，一旦点击按钮，就会执行以下代码，数据库点赞数量-1，并且将用户从redis的set集合中移除
            boolean update = update().setSql("liked = liked - 1").eq("id", id).update();
            if (update){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString()); // 更新成功才删除
            }

        }
        return Result.ok();
    }*/
    // 查询点赞的前五位用户
    @Override
    public Result queryBlogLikes(Long id) {
        // 1.获取前五位用户id
        String key = BLOG_LIKED_KEY + id;
        Set<String> topFiveIds = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(topFiveIds == null || topFiveIds.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 2.解析id，将字符串变为long类型
        List<Long> collectIds = topFiveIds.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", collectIds);
        // 3.根据id查询对应的用户（比如用户id，名字，头像）
        List<UserDTO> userDTOS =userService.query().
                in("id",collectIds).last("ORDER BY FIELD ( id, " + idStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOS);
    }

    // 查询用户信息并放入blog中
    public void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    // 防止页面点赞和博主点赞同时进行，需要并发控制
    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> LIKE_BLOG_SCRIPT;
    static {
        LIKE_BLOG_SCRIPT = new DefaultRedisScript<>();
        LIKE_BLOG_SCRIPT.setLocation(new ClassPathResource("blog_like.lua"));
        LIKE_BLOG_SCRIPT.setResultType(Long.class);
    }
    // 用户频繁点击并发，需要分布式锁
    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        // 添加分布式锁
        RLock lock = redissonClient.getLock("lock:blog:like:" + id + ":" + userId);
        if (!lock.tryLock()) {
            return Result.fail("操作频繁，请稍后再试");
        }
        try {
            Double member = stringRedisTemplate.opsForZSet().score(key, userId.toString());
            if (member == null) {
                // 更新mysql
                boolean update = update().setSql("liked = liked + 1").eq("id", id).update();
                if (update) {
                    // 更新redis缓存
                    stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                }
            } else {
                boolean update = update().setSql("liked = liked - 1").eq("id", id).update();
                if (update) {
                    stringRedisTemplate.opsForZSet().remove(key, userId.toString());
                }
            }
            return Result.ok();
        } finally {
            lock.unlock();
        }
    }
    // 上传blog，首先就要获取正在操作的用户，把用户id并设置在blog笔记上
    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店博文
        boolean isSuccess = save(blog);
        // 3.如果保存到数据库不成功，返回错误
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        // 4.成功保存到数据库，首先要获取该博客的粉丝
/*   5.select * from tb_follow where follow_user_id = ?
    获取的是各用户的id，还需要获取这些id的粉丝*/
        List<Follow> followUserIds = followService.query().eq("follow_user_id", user.getId()).list();
        // 6.推送笔记id给上一步结果
        for (Follow followUserId : followUserIds) {
            // 这是获取每一个粉丝，但是需要粉丝Id作为key
            Long userId = followUserId.getUserId();
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }
    // 当前用户查询推送的blog笔记
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        // 2.通过用户id 去redis查询blog日志也就是推送的内容
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset,2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 3.遍历推送的内容（包括推送内容和时间戳）
        List<Long> blogList = new ArrayList<>(typedTuples.size());
        long time = 0;
        int offsetTime = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 获取blogIds,但是是字符串类型，转为Long,并将每次遍历的blogId放入一个集合中
            Long blogId = Long.valueOf(typedTuple.getValue());
            blogList.add(blogId);
            // 获取score也就是时间戳,但是我们需要的是最小的时间戳，所以需要每次循环时候将上一次的时间戳覆盖，
            // 所以我们就在外面定义一个随机数，每次循环就覆盖以便下次查询以这个最小时间戳对应的blogId开始，
            // 这个过程还存在时间戳相同情况（同时上传），因此还需要统计offset数量（重复数量）
            long minTime = typedTuple.getScore().longValue();
            if (time == minTime) {
                offsetTime = offsetTime + 1;
            } else {
                time = minTime;
                offsetTime = 1;
            }

        }
        // 4.根据blogId查询blog笔记
        String idStr = StrUtil.join(",", blogList);
        List<Blog> blogIds = query().in("id", blogList).last("ORDER BY FIELD ( id, " + idStr + ")").list();
        // 5.封装
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setOffset(offsetTime);
        scrollResult.setList(blogIds);
        scrollResult.setMinTime(time);
        for (Blog blogId : blogIds) {
            // 查询当前blog作者信息
            queryBlogUser(blogId);
            // 查询点赞
            isBlogLike(blogId);
        }
        // 返回笔记（包括点赞数，）
        return Result.ok(scrollResult);
    }
}