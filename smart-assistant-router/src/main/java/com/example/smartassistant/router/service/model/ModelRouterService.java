/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.model;

import com.example.smartassistant.common.model.tier.ModelTier;
import com.example.smartassistant.common.model.tier.TieredModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * ⭐ G3 多模型路由门面 — 委托 common {@link TieredModelRouter} 统一完成「复杂度→档位」动态路由。
 *
 * <p>历史实现依赖 {@code defaultChatModel}/{@code heavyChatModel} 两个从未定义的 Spring Bean
 * （启动即 {@code UnsatisfiedDependencyException}），且从未被任何调用方使用，属于断引用死代码。
 * 此处改为注入 common 中台的 {@link TieredModelRouter}，对外保留 {@code selectModel}/{@code getModelTierName}
 * 生态 API；真正的「平滑降级 + 档位选择」逻辑沉淀到 common 层，供所有服务复用。</p>
 *
 * <p>若 common 中台因无 {@code OllamaChatModel}（如纯 HTTP 转发环境）未装配
 * {@link TieredModelRouter}，本门面优雅降级为 {@code null}，调用方需判空。</p>
 */
@Service
public class ModelRouterService {

    private static final Logger log = LoggerFactory.getLogger(ModelRouterService.class);

    private final TieredModelRouter tierRouter;

    @Autowired(required = false)
    public ModelRouterService(TieredModelRouter tierRouter) {
        this.tierRouter = tierRouter;
        if (tierRouter == null) {
            log.warn("[ModelRouter] common TieredModelRouter 未装配（无 OllamaChatModel？），本门面仅保留 API 壳。");
        }
    }

    /**
     * 根据查询选择对应档位的 ChatModel（无降级，供调用方自行决定）。
     *
     * @param query 用户查询
     * @return 档位对应的 {@link ChatModel}；中台未装配时返回 null
     */
    public ChatModel selectModel(String query) {
        if (tierRouter == null) {
            return null;
        }
        return tierRouter.selectModel(query, null);
    }

    /**
     * 获取查询对应的模型档位名称（用于日志/追踪）。
     */
    public String getModelTierName(String query) {
        if (tierRouter == null) {
            return ModelTier.STANDARD.name();
        }
        return tierRouter.selectTier(query, null).name();
    }
}
