/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.entity.ApprovalRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 审批记录 Mapper (MyBatis Plus)
 */
@Mapper
public interface ApprovalRecordMapper extends BaseMapper<ApprovalRecordEntity> {

    /**
     * 根据订单号和操作类型查询待确认的记录
     */
    @Select("SELECT * FROM approval_records WHERE order_id = #{orderId} AND action_type = #{actionType} AND status = 'pending' ORDER BY created_at DESC LIMIT 1")
    ApprovalRecordEntity findPending(@Param("orderId") String orderId, @Param("actionType") String actionType);

    /**
     * 根据订单号和操作类型查询已确认的记录
     */
    @Select("SELECT * FROM approval_records WHERE order_id = #{orderId} AND action_type = #{actionType} AND status = 'confirmed' ORDER BY confirmed_at DESC LIMIT 1")
    ApprovalRecordEntity findConfirmed(@Param("orderId") String orderId, @Param("actionType") String actionType);

    /**
     * 更新确认状态
     */
    @Update("UPDATE approval_records SET status = 'confirmed', confirmed_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 'pending'")
    int confirmById(@Param("id") Long id);

    /**
     * 更新消费状态
     */
    @Update("UPDATE approval_records SET status = 'consumed', consumed_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 'confirmed'")
    int consumeById(@Param("id") Long id);
}
