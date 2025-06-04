package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;

import com.hmdp.entity.User;
import com.hmdp.service.IFollowService;
import com.hmdp.mapper.TbFollowMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.KEY_PRE_FIX;

/**
* @author cuiyq
* @description 针对表【tb_follow】的数据库操作Service实现
* @createDate 2025-05-28 10:47:25
*/
@Slf4j
@Service
public class IFollowServiceImpl extends ServiceImpl<TbFollowMapper, Follow>
    implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;
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
        String key = KEY_PRE_FIX + "follows:" + id;
        if (isFollow) {
//        3.如果关注添加数据
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(followUserId);
            save(follow);
//         4.往redis存入数据
// 把关注用户的id，放入redis的set集合 sadd userId followerUserId
            Long add = stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        }
//        2.如果取关删除数据
        if (!isFollow) {
            remove(new QueryWrapper<Follow>().eq("follow_user_id",followUserId));
            // 把关注用户的id从Redis集合中移除
            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
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

    /**
     * 共同关注
     * @param id
     * @return
     */
    @Override
    public Result followCommons(Long id) {
//        1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = KEY_PRE_FIX + "follows:" + userId;
//        2.求交集
        String key2 =  KEY_PRE_FIX + "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key,key2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok();
        }
//        3.解析id
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
 //        4.查询用户 转换成dto
        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}




