package com.example.smartassistant.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 产品知识库查询工具 — 从经验 MD 知识库中检索与商品咨询相关的历史经验。
 * <p>
 * 知识库积累了用户对同一商品的历史反馈和咨询模式，Agent 可据此：
 * <ul>
 *   <li>了解用户对某商品最关心的维度（价格/规格/库存/评测）</li>
 *   <li>根据历史经验引导用户（"其他用户也问了..."）</li>
 *   <li>按商品的咨询频率决定回答深度</li>
 * </ul>
 * <p>
 * 知识库由 Router 的 {@code AgentKnowledgeService} 在对话结束后自动生成。
 */
@Component
public class KnowledgeQueryTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQueryTool.class);

    @Value("${product.knowledge.path:../smart-assistant-router/knowledge/product_agent_kb.md}")
    private String knowledgePath;

    @Tool(description = "查询产品知识库：从商品咨询的历史经验中查找匹配的意图模式、"
            + "用户常见问题维度（价格/规格/库存/评测）和引导建议。"
            + "输入关键词如'iPhone 价格'、'MacBook 配置'、'耳机 评测'。")
    public String queryKnowledge(
            @ToolParam(description = "查询关键词，如 'iPhone 15 Pro 价格'、'MacBook 配置'、'耳机 对比'", required = true)
            String intent) {

        log.info("[KnowledgeTool] 查询知识库: {}", intent);

        try {
            Path file = Path.of(knowledgePath);
            if (!Files.exists(file)) {
                return "产品知识库尚未生成。继续处理用户请求，此轮对话结束后经验将自动沉淀到知识库。";
            }

            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank()) return "知识库为空。";

            List<String> keywords = extractKeywords(intent);
            List<KnowledgeSection> matched = findRelevantSections(content, keywords);

            if (matched.isEmpty()) {
                return "知识库中未找到与 '" + intent + "' 匹配的历史经验。请直接处理用户请求。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("产品知识库中与 '").append(intent).append("' 相关的历史经验：\n\n");
            for (int i = 0; i < Math.min(matched.size(), 5); i++) {
                KnowledgeSection ks = matched.get(i);
                sb.append("--- ").append(ks.title).append(" (相关度: ").append(String.format("%.0f%%", ks.score * 100)).append(")\n");
                sb.append(ks.content.trim()).append("\n\n");
            }
            sb.append("参考以上历史经验，回答用户问题时可以适当引导。");

            log.info("[KnowledgeTool] 返回 {} 条匹配", Math.min(matched.size(), 5));
            return sb.toString();

        } catch (IOException e) {
            log.warn("[KnowledgeTool] 读取失败: {}", e.getMessage());
            return "知识库读取失败，直接处理用户请求即可。";
        }
    }

    private List<String> extractKeywords(String intent) {
        if (intent == null || intent.isBlank()) return Collections.emptyList();
        List<String> kws = new ArrayList<>();
        Matcher m = Pattern.compile("[a-zA-Z0-9-]+").matcher(intent);
        while (m.find()) kws.add(m.group().toLowerCase());
        m = Pattern.compile("[\\u4e00-\\u9fa5]{1,6}").matcher(intent);
        while (m.find()) { String kw = m.group(); if (kw.length() >= 2) kws.add(kw); }
        return kws;
    }

    private List<KnowledgeSection> findRelevantSections(String content, List<String> keywords) {
        if (keywords.isEmpty()) return Collections.emptyList();
        List<KnowledgeSection> sections = new ArrayList<>();
        String[] parts = content.split("(?=^#{1,3}\\s)");
        String currentTitle = "概要";
        for (String part : parts) {
            Matcher tm = Pattern.compile("^#{1,3}\\s+(.+)", Pattern.MULTILINE).matcher(part);
            if (tm.find()) currentTitle = tm.group(1).trim();
            if (part.trim().startsWith("> 此文档由") || part.trim().startsWith("---")) continue;
            double score = 0;
            int total = keywords.size();
            String lower = part.toLowerCase();
            for (String kw : keywords) if (lower.contains(kw)) score += 1.0 / total;
            if (score > 0.1) sections.add(new KnowledgeSection(currentTitle, part.trim(), score));
        }
        sections.sort((a, b) -> Double.compare(b.score, a.score));
        return sections;
    }

    private static class KnowledgeSection {
        final String title; final String content; final double score;
        KnowledgeSection(String t, String c, double s) { title = t; content = c; score = s; }
    }
}
