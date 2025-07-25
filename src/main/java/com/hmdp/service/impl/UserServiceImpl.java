package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author cuiyq
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 实现用户签到功能
     * @return
     */
    @Override
    public Result sign() {
        //1. 获得当前用户
        Long id = UserHolder.getUser().getId();

        //2. 获取今天日期
        String format = LocalDateTime.now().format(DateTimeFormatter.ofPattern(":yyyyMM"));
        LocalDateTime now = LocalDateTime.now();
        //3.  进行拼接
        String key = KEY_PRE_FIX + USER_SIGN_KEY + id + format;
        //获取今天是本月的第几天 1-31
        int dayOfMonth = now.getDayOfMonth();
        //4. 保存到redis中

        Boolean b = stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        //5. 返回
        return Result.ok();
    }

    /**
     * 统计连续签到用户
     * @return
     */
    @Override
    public Result signCount() {
        //1. 获得当前用户
        Long id = UserHolder.getUser().getId();

        //2. 获取今天日期
        String format = LocalDateTime.now().format(DateTimeFormatter.ofPattern(":yyyyMM"));
        LocalDateTime now = LocalDateTime.now();
        //3.  进行拼接
        String key = KEY_PRE_FIX + USER_SIGN_KEY + id + format;
        //4.获取今天是本月的第几天 1-31
        int dayOfMonth = now.getDayOfMonth();
        //5.获得本月所有签到记录  BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        //6.循环
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }

        int count = 0;
        while (true) {
            //6.1 与一做运算，得到最后一个bit位
            if ((num & 1) == 0) {
                //6.2如果为0则未签到
                break;
            } else {
                //6.3如果为1则计数+1
                count ++;
            }
            //6.4把数字右移一位
            num >>>= 1;
        }
        //7返回
        return Result.ok(count);
    }

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4. 保存验证码到redis，并设置为五分钟
//        session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5. 发送验证码
        log.debug("发送短信验证码成功，验证码:{}", code);
        //返回ok
        return Result.ok();
    }


    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //2. 校验验证码--token
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            //3. 不一致，报错
            return Result.fail("验证码错误");
        }

        //4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //5. 判断用户是否存在
        if (user == null) {
            //6. 不存在，创建新用户
            user = createUserWithPhone(phone);
        }
        //7.保存用户信息到redis
//        生成token
        String token = UUID.randomUUID().toString();
        log.info("token:{}", token);
//        转换成dto
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

//        将lang转换为string
//        将user对象转换为HashMap存储
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((filedName, o) -> o.toString()));
        String tokenkey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenkey, stringObjectMap);

//        设置到期时间
        stringRedisTemplate.expire(tokenkey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }


    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        save(user);
        return user;
    }
}
