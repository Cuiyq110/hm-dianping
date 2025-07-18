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
 * @author cuiyq
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {



    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sendCode(String phone, HttpSession session);

    /**
     * 实现用户签到功能
     * @return
     */
    Result sign();

    /**
     *  统计连续签到用户
     * @return
     */
    Result signCount();
}
