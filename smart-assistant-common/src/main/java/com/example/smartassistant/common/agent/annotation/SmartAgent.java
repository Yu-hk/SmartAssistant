/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个接口为 Smart Agent。
 * <p>
 * 框架会自动生成 JDK 动态代理，将接口方法调用转为 ReAct 循环。
 * </p>
 *
 * <pre>
 * &#64;SmartAgent(name = "order_agent", systemPrompt = "prompts/order-system-prompt.txt")
 * public interface OrderAgent {
 *     String chat(&#64;UserMessage String message);
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SmartAgent {

    /** Agent 名称（需与 Nacos 注册名一致） */
    String name();

    /** 系统提示词 classpath 路径 */
    String systemPrompt() default "";

    /** 最大迭代次数 */
    int maxIterations() default 10;

    /** 超时毫秒 */
    long timeoutMs() default 60_000;

    /** Token 预算比例 (0~1) */
    double tokenBudgetRatio() default 0.8;

    /** 上下文窗口大小 */
    int contextWindow() default 128_000;

    /** 是否启用上下文压缩 */
    boolean enableCompress() default true;

    /** 触发压缩的消息数 */
    int compressThreshold() default 20;

    /** 压缩时保留的轮次 */
    int keepRounds() default 3;

    /** 是否启用并行工具执行 */
    boolean parallelExecution() default true;

    /** 最大并发工具数 */
    int maxConcurrency() default 4;

    /** 单个工具超时毫秒 */
    long toolTimeoutMs() default 30_000;
}
