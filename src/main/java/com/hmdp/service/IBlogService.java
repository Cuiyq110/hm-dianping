package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author cuiyq
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {


    /**
     * 查询笔记
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);


    Result queryBlogById(Long id);

    /**
     * 点赞功能
     * @param id
     * @return
     */
    Result likeBlog(Long id);
}
