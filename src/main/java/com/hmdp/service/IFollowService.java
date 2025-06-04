package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;

import com.baomidou.mybatisplus.extension.service.IService;

/**
* @author cuiyq
* @description 针对表【tb_follow】的数据库操作Service
* @createDate 2025-05-28 10:47:25
*/
public interface IFollowService extends IService<Follow> {

    /**
     * 关注取关
     * @param followUserId
     * @param isFollow
     * @return
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断是否关注
     * @param followUserId
     * @return
     */
    Result isFollow(Long followUserId);

    /**
     * 共同关注
     * @param id
     * @return
     */
    Result followCommons(Long id);
}
