package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.constants.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;


    @Override
    public Result isFollowById(Long followUserId, Boolean isFollow) {
        //1.获取当前用户id
        if (UserHolder.getUser() == null){
            return Result.fail("未登录");
        }
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //2.isFollow 为 true 则关注，添加数据库
        if (isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean success = this.save(follow);
            if (success){
                //点击关注后，将关注的用户放在redis中
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }
        else {
            //3. 否则 取关，删除数据库
            QueryWrapper<Follow> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id",userId)
                    .eq("follow_user_id",followUserId);
            boolean success = this.remove(queryWrapper);
            if (success){
                //取消关注后，将关注的用户从redis中移除
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //1.获取当前用户的id
        if (UserHolder.getUser() == null){
            return Result.fail("未登录");
        }
        Long userId = UserHolder.getUser().getId();
        //2.根据当前用户id和blog作者id查follow数据库
        QueryWrapper<Follow> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id",userId)
                .eq("follow_user_id",followUserId);
        int count = this.count(queryWrapper);
        return Result.ok(count > 0);
    }

    @Override
    public Result queryCommonUsers(Long followUserId) {
        //1.获取当前用户id
        UserDTO user = UserHolder.getUser();
        if (user == null){
            return Result.fail("未登录");
        }
        Long userId = UserHolder.getUser().getId();
        String userKey = "follows:" + userId;
        String followUserKey = "follows:" + followUserId;
        //2.从redis中判断两个id所关注的用户set集合的交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(userKey, followUserKey);
        if (intersect == null || intersect.isEmpty()){
            //无交集
            return Result.ok(new ArrayList<>());
        }
        List<Long> ids = intersect.stream().map(id -> Long.valueOf(id)).collect(Collectors.toList());
        //3.根据id查询数据库
        List<UserDTO> commonUsers = userService.listByIds(ids)
                .stream()
                .map(user1 -> BeanUtil.copyProperties(user1, UserDTO.class))
                .collect(Collectors.toList());
        //4.返回数据
        return Result.ok(commonUsers);
    }

    @Override
    public Result queryCommonUsersByDB(Long followUserId) {
        //1.获取当前用户id
        UserDTO user = UserHolder.getUser();
        if (user == null){
            return Result.fail("未登录");
        }
        Long userId = UserHolder.getUser().getId();
        //1. 从follow表中查询本人的关注列表和某用户的关注列表
        QueryWrapper<Follow> queryWrapper = null;
        queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id",userId);
        List<Follow> userFollowList = this.list(queryWrapper);
        Set<Long> userFollowHashSet = new HashSet<>();
        if (userFollowList == null || userFollowList.isEmpty()){
            return Result.ok(new ArrayList<>());
        }
        userFollowList.stream()
                .forEach(follow -> userFollowHashSet.add(follow.getFollowUserId())
                );
        //2. 把自己的关注列表放到hashSet中，然后遍历某用户的关注列表，看有多少存在。
        queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id",followUserId);
        List<Follow> blogUserFollowList = this.list(queryWrapper);
        if (blogUserFollowList == null || blogUserFollowList.isEmpty()){
            return Result.ok(new ArrayList<>());
        }
        List<Follow> commonFollowsList = blogUserFollowList.stream()
                .filter(follow -> userFollowHashSet.contains(follow.getFollowUserId()))
                .collect(Collectors.toList());
        //4.返回数据
        List<Long> ids = commonFollowsList
                .stream()
                .map(Follow::getFollowUserId).collect(Collectors.toList());
        //5.根据id查询数据库
        List<UserDTO> commonUsers = userService.listByIds(ids)
                .stream()
                .map(user1 -> BeanUtil.copyProperties(user1, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(commonUsers);
    }
}
