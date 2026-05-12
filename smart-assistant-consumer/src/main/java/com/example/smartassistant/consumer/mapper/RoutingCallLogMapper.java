/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.consumer.entity.RoutingCallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 路由调用日志 Mapper (MyBatis Plus)
 */
@Mapper
public interface RoutingCallLogMapper extends BaseMapper<RoutingCallLog> {
    
    /**
     * 查询最近 50 条日志
     */
    List<RoutingCallLog> findTop50ByOrderByCreatedAtDesc();
    
    /**
     * 按会话 ID 查询
     */
    List<RoutingCallLog> findBySessionIdOrderByCreatedAtDesc(@Param("sessionId") String sessionId);
    
    /**
     * 按时间范围查询
     */
    List<RoutingCallLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
        @Param("start") LocalDateTime start, 
        @Param("end") LocalDateTime end
    );
    
    /**
     * 统计某 Agent 的调用次数
     */
    long countByRoutedAgent(@Param("agentName") String agentName);
    
    /**
     * 统计失败次数
     */
    long countByStatus(@Param("status") String status);
}
