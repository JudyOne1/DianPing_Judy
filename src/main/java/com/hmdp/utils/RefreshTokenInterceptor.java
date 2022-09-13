package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 先拦截所有=>进行token刷新
 * 放行到loginInterceptor再进行登录拦截
 * @author:Judy
 * @date:2022/9/4 9:25
 * ClassName:LoginInterceptor
 * package:com.hmdp.utils
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //token=>redis=>map=>userDto
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            //放行给第二层拦截器
            return true;
        }
        String key = LOGIN_USER_KEY+token;
        //通过token查询用户信息
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        if (userMap.isEmpty()){
            //放行给第二层拦截器
            return true;
        }
        //转存用户信息至threadlocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        //刷新token有效期    一旦用户操作就会进入拦截器=>刷新有效期，不操作=>过期
        stringRedisTemplate.expire(key,LOGIN_USER_TTL, TimeUnit.SECONDS);
        //放行给第二层拦截器
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
