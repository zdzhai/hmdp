package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 关注/取关
     * @param followUserId
     * @param isFollow
     * @return
     */
    @PutMapping("/{id}/{isFollow}")
    public Result isFollowById(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow){
        //1.校验参数
        if (followUserId < 0){
            return Result.fail("用户不存在");
        }
        //2.处理业务
        return followService.isFollowById(followUserId, isFollow);
    }

    /**
     * 获取当前用户关注当前blog作者的状态（关注/未关注）
     * @param followUserId
     * @return
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId){
        return followService.isFollow(followUserId);
    }

    /**
     * 查询共同关注的好友
     * @param followUserId
     * @return
     */
    @GetMapping("/common/{id}")
    public Result queryCommonUsers(@PathVariable("id") Long followUserId){
        return followService.queryCommonUsers(followUserId);
    }


}
