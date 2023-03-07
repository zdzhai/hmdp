package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 保存用户创建的blog
     * @param blog
     */
    void saveBlog(Blog blog);

    /**
     * 根据blogId来查询blog
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 查询最热blog
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);

    /**
     * 点赞blog
     * @param id
     * 给blog点赞
     */
    void likeBlog(Long id);

    /**
     * 返回给blog点赞的用户信息（头像）
     * @param id
     * @return List
     */
    List<UserDTO> likesBlog(Long id);
}
