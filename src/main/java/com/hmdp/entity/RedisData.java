package com.hmdp.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author dongdong
 * 逻辑过期结果封装类
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
