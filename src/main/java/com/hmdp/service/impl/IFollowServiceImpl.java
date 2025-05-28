package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;

import com.hmdp.service.IFollowService;
import com.hmdp.mapper.TbFollowMapper;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
* @author cuiyq
* @description 针对表【tb_follow】的数据库操作Service实现
* @createDate 2025-05-28 10:47:25
*/
@Service
public class IFollowServiceImpl extends ServiceImpl<TbFollowMapper, Follow>
    implements IFollowService {


    /**
     * 关注取关
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取登录用户id
        Long id = UserHolder.getUser().getId();
        if (isFollow) {
//        3.如果关注添加数据
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(followUserId);
            save(follow);
        }
//        2.如果取关删除数据
        if (!isFollow) {
            remove(new QueryWrapper<Follow>().eq("follow_user_id",followUserId));
        }
        return Result.ok();
    }

    /**
     * 判断是否关注
     * @param followUserId
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        //1。获取登录用户
        Long id = UserHolder.getUser().getId();
        //2.查询是否关注
        QueryWrapper<Follow> followQueryWrapper = new QueryWrapper<>();
        followQueryWrapper.eq("user_id", id).eq("follow_user_id", followUserId);
        int count = count(followQueryWrapper);
        return Result.ok(count > 0);
    }
}




