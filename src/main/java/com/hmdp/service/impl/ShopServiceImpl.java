package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    // 注入redis操作
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    // 注入解决缓存问题工具
    @Resource
    private CacheClient cacheClient;
    // 查询店铺（加缓存）
    @Override
    public Result queryById(Long id) {
/*        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough
                        (CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);*/
        // 解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById ,
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES);

/*        if (shop == null) {
            return Result.fail("店铺不存在！");
        }*/

        return Result.ok(shop);
    }
    // 更新店铺（加缓存），只有查询的时候未命中缓存才会将数据库写入缓存
    @Override
    @Transactional // 需要添加事务使执行更新数据库和删除缓存在同一个线程下完成
    public Result update(Shop shop) {
        Long shopId = shop.getId();
        // 如果店铺的id为null不能执行更新操作
        if (shopId == null) {
            return Result.fail("店铺id不能为空！");
        }
        // 1.先更新数据库
        boolean result = updateById(shop);
        if (result) {
            // 2.再删除缓存
            stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
        }
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断店铺是否需要坐标查询
        if (x == null || y == null) {
            // 2.不需要坐标查询就去数据库直接查询
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        // 3.需要坐标查询，去redis查询按距离和店铺分页，返回的结果是shopId和distance
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key, //店铺类型
                GeoReference.fromCoordinate(x, y),// 坐标
                new Distance(5000), // 距离
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end) // 从0开始读，一直读到end
        );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        // 4.解析获取得到的typeId，里面是店铺和distance
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size() <= from) {
            // 假设查询一页5个数据，总的11个数据，第三页就会出现空，第三页只有一个数据，
            // from就是分页参数第一页5个数据，第二页10条数据（在原来基础上），第三页15条数据，因为分页也就是end每页只展示5条
            // 相当于from占总条数，end是每页查询的固定条数
            return Result.ok(Collections.emptyList());
        }
        List<Long> shopIds = new ArrayList<>(content.size());
        Map<String, Distance> map = new HashMap<>();
        // 获取的内容是店铺和distance，但是需要分页截取，截取from到end,跳过from就是跳过前面已经读取的分页了，现在重新继续在这基础上继续读
        content.stream().skip(from).forEach(geoResult -> {
            // 获取shopId
            String shopId = geoResult.getContent().getName();
            shopIds.add(Long.valueOf(shopId));
            // 获取distance
            Distance distance = geoResult.getDistance();
            // 但是我们需要店铺Id和distance之间有关联，所以创建一个map集合，key为店铺，value为distance
            map.put(shopId,distance);
        });
        // 5.封装为shop返回（需要将distance一起）
        String join = StrUtil.join(",", shopIds);
        List<Shop> shopList = query().in("id", shopIds).last("ORDER BY FIELD ( id," + join + ")").list();
        for (Shop shop : shopList) {
            // 先map通过key（shop的id需要转化为字符串），得到distanc，但是还需要转化为double类型
            shop.setDistance(map.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shopList);
    }
}
