package com.example.smartassistant.router.mapper;

import com.example.smartassistant.router.model.RoutingHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 路由历史 Mapper 接口
 * SQL 定义在 resources/mapper/RoutingHistoryMapper.xml
 */
@Mapper
public interface RoutingHistoryMapper {
    
    /**
     * 插入路由历史记录
     */
    int insert(RoutingHistory history);
    
    /**
     * 根据 ID 查询
     */
    RoutingHistory findById(Long id);
    
    /**
     * 更新路由结果
     */
    int updateResult(@Param("id") Long id, 
                     @Param("success") Boolean success, 
                     @Param("responseTimeMs") Long responseTimeMs);
    
    /**
     * 更新用户反馈
     */
    int updateFeedback(@Param("id") Long id, @Param("feedback") String feedback);
    
    /**
     * 查询 Agent 成功率（最近 N 天）
     */
    Double getSuccessRate(@Param("agentName") String agentName, @Param("since") LocalDateTime since);
    
    /**
     * 查询 Agent 平均响应时间（最近 N 天，仅成功记录）
     */
    Double getAvgResponseTime(@Param("agentName") String agentName, @Param("since") LocalDateTime since);
    
    /**
     * 查询最近 N 条记录
     */
    List<RoutingHistory> findRecent(@Param("limit") int limit);
    
    /**
     * 统计各 Agent 的路由次数（最近 N 天）
     */
    List<Map<String, Object>> countByAgent(@Param("since") LocalDateTime since);
    
    /**
     * 查询总记录数
     */
    long totalCount();
}
