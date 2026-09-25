/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.core;

import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.common.agent.protocol.AgentNodeOutput;
import com.example.smartassistant.common.quality.DomainAgentResponse;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.routing.contract.RoutingKeys;
import com.example.smartassistant.routing.contract.WorkflowOperation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Business policy for using verified catalog evidence in product workflow replies. */
public final class ProductEvidenceResponsePolicy {

    private ProductEvidenceResponsePolicy() {
    }

    public static boolean isAnalysisOrRecommendationRequest(AgentExecutionRequest request) {
        return WorkflowOperation.ANALYZE_PRODUCT_DATA.code().equalsIgnoreCase(request.operation())
                || WorkflowOperation.RECOMMEND_PRODUCT.code().equalsIgnoreCase(request.operation());
    }

    public static String buildVerifiedContext(AgentExecutionRequest request) {
        StringBuilder context = new StringBuilder();
        Object profile = request.input().get(RoutingKeys.USER_PROFILE_INPUT);
        String userProfile = profile == null ? "" : String.valueOf(profile).trim();
        if (!userProfile.isBlank()) {
            context.append("[用户画像]\n").append(userProfile).append("\n\n");
        }
        request.predecessorOutputs().forEach((nodeId, output) -> {
            context.append("[上游节点 ").append(nodeId).append("]\n");
            if (output.data() != null && !output.data().isEmpty()) {
                context.append("结构化数据：").append(output.data()).append('\n');
            }
            // Discovery prose duplicates its typed catalog; analysis prose may add context.
            boolean structuredCatalog = output.data() != null && output.data().containsKey("products");
            if (structuredCatalog) {
                context.append("目录字段口径：popularity 仅为目录 sales_30d 记录的站内近30天销量，不累加历史订单，不代表全网热度；")
                        .append("rating 为5分制评分，reviewCount 为评价数量。\n");
            }
            if (!structuredCatalog && output.answer() != null && !output.answer().isBlank()) {
                context.append(output.answer().trim()).append('\n');
            }
            context.append('\n');
        });
        return context.toString().trim();
    }

    /** Never convert an audit failure into a recommendation solely because products exist. */
    public static DomainAgentResponse ensureEvidenceBackedRecommendation(
            AgentExecutionRequest request, DomainAgentResponse modelResponse) {
        if (modelResponse.quality().isFail()
                || modelResponse.quality().getReasonCodes().contains("NO_ELIGIBLE_VERIFIED_PRODUCT")) {
            return modelResponse;
        }
        List<Map<?, ?>> products = verifiedProducts(request);
        if (products.isEmpty() || mentionsVerifiedProduct(modelResponse.answer(), products)) {
            return modelResponse;
        }

        List<Map<?, ?>> displayed = products.stream().limit(5).toList();
        StringBuilder answer = new StringBuilder("当前可核实的商品候选：\n");
        int index = 1;
        Object sharedPopularity = null;
        boolean samePopularity = displayed.size() > 1;
        for (Map<?, ?> product : displayed) {
            String code = text(product.get("code"));
            String name = text(product.get("name"));
            String price = decimalText(product.get("price"));
            String stock = text(product.get("stock"));
            Object popularity = product.get("popularity");
            if (!(popularity instanceof Number count) || count.doubleValue() <= 0) {
                samePopularity = false;
            }
            if (sharedPopularity == null) sharedPopularity = popularity;
            else if (!String.valueOf(sharedPopularity).equals(String.valueOf(popularity))) {
                samePopularity = false;
            }
            answer.append(index++).append(". ").append(name);
            if (!code.isBlank()) answer.append("（").append(code).append("）");
            if (!price.isBlank()) answer.append(" — ¥").append(price);
            if (!stock.isBlank()) answer.append("，库存：").append(stock);
            if (popularity instanceof Number count && count.doubleValue() > 0) {
                answer.append("，近30天站内销量：").append(popularity);
            }
            if (!text(product.get("spec")).isBlank()) {
                answer.append("，规格：").append(text(product.get("spec")));
            }
            if (product.get("rating") instanceof Number rating && rating.doubleValue() > 0) {
                answer.append("，评分：").append(decimalText(rating)).append("/5");
            }
            if (product.get("reviewCount") instanceof Number count && count.longValue() > 0) {
                answer.append("，评价数：").append(count);
            }
            answer.append('\n');
        }
        if (samePopularity && sharedPopularity != null) {
            answer.append("\n以上展示候选的近30天站内销量均为 ").append(sharedPopularity)
                    .append("，仅凭该销量无法区分优先顺序。");
        } else {
            answer.append("\n以上候选来自当前商品目录。");
        }
        answer.append("具体用途的适配性仍需结合相应规格或实测核实，不能仅凭销量认定最适合。");
        return DomainAgentResponse.of(answer.toString().trim(),
                DomainQualityResult.warn(0.8,
                        "PRODUCT_RECOMMENDATION_VERIFIED_CANDIDATE_FALLBACK",
                        "PRODUCT_RECOMMENDATION_EVIDENCE_LIMITED"));
    }

