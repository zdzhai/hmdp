package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {

    /**
     * 查询店铺的优惠券
     * @param shopId
     * @return
     */
    Result queryVoucherOfShop(Long shopId);

    /**
     * 给店铺添加优惠券
     * @param voucher
     */
    void addSeckillVoucher(Voucher voucher);
}
