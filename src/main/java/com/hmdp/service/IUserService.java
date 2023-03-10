package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 发送短信验证码
     * @param phone
     * @param session
     * @return Result
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 验证登录信息
     * @param loginFormDTO
     * @param session
     * @return
     */
    Result login(LoginFormDTO loginFormDTO, HttpSession session);

    /**
     * 使用redis的BitMap功能实现用户打卡功能
     * @return
     */
    Result sign();

    /**
     * 用户连续签到统计
     * @return
     */
    Result signCount();
}
