package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.constants.SystemConstants;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.constants.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.constants.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author dongdong
 * @since 2023-03-06
 */
@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private UserServiceImpl userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result saveBlog(Blog blog) {
        //1.获取当前登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2.保存探店博文
        boolean success = this.save(blog);
        if (!success){
            return Result.fail("blog发布失败！");
        }
        //3. 查询笔记作者的所有粉丝 select * from tb_blog where follow_id = ?
        Long blogId = blog.getId();
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        if (follows == null || follows.isEmpty()){
            return Result.ok(blogId);
        }
        //4. 推送笔记id到粉丝的收件箱
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            //收件箱key
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blogId.toString(),
                    System.currentTimeMillis());
        }
        //5.返回blogId
        return Result.ok(blogId);
    }

    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = this.getById(id);
        //2.校验
        if (blog == null){
            return Result.fail("博客不存在");
        }
        //3.根据userId查询用户的部分信息
        queryBlogUser(blog);
        //4.查询当前用户是否点赞
        queryUserIsLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            queryUserIsLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public void likeBlog(Long id) {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //1.判断当前用户是否已点赞
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, userId.toString());
        //2.未点赞
        if (score == null){
            //2.1 点赞
            boolean isSuccess = this.update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess){
                //2.2 向redis的Zset中添加用户id
                stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + id, userId.toString(),
                        System.currentTimeMillis());
            }
        }
        //3.已点赞，取消赞
        else {
            boolean isSuccess = this.update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess){
                //3.2 从redis的set中删除用户id
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + id, userId.toString());
            }
        }
    }

    @Override
    public List<UserDTO> likesBlog(Long id) {
        //1.根据blogId查询redis中的top5数据
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return new ArrayList<>();
        }
        //2.根据得到的集合去查询用户表中用户的部分信息
        List<Long> userIdList = top5.stream()
                .map(userId -> Long.valueOf(userId))
                .collect(Collectors.toList());
        //得到userIdList组成的id字符串
        String idsStr = StrUtil.join(",", userIdList);
        log.info(idsStr);
        //查询数据库//where id in(5,1)ORDER BY FIELD(id,5,1) 这样可以按照redis中的top5顺序查出
        List<UserDTO> userDTOList = userService.query()
                .in("id", idsStr).last("ORDER BY FIELD(id," + idsStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //3.返回列表
        return userDTOList;
    }

    /**
     * 查询blog发布用户的头像，昵称
     * @param blog
     */
    public void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }

    /**
     * 查询当前用户有没有对blog点赞
     * @param blog blogId
     */
    public void queryUserIsLiked(Blog blog){
        //1.获得当前用户Id
        if (UserHolder.getUser() == null){
            return;
        }
        Long userId = UserHolder.getUser().getId();
        Long blogId = blog.getId();
        //2.从redis中判断当前用户是否点赞并给isLiked字段赋值
        Double score = stringRedisTemplate.opsForZSet()
                .score(BLOG_LIKED_KEY + blogId, userId.toString());
        blog.setIsLike(score != null);
    }


    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.根据max 和 offset获得当前用户收件箱的blogId的集合
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //min 最小score值，max最大score值，offset 从max往下的偏移量，count 获取几条
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //3.解析数据
        List<Long> ids = new ArrayList<>(typedTuples.size());
        //最小时间戳和偏移量
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //获取id
            ids.add(Long.valueOf(typedTuple.getValue()));
            //获取分数(时间戳)
            long time = typedTuple.getScore().longValue();
            if (time == minTime){
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
//        os = minTime == max ? os : os + offset;
        //4.根据id查询数据库
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            queryUserIsLiked(blog);
        }
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        //6.返回
        return Result.ok(scrollResult);
    }
}
