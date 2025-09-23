package com.hmdp.dto;

import lombok.Data;
/*返回部分信息响应给前端*/
@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
}
