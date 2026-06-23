/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.error;

/**
 * 错误分类——按错误来源划分，用于 {@link ErrorRecoveryService} 做分类级恢复策略判断。
 */
public enum ErrorCategory {

    /** 工具执行异常（运行时失败、超时、工具幻觉等） */
    TOOL,

    /** 数据查找失败（未找到订单/商品/物流等） */
    DATA,

    /** 状态冲突（订单状态不匹配、重复操作等） */
    STATE,

    /** 外部服务不可用（新闻/搜索/汇率/优惠券等） */
    SERVICE,

    /** 参数校验错误（表达式解析/货币代码/脚本拒绝等） */
    VALIDATION,

    /** 安全限制（敏感操作/危险脚本等） */
    SECURITY,

    /** 系统内部错误（序列化/预算超限/超时等） */
    SYSTEM
}
