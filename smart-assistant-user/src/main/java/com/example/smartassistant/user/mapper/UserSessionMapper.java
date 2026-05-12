/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.user.model.UserSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户会话 Mapper (MyBatis Plus)
 */
@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {
    
    /**
     * 根据 Token ID 查找会话
     */
    UserSession findByTokenId(@Param("tokenId") String tokenId);
    
    /**
     * 查找用户的所有活跃会话
     */
    List<UserSession> findByUserIdAndIsActiveTrue(@Param("userId") Long userId);
    
    /**
     * 查找用户的所有会话（按时间降序）
     */
    List<UserSession> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);
    
    /**
     * 检查 Token 是否有效
     */
    boolean existsByTokenIdAndIsActiveTrueAndIsRevokedFalse(@Param("tokenId") String tokenId);
}
