/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.spi.impl;

import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.CouponRecommendationDTO;
import com.example.smartassistant.common.tool.spi.dto.LogisticsDTO;
import com.example.smartassistant.common.tool.spi.dto.OrderDTO;
import com.example.smartassistant.common.tool.spi.dto.RefundDTO;
import com.example.smartassistant.common.tool.spi.dto.UserCouponDTO;
import com.example.smartassistant.entity.CouponEntity;
import com.example.smartassistant.entity.OrderEntity;
import com.example.smartassistant.entity.OrderLogisticsEntity;
import com.example.smartassistant.entity.OrderRefundEntity;
import com.example.smartassistant.mapper.CouponMapper;
import com.example.smartassistant.mapper.OrderLogisticsMapper;
import com.example.smartassistant.mapper.OrderMapper;
import com.example.smartassistant.mapper.OrderRefundMapper;
import com.example.smartassistant.service.ApprovalService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of {@link OrderDataProvider} in the order module.
 * <p>Maps between entity objects and SPI DTOs, delegating persistence to MyBatis Plus mappers
 * and business logic to domain services.</p>
 */
@Component
public class OrderDataProviderImpl implements OrderDataProvider {

    private final OrderMapper orderMapper;
    private final OrderRefundMapper orderRefundMapper;
    private final OrderLogisticsMapper orderLogisticsMapper;
    private final ApprovalService approvalService;
    private final CouponMapper couponMapper;
    private final JdbcTemplate jdbcTemplate;

    public OrderDataProviderImpl(OrderMapper orderMapper,
                                 OrderRefundMapper orderRefundMapper,
                                 OrderLogisticsMapper orderLogisticsMapper,
                                 ApprovalService approvalService,
                                 CouponMapper couponMapper,
                                 JdbcTemplate jdbcTemplate) {
        this.orderMapper = orderMapper;
        this.orderRefundMapper = orderRefundMapper;
        this.orderLogisticsMapper = orderLogisticsMapper;
        this.approvalService = approvalService;
        this.couponMapper = couponMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ========== Order CRUD ==========

    @Override
    public OrderDTO findOrderByOrderId(String orderId) {
        OrderEntity entity = orderMapper.findByOrderId(orderId);
        return entity == null ? null : toOrderDTO(entity);
    }

    @Override
    public void insertOrder(OrderDTO order) {
        OrderEntity entity = toOrderEntity(order);
        orderMapper.insert(entity);
        // Sync back the generated ID
        if (order != null) {
            order.setId(entity.getId());
        }
    }

    @Override
    public int updateOrderById(OrderDTO order) {
        if (order == null || order.getId() == null) {
            return 0;
        }
        OrderEntity entity = toOrderEntity(order);
        entity.setId(order.getId());
        return orderMapper.updateById(entity);
    }

    @Override
    public void updatePayment(String orderId, String status, String paymentMethod) {
        orderMapper.updatePayment(orderId, status, paymentMethod);
    }

    @Override
    public void updateStatusByOrderId(String orderId, String status) {
        orderMapper.updateStatusByOrderId(orderId, status);
    }

    // ========== Logistics ==========

    @Override
    public LogisticsDTO findLogisticsByTrackingNo(String trackingNo) {
        OrderLogisticsEntity entity = orderLogisticsMapper.findByTrackingNo(trackingNo);
        return entity == null ? null : toLogisticsDTO(entity);
    }

    @Override
    public LogisticsDTO findLogisticsByOrderId(String orderId) {
        OrderLogisticsEntity entity = orderLogisticsMapper.findByOrderId(orderId);
        return entity == null ? null : toLogisticsDTO(entity);
    }

    @Override
    public int insertLogistics(LogisticsDTO logistics) {
        OrderLogisticsEntity entity = toLogisticsEntity(logistics);
        return orderLogisticsMapper.insert(entity);
    }

    @Override
    public int updateLogisticsById(LogisticsDTO logistics) {
        if (logistics == null || logistics.getId() == null) {
            return 0;
        }
        OrderLogisticsEntity entity = toLogisticsEntity(logistics);
        entity.setId(logistics.getId());
        return orderLogisticsMapper.updateById(entity);
    }

    // ========== Refund ==========

    @Override
    public int insertRefund(RefundDTO refund) {
        OrderRefundEntity entity = toRefundEntity(refund);
        return orderRefundMapper.insert(entity);
    }

    // ========== Approval ==========

    @Override
    public void createApproval(String orderId, String actionType, String reason) {
        approvalService.createApproval(orderId, actionType, reason);
    }

    @Override
    public boolean confirmAction(String orderId, String actionType) {
        return approvalService.confirmAction(orderId, actionType);
    }

    @Override
    public boolean checkAndConsume(String orderId, String actionType) {
        return approvalService.checkAndConsume(orderId, actionType);
    }

    // ========== Analytics Queries ==========

    @Override
    public List<Map<String, Object>> queryOrdersByStatus(String status, int limit, int offset) {
        String sql = "SELECT * FROM orders WHERE status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.queryForList(sql, status, limit, offset);
    }

    @Override
    public List<Map<String, Object>> countOrdersByStatus() {
        String sql = "SELECT status, COUNT(*) AS count FROM orders GROUP BY status ORDER BY count DESC";
        return jdbcTemplate.queryForList(sql);
    }

    @Override
    public List<Map<String, Object>> queryTopRefunds(int limit, int offset) {
        String sql = "SELECT r.*, o.order_id, o.user_id, o.product_name " +
                     "FROM order_refunds r " +
                     "LEFT JOIN orders o ON r.order_id = o.order_id " +
                     "ORDER BY r.amount DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.queryForList(sql, limit, offset);
    }

    @Override
    public List<Map<String, Object>> queryUserRefunds(Long userId) {
        String sql = "SELECT r.*, o.product_name " +
                     "FROM order_refunds r " +
                     "LEFT JOIN orders o ON r.order_id = o.order_id " +
                     "WHERE o.user_id = ? " +
                     "ORDER BY r.created_at DESC";
        return jdbcTemplate.queryForList(sql, userId);
    }

    // ========== SQL Query (TextToSqlTool) ==========

    @Override
    public List<Map<String, Object>> executeSelectSql(String sql) {
        return jdbcTemplate.queryForList(sql);
    }

    // ========== Coupon ==========

    @Override
    public List<UserCouponDTO> getUserCoupons(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<CouponEntity> entities = couponMapper.findAvailableByUserId(userId);
        return entities.stream()
                .map(this::toUserCouponDTO)
                .collect(Collectors.toList());
    }

    @Override
    public CouponRecommendationDTO findBestCouponCombination(BigDecimal amount, List<UserCouponDTO> coupons) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0 || coupons == null || coupons.isEmpty()) {
            return null;
        }

