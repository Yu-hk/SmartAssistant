/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import com.example.smartassistant.common.agent.annotation.MemoryId;
import com.example.smartassistant.common.agent.annotation.SmartAgent;
import com.example.smartassistant.common.agent.annotation.UserMessage;
import com.example.smartassistant.common.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 代理工厂 — 根据 {@link SmartAgent} 注解的接口自动生成 Agent 实现。
 * <p>
 * 消除各微服务中重复的 {@code @Configuration} 配置类。
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>
 * // 1. 定义 Agent 接口
 * &#64;SmartAgent(name = "order_agent", systemPrompt = "prompts/order-system-prompt.txt")
 * public interface OrderAgent {
 *     String chat(&#64;UserMessage String message);
 * }
 *
 * // 2. 一行创建
 * OrderAgent agent = AgentProxyFactory.create(OrderAgent.class, chatModel, orderTools);
 * </pre>
 */
public class AgentProxyFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentProxyFactory.class);

    /**
     * 创建 Agent 代理对象（接口风格）。
     * <p>
     * 适用于新代码：消费者面向接口编程。
     *
     * @param agentInterface 标注了 @SmartAgent 的接口
     * @param chatModel      Spring AI ChatModel
     * @param toolInstances  工具实例
     * @param <T>            接口类型
     * @return 代理实例（实现 agentInterface）
     */
    @SuppressWarnings("unchecked")
    public static <T> T create(Class<T> agentInterface, ChatModel chatModel, Object... toolInstances) {
        SmartAgent annotation = getAnnotation(agentInterface);
        SmartReActAgent agent = buildAgent(annotation, chatModel, toolInstances);
        Method targetMethod = findTargetMethod(agentInterface);

        return (T) java.lang.reflect.Proxy.newProxyInstance(
                agentInterface.getClassLoader(),
                new Class[]{agentInterface},
                new AgentInvocationHandler(agent, targetMethod, annotation.name()));
    }

    /**
     * 创建 SmartReActAgent 实例（向后兼容风格）。
     * <p>
     * 适用于旧代码：消费者仍使用 {@code SmartReActAgent.execute()}。
     * 工厂负责解析 {@code @SmartAgent} 注解并完成全部构建。
     *
     * @param agentInterface 标注了 @SmartAgent 的接口
     * @param chatModel      Spring AI ChatModel
     * @param toolInstances  工具实例
     * @return SmartReActAgent（不生成代理，可直接调用 execute）
     */
    public static SmartReActAgent createAgent(Class<?> agentInterface, ChatModel chatModel, Object... toolInstances) {
        SmartAgent annotation = getAnnotation(agentInterface);
        return buildAgent(annotation, chatModel, toolInstances);
    }

    private static SmartAgent getAnnotation(Class<?> agentInterface) {
        SmartAgent annotation = agentInterface.getAnnotation(SmartAgent.class);
        if (annotation == null) {
            throw new IllegalArgumentException("接口 " + agentInterface.getName()
                    + " 未标注 @SmartAgent 注解");
        }
        return annotation;
    }

    // ==================== 内部构建 ====================

    private static SmartReActAgent buildAgent(SmartAgent annotation, ChatModel chatModel, Object[] toolInstances) {
        // 1. 收集工具：通过 MethodToolCallbackProvider 统一发现 @Tool 方法
        List<ToolCallback> toolCallbacks = new ArrayList<>();
        for (Object toolInstance : toolInstances) {
            if (toolInstance instanceof ToolCallback tc) {
                toolCallbacks.add(tc);
            } else {
                MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                        .toolObjects(toolInstance)
                        .build();
                for (var tc : provider.getToolCallbacks()) {
                    toolCallbacks.add(tc);
                }
            }
        }

        // 2. 加载系统提示词
        String servicePrompt = loadSystemPrompt(annotation.systemPrompt());

        // 3. 构建并配置 SmartReActAgent
        return new SmartReActAgent(chatModel)
                .withMaxIterations(annotation.maxIterations())
                .withTimeoutMs(annotation.timeoutMs())
                .withTokenBudget(true, annotation.tokenBudgetRatio(), annotation.contextWindow())
                .withCompress(annotation.enableCompress(), annotation.compressThreshold(), annotation.keepRounds())
                .withParallelExecution(annotation.parallelExecution(), annotation.maxConcurrency(), annotation.toolTimeoutMs())
                .withPreset(PromptBuilder.build()
                        .withServicePrompt(servicePrompt)
                        .assemble(), toolCallbacks);
    }

    private static Method findTargetMethod(Class<?> agentInterface) {
        for (Method m : agentInterface.getDeclaredMethods()) {
            for (Parameter p : m.getParameters()) {
                if (p.isAnnotationPresent(UserMessage.class)) {
                    return m;
                }
            }
        }
        // 兜底：用第一个方法
        Method[] methods = agentInterface.getDeclaredMethods();
        if (methods.length > 0) {
            return methods[0];
        }
        throw new IllegalArgumentException("接口 " + agentInterface.getName() + " 中没有可调用方法");
    }

    private static String loadSystemPrompt(String path) {
        if (path == null || path.isBlank()) return "";
        try {
            Resource resource = new DefaultResourceLoader().getResource("classpath:" + path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[AgentProxy] 加载系统提示词失败: {}", path);
            return "";
        }
    }

    // ==================== JDK 动态代理 ====================

    private record AgentInvocationHandler(
            SmartReActAgent agent,
            Method targetMethod,
            String agentName
    ) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Object 方法直接转发
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }
            // 只处理目标方法
            if (!method.equals(targetMethod)) {
                log.warn("[AgentProxy:{}] 忽略未定义的方法: {}", agentName, method.getName());
                return null;
            }

            // 提取参数
            String userMessage = null;
            Parameter[] params = targetMethod.getParameters();
            if (args != null) {
                for (int i = 0; i < Math.min(params.length, args.length); i++) {
                    if (params[i].isAnnotationPresent(UserMessage.class)) {
                        userMessage = args[i] != null ? args[i].toString() : "";
                    }
                }
            }
            if (userMessage == null && args != null && args.length > 0) {
                userMessage = args[0].toString();
            }
            if (userMessage == null) {
                throw new IllegalArgumentException("缺少 @UserMessage 参数");
            }

            log.info("[AgentProxy:{}] 执行: message={}", agentName, truncate(userMessage, 80));
            return agent.execute(userMessage);
        }

        private String truncate(String s, int max) {
            return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
        }
    }
}
