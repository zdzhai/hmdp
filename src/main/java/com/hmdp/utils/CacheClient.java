package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.constants.RedisConstants.LOCK_SHOP_KEY;

/**
 * @author dongdong
 * 缓存工具类
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓
     * 存击穿问题
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        //设置过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData),time,unit);
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     * @param keyPreFix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R,ID> R queryWithPassThrough(String keyPreFix, ID id, Class<R> type,
                                         Function<ID,R> dbFallback, Long time, TimeUnit unit){
        //1.从redis中查询商铺
        String key = keyPreFix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.有, 则判断值是否为""
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        if ("".equals(json)){
            return null;
        }
        //3. 不存在，根据id去数据库查
        R r = dbFallback.apply(id);
        //4. 不存在，将null写入redis
        if (r == null){
            //将null写入redis
            this.set(key,"",time,unit);
            return null;
        }
        //5. 存在 写入redis
        this.set(key, r, time, unit);
        return r;
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
     * 根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
     * @param keyPreFix
     * @param id
     * @param type
     * @param dbFallBack
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R,ID> R queryWithLogicExpire(String keyPreFix, ID id, Class<R> type,
                                         Function<ID,R> dbFallBack, Long time, TimeUnit unit) {
        //1.校验用户id
        //2.从redis中查询商铺
        String key = keyPreFix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //3.有, 则判断值是否为""
        if (StrUtil.isBlank(json)){
            return null;
        }
        //4.命中则需要先反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            // 未过期直接返回
            return r;
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
                    //1. 查询数据库
                    R r1 = dbFallBack.apply(id);
                    //2. 写入redis
                    this.setWithLogicExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //7. 释放互斥锁
                    unLock(lockKey);
                }
            });
        }
        return r;
    }
}
