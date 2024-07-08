package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryShopList() {

        // 从redis查询缓存
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range("shop-type", 0, -1);

        // json 转List<ShopType>
        List<ShopType> chechListShopType = new ArrayList<ShopType>();
        //判断是否存在
        if (!shopTypeJson.isEmpty()){
            //存在就返回
            for (String s : shopTypeJson) {
                chechListShopType.add(JSONUtil.toBean(s, ShopType.class));
            }
            chechListShopType.sort(Comparator.comparing(ShopType::getSort));
            return Result.ok(chechListShopType);
        }

        //不存在，就查询所有的记录
        List<ShopType> listShopType =  list();
        //数据库不存在，返回错误
        if (listShopType == null) {
            return Result.fail("不存在店铺");
        }
        //数据库存在，写到redis
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop));
        for (ShopType shopType : listShopType) {
            stringRedisTemplate.opsForList().leftPush("shop-type", JSONUtil.toJsonStr(shopType));
        }

        //返回

        return Result.ok(listShopType);
    }
}
