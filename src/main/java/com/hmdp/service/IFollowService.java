package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 判断当前用户是否关注了blog作者
     * @param followUserId
     * @param isFollow
     * @return
     */
    Result isFollowById(Long followUserId, Boolean isFollow);

    /**
     * 获取当前用户关注当前blog作者的状态（关注/未关注）
     * @param followUserId
     * @return
     */
    Result isFollow(Long followUserId);

    /**
     * 获取当前用户关注和blog作者的共同关注好友
     * @param followUserId
     * @return
     */
    Result queryCommonUsers(Long followUserId);

}
