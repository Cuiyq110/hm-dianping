package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

//        从线程中取 用户信息 判断是否需要拦截（ThreadLocal中是否有用户）
        UserDTO user = UserHolder.getUser();

        if (user == null) {
            //7.不存在，拦截
            response.setStatus(401);
            return false;
        }
        //6. 放行
        return true;
    }

}
