package com.example.smartassistant.controller;

import com.example.smartassistant.service.core.OrderCheckoutHistoryService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Customer-only history read. The gateway replaces all identity headers from the JWT. */
@RestController
@RequestMapping("/api/order/checkout-history")
public class OrderCheckoutHistoryController {
    private final OrderCheckoutHistoryService history;

    public OrderCheckoutHistoryController(OrderCheckoutHistoryService history) {
        this.history = history;
    }

    @GetMapping
    public ResponseEntity<OrderCheckoutHistoryService.Suggestions> suggestions(
            @RequestHeader(value = "X-User-Id", required = false) Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(history.suggestions(ownerId));
    }
}

