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
 *
 * <p>P0 更新：confirmById 支持传入 operator 审计字段；
 * 所有 UPDATE SQL 保留 WHERE status= 子句作为 DB 级原子状态机防护。</p>
 */
@Mapper
public interface ApprovalRecordMapper extends BaseMapper<ApprovalRecordEntity> {

    /**
     * 根据订单号和操作类型查询待确认的记录
     */
    @Select("SELECT * FROM approval_records WHERE order_id = #{orderId} AND action_type = #{actionType} AND status = 'PENDING' ORDER BY created_at DESC LIMIT 1")
    ApprovalRecordEntity findPending(@Param("orderId") String orderId, @Param("actionType") String actionType);

    /**
     * 根据订单号和操作类型查询已确认的记录
     */
    @Select("SELECT * FROM approval_records WHERE order_id = #{orderId} AND action_type = #{actionType} AND status = 'CONFIRMED' ORDER BY confirmed_at DESC LIMIT 1")
    ApprovalRecordEntity findConfirmed(@Param("orderId") String orderId, @Param("actionType") String actionType);

    /**
     * 原子更新确认状态（带审计字段）。
     *
     * <p>WHERE status='PENDING' 保证只有待确认记录被更新（DB 级并发防护）。</p>
     *
     * @return 更新行数（0=并发冲突或状态已变化，1=成功）
     */
    @Update("UPDATE approval_records " +
            "SET status = 'CONFIRMED', " +
            "    confirmed_at = CURRENT_TIMESTAMP, " +
            "    operator = #{operator}, " +
            "    operator_ip = #{operatorIp} " +
            "WHERE id = #{id} AND status = 'PENDING'")
    int confirmById(@Param("id") Long id,
                    @Param("operator") String operator,
                    @Param("operatorIp") String operatorIp);

    /**
     * 原子更新消费状态。
     *
     * <p>WHERE status='CONFIRMED' 保证只有已确认记录可被消费（DB 级并发防护）。</p>
     *
     * @return 更新行数（0=并发冲突或状态已变化，1=成功）
     */
    @Update("UPDATE approval_records " +
            "SET status = 'CONSUMED', " +
            "    consumed_at = CURRENT_TIMESTAMP " +
            "WHERE id = #{id} AND status = 'CONFIRMED'")
    int consumeById(@Param("id") Long id);
}
