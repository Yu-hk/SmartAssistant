/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

/**
 * ReAct 执行过程的观察者接口。
 * <p>
 * 用于监听 {@link SmartReActAgent} 执行过程中的各个阶段，
 * 可对接 SSE 流式输出、日志跟踪、监控埋点等功能。
 * </p>
 *
 * <p>
 * 所有方法均为 default 空实现，实现类只需覆盖感兴趣的阶段。
 * </p>
 *
 * <h3>调用时序</h3>
 * <pre>
 * onThinking(step, reasoning)      ← LLM 产出了推理内容（DeepSeek reasoning_content）
 * onToolCall(step, name, args)     ← LLM 发起了工具调用
 * onToolResult(content)            ← 工具执行完毕返回结果
 *   ↻ 重复以上三轮直至 LLM 不再调用工具
 * onResponse(text)                 ← LLM 返回了最终回答
 * </pre>
 */
public interface ReActObserver {

    /**
     * 模型产出了推理内容（思考过程）。
     * <p>
     * 注意：SmartReActAgent 使用同步 {@code chatModel.call()}，
     * 因此推理内容以完整文本形式一次性返回，而非逐个 Token 产出。
     * 如需逐个 Token 流式体验，请使用 {@code chatModel.stream()} 封装的外部代理。
     *
     * @param step             当前迭代步数（从 1 开始）
     * @param reasoningContent 推理内容
     */
    default void onThinking(int step, String reasoningContent) {}

    /**
     * 模型发起了工具调用。
     *
     * @param step      当前工具调用序号（同轮内从 1 开始）
     * @param toolName  工具名称
     * @param arguments 工具参数（JSON 字符串）
     */
    default void onToolCall(int step, String toolName, String arguments) {}

    /**
     * 工具执行完成，返回结果。
     *
     * @param content 工具执行结果（可能被截断）
     */
    default void onToolResult(String content) {}

    /**
     * 模型返回了最终回答（本轮无工具调用）。
     *
     * @param response 最终回答文本
     */
    default void onResponse(String response) {}
}