    /** Preserve clarification/browse results across both direct and transitive DAG edges. */
    public static AgentNodeOutput verifiedDiscoveryReply(AgentExecutionRequest request) {
        AgentNodeOutput reply = null;
        for (AgentNodeOutput output : request.predecessorOutputs().values()) {
            if (!"SUCCEEDED".equals(output.status())) return null;
            // One terminal marker must not hide conflicting catalog evidence on another edge.
            if (output.data().containsKey("products")
                    && !Boolean.TRUE.equals(output.data().get("clarificationRequired"))
                    && !Boolean.TRUE.equals(output.data().get("browsingOnly"))) return null;
            if (Boolean.TRUE.equals(output.data().get("clarificationRequired"))
                    || Boolean.TRUE.equals(output.data().get("browsingOnly"))) {
                if (output.answer() == null || output.answer().isBlank()) return null;
                if (reply != null && !reply.answer().equals(output.answer())) return null;
                reply = output;
            }
        }
        return reply;
    }

    public static boolean hasVerifiedEmptyCatalog(AgentExecutionRequest request) {
        boolean emptyCatalog = false;
        for (AgentNodeOutput output : request.predecessorOutputs().values()) {
            if (!"SUCCEEDED".equals(output.status())) return false;
            Object products = output.data().get("products");
            if (products instanceof List<?> items && !items.isEmpty()) return false;
            Object count = output.data().get("productCount");
            if (products instanceof List<?> items && items.isEmpty()
                    && count instanceof Number number && number.doubleValue() == 0) {
                emptyCatalog = true;
            }
        }
        return emptyCatalog;
    }

    public static List<Map<?, ?>> verifiedProducts(AgentExecutionRequest request) {
        Map<String, Map<?, ?>> catalog = new LinkedHashMap<>();
        for (AgentNodeOutput output : request.predecessorOutputs().values()) {
            if (!"SUCCEEDED".equals(output.status())) return List.of();
            Object value = output.data().get("products");
            if (!(value instanceof List<?> items) || items.isEmpty()) continue;
            for (Object item : items) {
                Map<?, ?> product;
                if (item instanceof Map<?, ?> map) product = map;
                else if (item instanceof com.example.smartassistant.spi.ProductBackend.ProductSummary summary) {
                    product = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(summary, Map.class);
                } else return List.of();
                String code = text(product.get("code"));
                if (code.isBlank() || text(product.get("name")).isBlank()) return List.of();
                Map<?, ?> previous = catalog.putIfAbsent(code, product);
                if (previous != null && !previous.equals(product)) return List.of();
            }
        }
        return List.copyOf(catalog.values());
    }

    private static boolean mentionsVerifiedProduct(String answer, List<Map<?, ?>> products) {
        if (answer == null || answer.isBlank()) return false;
        String normalizedAnswer = normalizeProductReference(answer);
        for (Map<?, ?> product : products) {
            String code = normalizeProductReference(text(product.get("code")));
            String name = normalizeProductReference(text(product.get("name")));
            if ((!code.isBlank() && normalizedAnswer.contains(code))
                    || (!name.isBlank() && normalizedAnswer.contains(name))) return true;
        }
        return false;
    }

    private static String normalizeProductReference(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String decimalText(Object value) {
        if (value == null) return "";
        try {
            return new java.math.BigDecimal(String.valueOf(value))
                    .stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return text(value);
        }
    }
}
