package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import jdk.internal.org.objectweb.asm.tree.TryCatchBlockNode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁缓存击穿
//        Shop shop = queryWithMutex(id);
        //逻辑过期时间缓存击穿
        Shop shop = queryWithLogicaExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }
    public Shop queryWithMutex(Long id) {
        // 从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            //存在就返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return null;
        }
        if (shopJson != null){
            return null;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //不存在，就根据id查询数据库
            shop = getById(id);
            Thread.sleep(200);
            //数据库不存在，返回错误
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "",
                        CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //数据库存在，写到redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),
                    30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }
        //返回
        return shop;
    }
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    private static final ExecutorService CHECH_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicaExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //判断是否存在
        if (StrUtil.isBlank(shopJson)){
            //不存在就返回null
            System.out.println("这里直接不存在缓存");
            return null;
        }
        // 4. 命中就json反序列化成对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(jsonObject, Shop.class);
        LocalDateTime localDateTime = redisData.getExpireTime();
        // 5.判断缓存是否过期
        if (localDateTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期就返回店铺信息
            return shop;
        }
        //5.2 过期就缓存重建
        // 6.缓存重建
        //6.1 获取互斥锁
        boolean isLock = tryLock(LOCK_SHOP_KEY + id);
        if (isLock) {
            //6.2 成功则开启独立线程，执行缓存重建
            CHECH_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(LOCK_SHOP_KEY + id);
                }
            });
        }

        //6.3 失败就返回原始店铺信息
        return shop;

    }


    public Shop queryWithPassThrough(Long id) {
        // 从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            //存在就返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return null;
        }
        if (shopJson != null){
            return null;
        }
        //不存在，就根据id查询数据库
        Shop shop = getById(id);
        //数据库不存在，返回错误
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "",
                    CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //数据库存在，写到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),
                30, TimeUnit.MINUTES);
        //返回
        return shop;
    }
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("shop id 为空");
        }
        updateById(shop);

        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

        return Result.ok(shop);
    }
}
