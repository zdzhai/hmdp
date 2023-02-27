package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * @author dongdong
 * 判断ThreadLocal中有无用户信息，因为ThreadLocal是线程共享的变量
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