        UserCouponDTO best = null;
        BigDecimal maxDiscount = BigDecimal.ZERO;

        for (UserCouponDTO coupon : coupons) {
            if (Boolean.TRUE.equals(coupon.getUsed()) || Boolean.TRUE.equals(coupon.getExpired())) {
                continue;
            }
            BigDecimal discount = calculateDiscount(coupon, amount);
            if (discount.compareTo(maxDiscount) > 0) {
                maxDiscount = discount;
                best = coupon;
            }
        }

        if (best == null) {
            return null;
        }

        BigDecimal finalAmount = amount.subtract(maxDiscount).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalDiscount = maxDiscount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal originalAmount = amount.setScale(2, RoundingMode.HALF_UP);

        List<UserCouponDTO> recommended = new ArrayList<>();
        recommended.add(best);

        return CouponRecommendationDTO.builder()
                .recommendedCoupons(recommended)
                .totalDiscount(totalDiscount)
                .originalAmount(originalAmount)
                .finalAmount(finalAmount)
                .build();
    }

    // ========== SQL Table Whitelist ==========

    @Override
    public List<String> getAllowedTables() {
        return List.of("orders", "order_refunds", "order_logistics", "user_coupons", "approval_records");
    }

    // ========== Mapping Methods ==========

    private OrderDTO toOrderDTO(OrderEntity entity) {
        if (entity == null) return null;
        return OrderDTO.builder()
                .id(entity.getId())
                .orderId(entity.getOrderId())
                .userId(entity.getUserId())
                .productName(entity.getProductName())
                .amount(entity.getAmount())
                .status(entity.getStatus())
                .paymentMethod(entity.getPaymentMethod())
                .carrier(entity.getCarrier())
                .trackingNo(entity.getTrackingNo())
                .productType(entity.getProductType())
                .deliveredDate(entity.getDeliveredDate())
                .contactName(entity.getContactName())
                .contactPhone(entity.getContactPhone())
                .shippingAddress(entity.getShippingAddress())
                .requestId(entity.getRequestId())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private OrderEntity toOrderEntity(OrderDTO dto) {
        if (dto == null) return null;
        return OrderEntity.builder()
                .id(dto.getId())
                .orderId(dto.getOrderId())
                .userId(dto.getUserId())
                .productName(dto.getProductName())
                .amount(dto.getAmount())
                .status(dto.getStatus())
                .paymentMethod(dto.getPaymentMethod())
                .carrier(dto.getCarrier())
                .trackingNo(dto.getTrackingNo())
                .productType(dto.getProductType())
                .deliveredDate(dto.getDeliveredDate())
                .contactName(dto.getContactName())
                .contactPhone(dto.getContactPhone())
                .shippingAddress(dto.getShippingAddress())
                .requestId(dto.getRequestId())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }

    private LogisticsDTO toLogisticsDTO(OrderLogisticsEntity entity) {
        if (entity == null) return null;
        return LogisticsDTO.builder()
                .id(entity.getId())
                .orderId(entity.getOrderId())
                .trackingNo(entity.getTrackingNo())
                .companyName(entity.getCarrier())
                .status(entity.getStatus())
                .logisticsDetail(entity.getTrajectoryText() != null
                        ? entity.getTrajectoryText()
                        : entity.getTrajectory())
                .logisticsTime(entity.getCreatedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private OrderLogisticsEntity toLogisticsEntity(LogisticsDTO dto) {
        if (dto == null) return null;
        return OrderLogisticsEntity.builder()
                .id(dto.getId())
                .orderId(dto.getOrderId())
                .trackingNo(dto.getTrackingNo())
                .carrier(dto.getCompanyName())
                .status(dto.getStatus())
                .trajectory(dto.getLogisticsDetail())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }

    private OrderRefundEntity toRefundEntity(RefundDTO dto) {
        if (dto == null) return null;
        return OrderRefundEntity.builder()
                .id(dto.getId())
                .orderId(dto.getOrderId())
                .amount(dto.getAmount())
                .reason(dto.getReason())
                .status(dto.getStatus())
                .createdAt(dto.getCreateTime())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }

    private UserCouponDTO toUserCouponDTO(CouponEntity entity) {
        if (entity == null) return null;
        boolean isExpired = entity.getExpireAt() != null && entity.getExpireAt().isBefore(LocalDateTime.now());
        return UserCouponDTO.builder()
                .couponId(entity.getCouponId())
                .userId(entity.getUserId())
                .type(entity.getCouponType())
                .typeName(getCouponTypeName(entity.getCouponType()))
                .discount(entity.getValue())
                .minAmount(entity.getConditionAmount())
                .expired(isExpired)
                .expireAt(entity.getExpireAt())
                .used(entity.getUsed())
                .build();
    }

    /**
     * Calculate the discount amount for a given coupon applied to an amount.
     */
    private BigDecimal calculateDiscount(UserCouponDTO coupon, BigDecimal amount) {
        if (coupon == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        String type = coupon.getType() != null ? coupon.getType() : "";
        BigDecimal discount = coupon.getDiscount() != null ? coupon.getDiscount() : BigDecimal.ZERO;

        return switch (type) {
            case "FULL_REDUCTION" -> {
                BigDecimal minAmount = coupon.getMinAmount();
                if (minAmount != null && amount.compareTo(minAmount) >= 0) {
                    yield discount;
                }
                yield BigDecimal.ZERO;
            }
            case "DISCOUNT" -> {
                if (discount.compareTo(BigDecimal.ZERO) > 0 && discount.compareTo(BigDecimal.ONE) <= 0) {
                    yield amount.multiply(BigDecimal.ONE.subtract(discount));
                }
                yield BigDecimal.ZERO;
            }
            case "CASH" -> discount.min(amount);
            default -> BigDecimal.ZERO;
        };
    }

    /**
     * Get the display name for a coupon type code.
     */
    private static String getCouponTypeName(String couponType) {
        if (couponType == null) return "";
        return switch (couponType) {
            case "FULL_REDUCTION" -> "满减券";
            case "DISCOUNT" -> "折扣券";
            case "CASH" -> "现金券";
            default -> couponType;
        };
    }
}
