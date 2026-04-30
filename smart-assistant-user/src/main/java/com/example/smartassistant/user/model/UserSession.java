package com.example.smartassistant.user.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户会话实体（存储在数据库中，用于审计和追踪）
 * Redis 中存储活跃会话，数据库中长期存储历史记录 (MyBatis Plus)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "user_sessions", autoResultMap = true)
public class UserSession {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    @TableField("user_id")
    private Long userId;
    
    @TableField("token_id")
    private String tokenId;  // JWT Token ID (jti)
    
    @TableField(value = "device_info", typeHandler = JacksonTypeHandler.class)
    private Map<String, String> deviceInfo;  // 设备信息 JSON
    
    @TableField("ip_address")
    private String ipAddress;
    
    @TableField("user_agent")
    private String userAgent;
    
    @TableField("is_active")
    private Boolean isActive = true;
    
    @TableField("is_revoked")
    private Boolean isRevoked = false;
    
    @TableField("created_at")
    private LocalDateTime createdAt;
    
    @TableField("last_active_at")
    private LocalDateTime lastActiveAt;
    
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    
    @TableField("revoked_at")
    private LocalDateTime revokedAt;
}
