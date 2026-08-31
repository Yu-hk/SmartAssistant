package com.example.smartassistant.common.order;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStatusTest {

    @Test
    void resolvesStorageValuesAliasesAndEnumNames() {
        assertEquals(OrderStatus.PENDING_PAYMENT, OrderStatus.from("待付款").orElseThrow());
        assertEquals(OrderStatus.PENDING_PAYMENT, OrderStatus.from("待支付").orElseThrow());
        assertEquals(OrderStatus.DELIVERED, OrderStatus.from("DELIVERED").orElseThrow());
    }

    @Test
    void ownsLifecycleTransitionsInOnePlace() {
        assertTrue(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.PENDING_SHIPMENT));
        assertTrue(OrderStatus.PENDING_SHIPMENT.canTransitionTo(OrderStatus.CANCELLED));
        assertTrue(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.DELIVERED));
        assertFalse(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.CANCELLED));
    }
}
