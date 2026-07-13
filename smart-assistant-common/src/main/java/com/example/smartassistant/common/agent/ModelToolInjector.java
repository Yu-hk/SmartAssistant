package com.example.smartassistant.common.agent;

import org.slf4j.Logger;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * ⭐ 向自定义 ChatModel 注入工具列表（通过反射，避免循环依赖）。
 *
 * <p>如果 chatModel 有 {@code setToolCallbacks(List)} 方法，则调用它。
 * 这允许 CustomDeepSeekChatModel 获取工具定义并向 DeepSeek API 发送 tools 参数。</p>
 */
public final class ModelToolInjector {

    private ModelToolInjector() {
    }

    /**
     * 反射注入工具到 ChatModel。
     *
     * @param log       Agent 的日志器（用于记录注入结果/失败）
     * @param chatModel 目标 ChatModel
     * @param tools     待注入的工具回调列表
     */
    public static void inject(Logger log, ChatModel chatModel, List<ToolCallback> tools) {
        try {
            var method = chatModel.getClass().getMethod("setToolCallbacks", java.util.List.class);
            method.invoke(chatModel, tools);
            log.debug("[SmartReActAgent] 通过反射注入 {} 个工具到 ChatModel", tools.size());
        } catch (NoSuchMethodException e) {
            // ChatModel 没有 setToolCallbacks 方法，这是正常的
        } catch (Exception e) {
            log.warn("[SmartReActAgent] 注入工具到 ChatModel 失败: {}", e.getMessage());
        }
    }
}
