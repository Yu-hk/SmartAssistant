package com.example.smartassistant.consumer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.smartassistant.consumer.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天消息 Mapper (主模块 - MyBatis Plus)
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
    
    /**
     * 根据会话ID查询消息（按时间升序）
     */
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(@Param("sessionId") String sessionId);

    /**
     * 查询指定 ID 之后的消息（按时间升序，用于增量压缩）
     * @param sessionId 会话ID
     * @param lastId 上次压缩截至的消息ID
     */
    List<ChatMessage> findBySessionIdAndIdAfterOrderByCreatedAtAsc(
            @Param("sessionId") String sessionId,
            @Param("lastId") Long lastId
    );
    
    /**
     * 根据会话ID查询最近50条消息（按时间降序）
     */
    List<ChatMessage> findTop50BySessionIdOrderByCreatedAtDesc(@Param("sessionId") String sessionId);
    
    /**
     * 删除会话的所有消息
     */
    void deleteBySessionId(@Param("sessionId") String sessionId);
    
    /**
     * 统计会话的消息数量
     */
    long countBySessionId(@Param("sessionId") String sessionId);
    
    /**
     * 搜索消息内容（模糊查询）
     */
    List<ChatMessage> searchByKeyword(
        @Param("sessionId") String sessionId, 
        @Param("keyword") String keyword
    );
    
    /**
     * 根据 Agent 过滤消息
     */
    List<ChatMessage> findBySessionIdAndTargetAgentOrderByCreatedAtDesc(
        @Param("sessionId") String sessionId, 
        @Param("targetAgent") String targetAgent
    );
    
    /**
     * 只查询用户消息
     */
    List<ChatMessage> findBySessionIdAndIsUserTrueOrderByCreatedAtDesc(@Param("sessionId") String sessionId);
    
    /**
     * 只查询 AI 回复
     */
    List<ChatMessage> findBySessionIdAndIsUserFalseOrderByCreatedAtDesc(@Param("sessionId") String sessionId);
    
    /**
     * 按时间范围查询
     */
    List<ChatMessage> findBySessionIdAndCreatedAtBetweenOrderByCreatedAtAsc(
        @Param("sessionId") String sessionId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime
    );
    
    /**
     * 分页查询消息历史（按时间降序）
     */
    IPage<ChatMessage> findBySessionIdOrderByCreatedAtDesc(
        Page<ChatMessage> page,
        @Param("sessionId") String sessionId
    );
}
