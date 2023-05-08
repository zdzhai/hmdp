package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
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
import java.util.stream.Collectors;

import static com.hmdp.constants.RedisConstants.*;
import static com.hmdp.constants.SystemConstants.DEFAULT_PAGE_SIZE;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author dongdong
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryById(Long id){
        //解决缓存穿透
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class,
//                id2 -> this.getById(id2), CACHE_SHOP_TTL, TimeUnit.SECONDS);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public Result updateShop(Shop shop) {
        //1.校验
        if (shop == null){
            return Result.fail("修改商铺信息错误");
        }
        Long id = shop.getId();
        if (id == null || id <=0 ){
            return Result.fail("店铺id错误");
        }
        //2.先修改数据库
        QueryWrapper<Shop> queryWrapper = new QueryWrapper<>(shop);
        boolean res = this.update(queryWrapper);
        if (!res){
            return Result.fail("修改商铺信息失败");
        }
        //3.删除redis缓存
        String key = CACHE_SHOP_KEY + id;
        Boolean isDelete = stringRedisTemplate.delete(key);
        if (isDelete == null || !isDelete){
            return Result.fail("服务器错误");
        }
        return Result.ok(res);
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.校验 因为x和y不是必须的
        if (x == null || y == null){
            // 根据类型分页查询
            Page<Shop> page = this.query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //3.计算分页参数 第一页就是 从0开始 到每页的数量
        int from = (current - 1) * DEFAULT_PAGE_SIZE;
        int end = current * DEFAULT_PAGE_SIZE;
        //4.根据传入的x,y去redis中计算typeId类型店铺中和x，y 的所有距离 返回 shopId,distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );

        //5.解析id
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from){
            //没有下一页
            return Result.ok(Collections.emptyList());
        }
        //5.1截取from-end部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> idDistanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(res ->{
            String shopIdStr = res.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = res.getDistance();
            idDistanceMap.put(shopIdStr,distance);
        });
        //5.2根据id查询数据库
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(idDistanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }

    /**
     * 利用redis的setnx方法来表示获取锁
     * @param key
     * @return
     **/
    private boolean tryLock(String key){
        //todo 这里其实还是会有问题的，有可能方法未执行完的时候，锁就已经过期了，
        //todo 释放锁的时候有可能释放了别人的锁，所以这里可以对锁的名字进行修改为每个线程特有的Id表示
        //todo 但是还有个问题是判断标识和释放锁是两个动作，也有可能会出现线程安全问题，所以后续使用lua脚本保证原子性
        //todo 所以可以使用redisson实现分布式锁
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 利用互斥锁获取数据，防止缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        //1.校验用户id
        if (id < 0){
            return null;
        }
        //2.从redis中查询商铺
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //3.有, 则判断值是否为""
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if ("".equals(shopJson)){
            return null;
        }
        Shop shop;
        //4.使用互斥锁解决缓存击穿问题，没有则先获取互斥锁，获取到锁资源的，则去查询数据库
        //注意这里是锁的key
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            boolean isLock = tryLock(lockKey);
            //获取锁失败，则休眠50ms，再去执行一遍方法，重新查缓存 抢锁
            if (!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            shop = this.getById(id);
            //模拟重建数据的延时
            Thread.sleep(200);
            //5.不存在也写入redis
            if (shop == null){
                //解决缓存穿透问题，即使数据不存在，也向redis中写入数据，只不过写入的是null值
                stringRedisTemplate.opsForValue()
                        .set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在则先写入redis中，然后返回数据
            stringRedisTemplate.opsForValue()
                    .set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7. 释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

    /**
     * 设置逻辑过期解决缓存击穿
     * @param id
     * @return
     */
/*    public Shop queryWithLogicExpire(Long id) {
        //1.校验用户id
        if (id < 0){
            return null;
        }
        //2.从redis中查询商铺
        String key = CACHE_SHOP_KEY + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //3.有, 则判断值是否为""
        if (StrUtil.isBlank(json)){
            return null;
        }
        //4.命中则需要先反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            // 未过期直接返回
            return shop;
        }
        //已过期，进行缓存重建
        //注意这里是锁的key
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //获取锁成功，进行缓存重建，获取锁失败直接返回原来的过期数据
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //7. 释放互斥锁
                    unLock(lockKey);
                }
            });
        }
        return shop;
    }*/
    /**
     * 解决缓存穿透的查询
     * @param id
     * @return
     */
/*    public Shop queryWithPassThrough(Long id){
        //1.从redis中查询商铺
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.有, 则判断值是否为""
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if ("".equals(shopJson)){
            return null;
        }
        Shop shop = this.getById(id);
        //4. 不存在，将null写入redis
        if (shop == null){
            //将null写入redis
            stringRedisTemplate.opsForValue()
                    .set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //5. 存在 写入redis
        stringRedisTemplate.opsForValue()
                .set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }*/

/*    *//**
     * 保存热点数据到redis中
     * @param id
     * @param expireSeconds
     *//*
    public void saveShop2Redis(Long id, Long expireSeconds){
        //1. 查询店铺数据
        Shop shop = this.getById(id);
        //2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue()
                .set(CACHE_SHOP_KEY, JSONUtil.toJsonStr(redisData));
    }*/
}
