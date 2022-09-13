package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * 封装相关threadlocal方法，便于对threadlocal进行存和取的操作
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
