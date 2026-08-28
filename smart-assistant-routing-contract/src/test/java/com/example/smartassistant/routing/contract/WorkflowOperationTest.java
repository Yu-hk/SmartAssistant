package com.example.smartassistant.routing.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowOperationTest {

    @Test
    void exposesCanonicalAccessAndApprovalMetadata() {
        assertTrue(WorkflowOperation.CREATE_ORDER.isWrite());
        assertFalse(WorkflowOperation.CREATE_ORDER.approvalRequired());
        assertFalse(WorkflowOperation.QUERY_ORDER_LIST.isWrite());
        assertTrue(WorkflowOperation.fromCode("recommend_product").isPresent());
    }
}
