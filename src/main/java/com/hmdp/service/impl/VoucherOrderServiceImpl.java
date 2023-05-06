package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //创建阻塞队列
    private BlockingQueue<VoucherOrder> voucherOrderTask = new ArrayBlockingQueue<>(1024 * 1024);
    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private IVoucherOrderService proxy;
    //在类初始化成功后，运行这个线程池
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            //读取堵塞队列中的值，创建订单
            while (true){
                try {
                    VoucherOrder voucherOrder = voucherOrderTask.take();
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e){
                    log.error("处理订单异常",e);
                }

            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //采用redis实现分布式锁
//        ILock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
        //用redisson分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //尝试获取锁
        boolean success = lock.tryLock();
        //获取锁失败，直接返回
        if (!success){
            log.error("不允许重复下单");
        }
        try {
            //获取当前对象的代理对象
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    @Override
    /**
     * 这里我们使用lua脚本实现判断库存和用户是否下单
     */
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        if (r != 0){
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //2.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.1 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.2用户id
        voucherOrder.setUserId(userId);
        //2.3 优惠券id
        voucherOrder.setVoucherId(voucherId);
        //把订单放到阻塞队列中，异步的执行创建订单
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        voucherOrderTask.add(voucherOrder);

        return Result.ok(orderId);
    }
    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getId();
        //查询订单
        int count = this.query().eq("voucher_id", voucherOrder.getVoucherId())
                .eq("user_id", userId).count();
        if (count > 0){
           log.error("用户已经购买过了");
           return;
        }
        //扣减库存 乐观锁解决超卖问题
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0).update();
        //这种方式会导致许多线程都无法抢到优惠券
        //                .eq("stock",seckillVoucher.getStock()).update();
        if (!success){
            log.error("库存不足");
        }
        this.save(voucherOrder);
    }
/*    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null){
            return Result.fail("优惠券不存在");
        }
        LocalDateTime beginTime = seckillVoucher.getBeginTime();
        LocalDateTime endTime = seckillVoucher.getEndTime();
        //2. 秒杀是否开始或结束，如果尚未开始或已经结束则无法下单
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(endTime)){
            return Result.fail("活动已结束");
        }
        if (now.isBefore(beginTime)) {
            return Result.fail("活动尚未开始");
        }
        //3. 库存是否充足，不足则无法下单
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        //4.库存充足，
        //5.根据优惠券id和用户id查询订单表，看用户是否已下单
        Long userId = UserHolder.getUser().getId();
        //采用redis实现分布式锁
//        ILock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
        //用redisson分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //尝试获取锁
        boolean success = lock.tryLock();
        //获取锁失败，直接返回
        if (!success){
            return Result.fail("不允许重复下单");
        }
        try {
            //获取当前对象的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }
*//*        //这里直接锁方法的话，锁的粒度太大，我们只需要锁当前用户即可
        //但是toString()方法会创建新的字符串对象，所以使用inter()方法去常量池中找值相同的值
        //这里还需要，Spring管理事务是使用的AOP动态代理，
        // 所以我们应该使用动态代理对象来条用创建优惠券的方法
        synchronized (userId.toString().intern()) {
            //获取当前事务的代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }*//*
    }*/

/*    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
            Long userId = UserHolder.getUser().getId();
            //查询订单
            int count = this.query().eq("voucher_id", voucherId)
                    .eq("user_id", userId).count();
            if (count > 0){
                return Result.fail("用户已经购买过了");
            }
            //扣减库存 乐观锁解决超卖问题
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock",0).update();
            //这种方式会导致许多线程都无法抢到优惠券
    //                .eq("stock",seckillVoucher.getStock()).update();
            if (!success){
                return Result.fail("库存不足");
            }
            //6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //6.1 订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //6.2用户id
            voucherOrder.setUserId(userId);
            //6.3 优惠券id
            voucherOrder.setVoucherId(voucherId);
            this.save(voucherOrder);
            //7.返回订单id
            return Result.ok(orderId);
    }*/
}
