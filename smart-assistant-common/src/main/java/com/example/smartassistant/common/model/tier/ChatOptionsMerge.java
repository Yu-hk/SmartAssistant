package com.example.smartassistant.common.model.tier;

import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * 合并模型档位默认选项与单次请求选项。
 * 请求级 maxTokens、温度等必须覆盖默认值，同时保留档位指定的模型名。
 */
public final class ChatOptionsMerge {

    private ChatOptionsMerge() {
    }

    public static ChatOptions merge(ChatOptions defaults, ChatOptions requestOptions) {
        if (defaults == null) return requestOptions;
        if (requestOptions == null) return defaults;
        return defaults.mutate()
                .combineWith(requestOptions.mutate())
                .build();
    }
}
