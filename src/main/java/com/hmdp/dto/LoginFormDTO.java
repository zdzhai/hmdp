package com.hmdp.dto;

import lombok.Data;

/**
 * @author dongdong
 * @date 2023/2/26
 */
@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
}
