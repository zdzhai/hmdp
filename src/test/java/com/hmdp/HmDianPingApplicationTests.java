package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constants.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedissonClient redissonClient;

    @Test
    void testSaveShop(){
        Shop shop = shopService.getById(1L);
        String key = CACHE_SHOP_KEY + 1L;
        cacheClient.setWithLogicExpire(key, shop, 20L, TimeUnit.SECONDS);
    }

    @Test
    void testRedisson() throws InterruptedException {
        RLock lock = redissonClient.getLock("anyLock");
        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
        if (isLock){
            try {
                System.out.println("执行业务");
            } finally {
                lock.unlock();
            }
        }
    }
}
