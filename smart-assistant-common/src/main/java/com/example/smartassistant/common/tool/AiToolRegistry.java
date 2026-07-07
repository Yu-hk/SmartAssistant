/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具注册聚合器 — 收敛各业务模块重复的工具对象 → ToolCallback 列表构建样板。
 *
 * <p>对标 Spring AI 2.0 工程化样本中的 {@code AiToolRegistry}。文章采用 BeanPostProcessor
 * 全局扫描 @Tool 自动注册；本实现鉴于项目<b>多 Agent 隔离</b>需求（Order / General / Product /
 * Consumer 各有专属工具，不应跨 Agent 泄露），采用「显式传入工具 bean 给聚合器」的方式：
 * 既消除每工具 {@code MethodToolCallbackProvider.builder().toolObjects(bean).build()
 * .getToolCallbacks()} 的重复样板，又保持工具归属的清晰边界。</p>
 *
 * <p>若未来为单 Agent 场景，可扩展为 BeanPostProcessor 全局自动扫描（按 {@code @Tool} 注解收集）。</p>
 */
public class AiToolRegistry {

    /** 将多个工具对象聚合为 ToolCallback 列表（自动跳过 null） */
    public List<ToolCallback> assemble(Object... toolBeans) {
        List<ToolCallback> callbacks = new ArrayList<>();
        if (toolBeans == null) return callbacks;
        for (Object bean : toolBeans) {
            if (bean == null) continue;
            callbacks.addAll(List.of(
                    MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()));
        }
        return callbacks;
    }

    /** 列表版重载 */
    public List<ToolCallback> assemble(List<Object> toolBeans) {
        return assemble(toolBeans.toArray(new Object[0]));
    }
}
