package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constants.RedisConstants.*;

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
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id){
        return queryWithMutex(id);
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

    /**
     * 利用redis的setnx方法来表示获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        //todo 这里其实还是会有问题的，有可能方法未执行完的时候，锁就已经过期了，
        //todo 释放锁的时候有可能释放了别人的锁，所以这里可以对锁的名字进行修改为每个线程特有的
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
    public Result queryWithMutex(Long id) {
        //1.校验用户id
        if (id < 0){
            return Result.fail("商铺不存在");
        }
        //2.从redis中查询商铺
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //3.有, 则判断值是否为""
        if ("".equals(shopJson)){
            return Result.fail("商铺不存在");
        }
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        Shop shop = null;
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
                return Result.fail("商铺不存在");
            }
            //6.存在则先写入redis中，然后返回数据
            stringRedisTemplate.opsForValue()
                    .set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7. 释放互斥锁
            unLock(key);
        }
        return Result.ok(shop);
    }
}
