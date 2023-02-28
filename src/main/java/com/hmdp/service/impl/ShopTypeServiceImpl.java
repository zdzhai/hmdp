package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constants.RedisConstants.*;

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

//    @Override
//    public List<ShopType> queryShopType() {
//        //2.从redis中查询商铺
//        // todo redis List结构的使用
//        String key = SHOP_TYPE_KEY;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //3.有则直接返回
//        if (StrUtil.isNotBlank(shopJson)){
//            ShopType shopType = JSONUtil.toBean(shopJson, ShopType.class);
//            return Result.ok(shopType);
//        }
//        //4.没有则去查询数据库
//        List<ShopType> shopTypeList = this.query().orderByAsc("sort").list();
//        //5.不存在直接返回404
//        if (shopTypeList == null){
//            return Result.fail("店铺不存在");
//        }
//        //6.存在则先写入redis中，然后返回数据
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
//        stringRedisTemplate.expire(key,CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return Result.ok(shop);
//        return null;
//    }
}
