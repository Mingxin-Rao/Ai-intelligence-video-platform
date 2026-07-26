package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    // Login email (unique — replaces the former username field)
    private String email;

    private String password;

    private String nickname;

    private String avatar;

    private String role;

    // Completely removed createTime to prevent MyBatis from erroring when it cannot find the configuration
    // The database will automatically fill in the current time, no worries
}