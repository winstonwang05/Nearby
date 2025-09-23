package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Winston
 * @since 2025-8-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopTypeList() {
        String key = "cache:shopType:";
        // 1.从redis缓存里面所有商铺
        String shopType = stringRedisTemplate.opsForValue().get(key);
        // 2.如果存在，封装为java对象返回
        if (StrUtil.isNotBlank(shopType)) {
            List<ShopType> shopTypesList = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(shopTypesList);
        }
        // 3.不存在，从mysql中查询
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        // 4.mysql中不存在，错误
        if (shopTypes.isEmpty()) {
            return Result.fail("店铺类型不存在！");
        }
        // 5.mysql中存在，返回商铺信息，并存储在redis缓存中
        // 需要将java对象封装为json对象
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypes));
        return Result.ok(shopTypes);
    }
}
