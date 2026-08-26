package com.example.smartassistant.service.quality;

import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.common.rag.RetrievalQualityResult;
import com.example.smartassistant.common.rag.eval.FaithfulnessGuard;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.OrderDTO;
import com.example.smartassistant.service.core.OrderIntentService.IntentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderDomainQualityValidatorTest {

    private OrderDataProvider orderData;
    private OrderDomainQualityValidator validator;

    @BeforeEach
    void setUp() {
        orderData = mock(OrderDataProvider.class);
        validator = new OrderDomainQualityValidator(orderData);
    }

    @Test
    void passesOrderFactsThatMatchRetrievedEvidence() {
        RetrievalQualityResult retrieval = RetrievalQualityResult.highQuality(
                "订单 ORD-1001 当前状态为已发货。", 0.91);

        DomainQualityResult result = validator.evaluate(
                "查询 ORD-1001 的状态", "订单 ORD-1001 已发货。", IntentType.QUERY_ORDER,
                "42", retrieval, checked(false, 0.0));

        assertTrue(result.isPass());
    }

    @Test
    void failsWhenAnswerChangesOrderStatus() {
        RetrievalQualityResult retrieval = RetrievalQualityResult.highQuality(
                "订单 ORD-1001 当前状态为待发货。", 0.91);

        DomainQualityResult result = validator.evaluate(
                "查询 ORD-1001 的状态", "订单 ORD-1001 已发货。", IntentType.QUERY_ORDER,
                "42", retrieval, checked(false, 0.0));

        assertTrue(result.isFail());
        assertTrue(result.getReasonCodes().contains("ORDER_STATUS_MISMATCH"));
    }

    @Test
    void failsWhenAnswerMixesSupportedAndUnsupportedStatuses() {
        RetrievalQualityResult retrieval = RetrievalQualityResult.highQuality(
                "订单 ORD-1001 当前状态为已支付。", 0.91);

        DomainQualityResult result = validator.evaluate(
                "查询 ORD-1001 的状态", "订单 ORD-1001 已支付并且已发货。", IntentType.QUERY_ORDER,
                "42", retrieval, checked(false, 0.0));

        assertTrue(result.isFail());
        assertTrue(result.getReasonCodes().contains("ORDER_STATUS_MISMATCH"));
    }

    @Test
    void failsWhenAnswerIntroducesAnotherOrderId() {
        RetrievalQualityResult retrieval = RetrievalQualityResult.highQuality(
                "订单 ORD-1001 当前状态为已发货。", 0.91);

        DomainQualityResult result = validator.evaluate(
                "查询 ORD-1001 的状态", "订单 ORD-9999 已发货。", IntentType.QUERY_ORDER,
                "42", retrieval, checked(false, 0.0));

        assertTrue(result.isFail());
        assertTrue(result.getReasonCodes().contains("ANSWER_ORDER_ID_MISMATCH"));
    }

    @Test
    void warnsWhenIdentityIsMissing() {
        RetrievalQualityResult retrieval = RetrievalQualityResult.highQuality(
                "订单 ORD-1001 当前状态为已发货。", 0.91);

        DomainQualityResult result = validator.evaluate(
                "查询 ORD-1001 的状态", "订单 ORD-1001 已发货。", IntentType.QUERY_ORDER,
                null, retrieval, checked(false, 0.0));

        assertTrue(result.isWarn());
        assertTrue(result.getReasonCodes().contains("ORDER_IDENTITY_NOT_CONFIRMED"));
    }

    @Test
    void acceptsStructuredNoEvidenceRefusal() {
        RetrievalQualityResult retrieval = RetrievalQualityResult.noData("ORD-1001");

        DomainQualityResult result = validator.evaluate(
                "查询 ORD-1001 的状态", retrieval.getRejectionMessage(), IntentType.QUERY_ORDER,
                "42", retrieval, null);

        assertTrue(result.isPass());
        assertTrue(result.getReasonCodes().contains("SAFE_NO_EVIDENCE_RESPONSE"));
    }

    @Test
    void actionSuccessClaimRequiresToolEvidence() {
        RetrievalQualityResult retrieval = RetrievalQualityResult.highQuality(
                "订单 ORD-1001 当前状态为已支付。", 0.91);

        DomainQualityResult result = validator.evaluate(
                "取消 ORD-1001", "订单 ORD-1001 已成功取消。", IntentType.CANCEL,
                "42", retrieval, checked(false, 0.0));

        assertTrue(result.isWarn());
        assertTrue(result.getReasonCodes().contains("ACTION_RESULT_REQUIRES_TOOL_EVIDENCE"));
    }

    @Test
    void genericFaithfulnessRiskDoesNotSuppressVerifiedOrderFacts() {
        RetrievalQualityResult retrieval = RetrievalQualityResult.highQuality(
                "订单 ORD-1001 当前状态为已发货。", 0.91);

        DomainQualityResult result = validator.evaluate(
                "查询 ORD-1001 的状态", "订单 ORD-1001 已发货。", IntentType.QUERY_ORDER,
                "42", retrieval, checked(true, 0.7));

        assertTrue(result.isWarn());
        assertTrue(result.getReasonCodes().contains("UNSUPPORTED_ORDER_FACTS"));
    }

    @Test
    void acceptsNewlyCreatedOrderIdAfterOwnershipVerification() {
        when(orderData.findOrderByOrderId("ORD-2001")).thenReturn(OrderDTO.builder()
                .orderId("ORD-2001")
                .userId(42L)
                .status("待付款")
                .build());
        RetrievalQualityResult retrieval = RetrievalQualityResult.highQuality("", 0.8);

        DomainQualityResult result = validator.evaluate(
                "我要下单一台电脑", "订单 ORD-2001 创建成功，状态：待付款", IntentType.CREATE_ORDER,
                "42", retrieval, checked(false, 0.0));

        assertTrue(result.isPass());
        assertTrue(result.getReasonCodes().contains("ORDER_FACTS_VERIFIED"));
    }

    @Test
    void rejectsCreatedOrderIdOwnedByAnotherUser() {
        when(orderData.findOrderByOrderId("ORD-2001")).thenReturn(OrderDTO.builder()
                .orderId("ORD-2001")
                .userId(99L)
                .status("待付款")
                .build());
        RetrievalQualityResult retrieval = RetrievalQualityResult.highQuality("", 0.8);

        DomainQualityResult result = validator.evaluate(
                "我要下单一台电脑", "订单 ORD-2001 创建成功，状态：待付款", IntentType.CREATE_ORDER,
                "42", retrieval, checked(false, 0.0));

        assertTrue(result.isFail());
        assertTrue(result.getReasonCodes().contains("ANSWER_ORDER_ID_MISMATCH"));
    }

    @Test
    void rejectsCreatedOrderStatusThatDiffersFromPersistedOrder() {
        when(orderData.findOrderByOrderId("ORD-2001")).thenReturn(OrderDTO.builder()
                .orderId("ORD-2001")
                .userId(42L)
                .status("待付款")
                .build());
        RetrievalQualityResult retrieval = RetrievalQualityResult.highQuality("", 0.8);

        DomainQualityResult result = validator.evaluate(
                "我要下单一台电脑", "订单 ORD-2001 创建成功，状态：已付款", IntentType.CREATE_ORDER,
                "42", retrieval, checked(false, 0.0));

        assertTrue(result.isFail());
        assertTrue(result.getReasonCodes().contains("ORDER_STATUS_MISMATCH"));
    }

    @Test
    void acceptsEquivalentBusinessStatusLabels() {
        RetrievalQualityResult payment = RetrievalQualityResult.highQuality(
                "订单 ORD-1001 当前状态为待付款。", 0.91);
        RetrievalQualityResult delivered = RetrievalQualityResult.highQuality(
                "订单 ORD-1002 当前状态为已签收。", 0.91);

        assertTrue(validator.evaluate(
                "查询 ORD-1001 的状态", "订单 ORD-1001 当前待支付。", IntentType.QUERY_ORDER,
                "42", payment, checked(false, 0.0)).isPass());
        assertTrue(validator.evaluate(
                "查询 ORD-1002 的状态", "订单 ORD-1002 已完成。", IntentType.QUERY_ORDER,
                "42", delivered, checked(false, 0.0)).isPass());
    }

    private static FaithfulnessGuard.FaithfulnessVerdict checked(boolean hallucination, double score) {
        return new FaithfulnessGuard.FaithfulnessVerdict(true, hallucination, score, List.of(), null);
    }
}
