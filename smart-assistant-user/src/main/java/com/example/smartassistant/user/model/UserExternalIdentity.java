package com.example.smartassistant.user.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_external_identities")
public class UserExternalIdentity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String provider;
    private String subject;
    private String unionId;
    private String displayName;
    private String avatarUrl;
    private String email;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
