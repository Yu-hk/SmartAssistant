/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service;

import com.example.smartassistant.entity.ApprovalRecordEntity;
import com.example.smartassistant.entity.ApprovalRecordEntity.ApprovalStatus;
import com.example.smartassistant.mapper.ApprovalRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ApprovalService}.
 * Covers the approval state machine: PENDING -> CONFIRMED -> CONSUMED, and PENDING -> CANCELLED.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApprovalService Unit Tests")
class ApprovalServiceTest {

    @Mock
    private ApprovalRecordMapper approvalRecordMapper;

    @Captor
    private ArgumentCaptor<ApprovalRecordEntity> entityCaptor;

    private ApprovalService approvalService;

    @BeforeEach
    void setUp() {
        approvalService = new ApprovalService(approvalRecordMapper);
    }

    /**
     * 模拟 MyBatis {@code useGeneratedKeys} 行为：insert 成功后回填自增主键到实体。
     * 若不回填，{@code ApprovalService.createApproval} 返回的 {@code record.getId()} 会因 mock 默认行为而为 null。
     */
    private void stubInsertWithGeneratedKey() {
        when(approvalRecordMapper.insert(any(ApprovalRecordEntity.class))).thenAnswer(invocation -> {
            ApprovalRecordEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(1L);
            }
            return 1;
        });
    }

    // ==================== createApproval ====================

    @Test
    @DisplayName("createApproval should create a new pending approval when no existing pending record")
    void should_createPendingApproval_when_noExistingPending() {
        String orderId = "ORD-123";
        String actionType = "refund";
        String reason = "商品质量问题";

        when(approvalRecordMapper.findPending(orderId, actionType)).thenReturn(null);
        stubInsertWithGeneratedKey();

        Long recordId = approvalService.createApproval(orderId, actionType, reason);

        assertNotNull(recordId, "Should return a non-null record ID");
        verify(approvalRecordMapper).insert(entityCaptor.capture());
        ApprovalRecordEntity captured = entityCaptor.getValue();
        assertEquals(orderId, captured.getOrderId(), "Record should have correct orderId");
        assertEquals(actionType, captured.getActionType(), "Record should have correct actionType");
        assertEquals(ApprovalStatus.PENDING.name(), captured.getStatus(), "Record should be PENDING");
    }

    @Test
    @DisplayName("createApproval should cancel existing pending record before creating a new one")
    void should_cancelExistingPending_when_creatingNew() {
        String orderId = "ORD-123";
        String actionType = "refund";
        String reason = "重复退款";

        ApprovalRecordEntity existing = ApprovalRecordEntity.createPending(orderId, actionType, "旧原因");
        existing.setId(1L);
        when(approvalRecordMapper.findPending(orderId, actionType)).thenReturn(existing);
        stubInsertWithGeneratedKey();

        Long result = approvalService.createApproval(orderId, actionType, reason);

        assertNotNull(result, "Should return a non-null record ID");
        // Verify existing record was cancelled
        assertEquals(ApprovalStatus.CANCELLED.name(), existing.getStatus(),
                "Existing record should be cancelled");
        verify(approvalRecordMapper).updateById(existing);
        // Verify new record was inserted
        verify(approvalRecordMapper, times(1)).insert(any(ApprovalRecordEntity.class));
    }

    // ==================== confirmAction (with audit fields) ====================

    @Test
    @DisplayName("confirmAction should transition PENDING to CONFIRMED successfully")
    void should_confirmPending_when_validRequest() {
        String orderId = "ORD-123";
        String actionType = "refund";
        String operator = "admin";
        String operatorIp = "10.0.0.1";

        ApprovalRecordEntity pending = ApprovalRecordEntity.createPending(orderId, actionType, "退款");
        pending.setId(1L);
        when(approvalRecordMapper.findPending(orderId, actionType)).thenReturn(pending);
        when(approvalRecordMapper.confirmById(1L, operator, operatorIp)).thenReturn(1);

        boolean result = approvalService.confirmAction(orderId, actionType, operator, operatorIp);

        assertTrue(result, "Confirm action should succeed");
        verify(approvalRecordMapper).confirmById(1L, operator, operatorIp);
    }

    @Test
    @DisplayName("confirmAction should return false when no pending record exists")
    void should_returnFalse_when_noPendingRecord() {
        String orderId = "ORD-123";
        String actionType = "refund";

        when(approvalRecordMapper.findPending(orderId, actionType)).thenReturn(null);

        boolean result = approvalService.confirmAction(orderId, actionType, "admin", "10.0.0.1");

        assertFalse(result, "Should return false when no pending record");
    }

    @Test
    @DisplayName("confirmAction should return false when entity is already in terminal state")
    void should_returnFalse_when_entityIsTerminal() {
        String orderId = "ORD-123";
        String actionType = "refund";

        // Create an entity already in CONSUMED state
        ApprovalRecordEntity consumed = ApprovalRecordEntity.createPending(orderId, actionType, "退款");
        consumed.setId(1L);
        consumed.setStatus(ApprovalStatus.CONSUMED.name());
        when(approvalRecordMapper.findPending(orderId, actionType)).thenReturn(consumed);

        boolean result = approvalService.confirmAction(orderId, actionType, "admin", "10.0.0.1");

        assertFalse(result, "Should return false for terminal state entity");
        verify(approvalRecordMapper, never()).confirmById(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("confirmAction should return false when DB update returns 0 (concurrent conflict)")
    void should_returnFalse_when_dbUpdateFails() {
        String orderId = "ORD-123";
        String actionType = "refund";

        ApprovalRecordEntity pending = ApprovalRecordEntity.createPending(orderId, actionType, "退款");
        pending.setId(1L);
        when(approvalRecordMapper.findPending(orderId, actionType)).thenReturn(pending);
        // DB update returns 0 - already confirmed by another transaction
        when(approvalRecordMapper.confirmById(1L, "admin", "10.0.0.1")).thenReturn(0);

        boolean result = approvalService.confirmAction(orderId, actionType, "admin", "10.0.0.1");

        assertFalse(result, "Should return false when DB update fails");
    }

    // ==================== confirmAction (no audit fields - compatibility) ====================

    @Test
    @DisplayName("confirmAction without audit fields should delegate to the full method with SYSTEM defaults")
    void should_confirmWithDefaultAuditFields_when_callingSimplifiedMethod() {
        String orderId = "ORD-123";
        String actionType = "payment";

        ApprovalRecordEntity pending = ApprovalRecordEntity.createPending(orderId, actionType, "支付");
        pending.setId(1L);
        when(approvalRecordMapper.findPending(orderId, actionType)).thenReturn(pending);
        when(approvalRecordMapper.confirmById(1L, "SYSTEM", "0.0.0.0")).thenReturn(1);

        boolean result = approvalService.confirmAction(orderId, actionType);

        assertTrue(result, "Should succeed with default audit fields");
        verify(approvalRecordMapper).confirmById(1L, "SYSTEM", "0.0.0.0");
    }

    // ==================== checkAndConsume ====================

    @Test
    @DisplayName("checkAndConsume should transition CONFIRMED to CONSUMED successfully")
    void should_consumeConfirmed_when_validRequest() {
        String orderId = "ORD-123";
        String actionType = "payment";

        ApprovalRecordEntity confirmed = ApprovalRecordEntity.createPending(orderId, actionType, "支付");
        confirmed.setId(1L);
        confirmed.setStatus(ApprovalStatus.CONFIRMED.name());
        when(approvalRecordMapper.findConfirmed(orderId, actionType)).thenReturn(confirmed);
        when(approvalRecordMapper.consumeById(1L)).thenReturn(1);

        boolean result = approvalService.checkAndConsume(orderId, actionType);

        assertTrue(result, "Consume should succeed");
        verify(approvalRecordMapper).consumeById(1L);
    }

    @Test
    @DisplayName("checkAndConsume should return false when no confirmed record exists")
    void should_returnFalse_when_noConfirmedRecord() {
        String orderId = "ORD-123";
        String actionType = "payment";

        when(approvalRecordMapper.findConfirmed(orderId, actionType)).thenReturn(null);

        boolean result = approvalService.checkAndConsume(orderId, actionType);

        assertFalse(result, "Should return false when no confirmed record");
        verify(approvalRecordMapper, never()).consumeById(anyLong());
    }

    @Test
    @DisplayName("checkAndConsume should return false when entity is not in CONFIRMED state")
    void should_returnFalse_when_notConfirmed() {
        String orderId = "ORD-123";
        String actionType = "payment";

        ApprovalRecordEntity pending = ApprovalRecordEntity.createPending(orderId, actionType, "支付");
        pending.setId(1L);
        // Entity is still PENDING, not CONFIRMED
        when(approvalRecordMapper.findConfirmed(orderId, actionType)).thenReturn(pending);

        boolean result = approvalService.checkAndConsume(orderId, actionType);

        assertFalse(result, "Should return false when entity is not CONFIRMED");
    }

    @Test
    @DisplayName("checkAndConsume should return false when DB consume returns 0 (already consumed)")
    void should_returnFalse_when_consumeAlreadyDone() {
        String orderId = "ORD-123";
        String actionType = "payment";

        ApprovalRecordEntity confirmed = ApprovalRecordEntity.createPending(orderId, actionType, "支付");
        confirmed.setId(1L);
        confirmed.setStatus(ApprovalStatus.CONFIRMED.name());
        when(approvalRecordMapper.findConfirmed(orderId, actionType)).thenReturn(confirmed);
        when(approvalRecordMapper.consumeById(1L)).thenReturn(0);

        boolean result = approvalService.checkAndConsume(orderId, actionType);

        assertFalse(result, "Should return false when DB consume fails");
    }

    // ==================== getPendingApproval ====================

    @Test
    @DisplayName("getPendingApproval should return pending approval info when found")
    void should_returnPendingInfo_when_pendingExists() {
        String orderId = "ORD-123";
        String actionType = "refund";

        ApprovalRecordEntity pending = ApprovalRecordEntity.createPending(orderId, actionType, "七天无理由退货");
        pending.setId(1L);
        when(approvalRecordMapper.findPending(orderId, actionType)).thenReturn(pending);

        ApprovalService.PendingApproval result = approvalService.getPendingApproval(orderId, actionType);

        assertNotNull(result, "Should return pending approval info");
        assertEquals(orderId, result.getOrderId(), "Should have correct orderId");
        assertEquals(actionType, result.getActionType(), "Should have correct actionType");
        assertFalse(result.isConfirmed(), "Pending should not be confirmed yet");
    }

    @Test
    @DisplayName("getPendingApproval should return null when no pending record")
    void should_returnNull_when_noPendingExists() {
        when(approvalRecordMapper.findPending("ORD-123", "refund")).thenReturn(null);

        ApprovalService.PendingApproval result = approvalService.getPendingApproval("ORD-123", "refund");

        assertNull(result, "Should return null when no pending record");
    }
}
