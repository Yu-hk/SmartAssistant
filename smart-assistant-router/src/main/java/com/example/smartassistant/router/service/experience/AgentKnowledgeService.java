/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.experience;

import com.example.smartassistant.router.service.experience.ExperienceModel.CommonExperience;
import com.example.smartassistant.router.service.experience.ExperienceModel.ReactExperience;
import com.example.smartassistant.router.service.experience.ExperienceModel.ToolExperience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Agent 知识库服务 —— 将经验导出为 MD 文档，作为各 Agent 的知识库。
 * <p>
 * 对话中经验在 Redis 实时匹配，对话结束时写入 MD：
 * <ol>
 *   <li>从 ExperienceService 读取指定 Agent 的所有经验</li>
 *   <li>按类型（TOOL > COMMON > REACT）+ 命中次数降序排列</li>
 *   <li>生成结构化 Markdown，包含意图→工具映射、调用模式、协作关系</li>
 *   <li>写入 knowledge/{agent_name}_kb.md</li>
 * </ol>
 * <p>
 * Agent 启动时通过对应的 AgentConfig 将 MD 注入 system prompt（或通过 @Tool 按需查询），
 * 实现"Agent 认识自己"的自举闭环。
 */
@Service
public class AgentKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(AgentKnowledgeService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Path KNOWLEDGE_DIR = Paths.get("knowledge");

    private final ExperienceService experienceService;

    public AgentKnowledgeService(ExperienceService experienceService) {
        this.experienceService = experienceService;
        try {
            Files.createDirectories(KNOWLEDGE_DIR);
        } catch (Exception e) {
            log.warn("[Knowledge] 创建 knowledge 目录失败: {}", e.getMessage());
        }
    }

    /**
     * 导出指定 Agent 的经验为 MD 知识库。
     *
     * @param agentName Agent 名称，如 "order_agent"
     * @return 生成的 MD 内容
     */
    public String exportToMd(String agentName) {
        long start = System.currentTimeMillis();

        try {
            Set<String> allIds = experienceService.listExperienceIds();
            if (allIds.isEmpty()) return null;

            List<ExperienceModel> agentExps = new ArrayList<>();
            for (String id : allIds) {
                ExperienceModel exp = experienceService.loadExperience(id);
                if (exp != null && agentName.equals(exp.getAgentName())) {
                    agentExps.add(exp);
                }
            }

            if (agentExps.isEmpty()) {
                log.info("[Knowledge] Agent {} 无经验可导出", agentName);
                return null;
            }

            // 按类型优先级 + 命中数排序
            agentExps.sort((a, b) -> {
                int tc = typeOrder(a.getType()) - typeOrder(b.getType());
                if (tc != 0) return tc;
                return Integer.compare(b.getHitCount(), a.getHitCount());
            });

            String md = buildMarkdown(agentName, agentExps);

            // 写入文件
            Path file = KNOWLEDGE_DIR.resolve(agentName + "_kb.md");
            Files.writeString(file, md, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            long elapsed = System.currentTimeMillis() - start;
            log.info("[Knowledge] ✅ 导出 {} 知识库: {} 条经验, {} bytes → {} ({}ms)",
                    agentName, agentExps.size(), md.length(), file, elapsed);

            return md;

        } catch (Exception e) {
            log.error("[Knowledge] 导出失败: agent={}, err={}", agentName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 加载指定 Agent 的知识库 MD 文本（用于注入 system prompt）。
     *
     * @param agentName Agent 名称
     * @return MD 内容，文件不存在时返回 null
     */
    public String loadKnowledge(String agentName) {
        try {
            Path file = KNOWLEDGE_DIR.resolve(agentName + "_kb.md");
            if (!Files.exists(file)) return null;
            String content = Files.readString(file, StandardCharsets.UTF_8);
            log.debug("[Knowledge] 加载 {} 知识库: {} bytes", agentName, content.length());
            return content;
        } catch (Exception e) {
            log.warn("[Knowledge] 加载失败: agent={}, err={}", agentName, e.getMessage());
            return null;
        }
    }

    /**
     * 导出所有 Agent 的知识库。
     */
    public Map<String, String> exportAll() {
        Map<String, String> results = new LinkedHashMap<>();
        Set<String> agentNames = collectAgentNames();
        for (String agentName : agentNames) {
            String md = exportToMd(agentName);
            if (md != null) results.put(agentName, md);
        }
        return results;
    }

    // ==================== 内部方法 ====================

    private int typeOrder(ExperienceModel.Type type) {
        return switch (type) {
            case TOOL -> 1;
            case COMMON -> 2;
            case REACT -> 3;
        };
    }

    private String buildMarkdown(String agentName, List<ExperienceModel> exps) {
        StringBuilder sb = new StringBuilder();
        String now = Instant.now().atZone(ZoneId.systemDefault()).format(DT_FMT);

        sb.append("# ").append(agentName.replace("_", " ")).append(" 知识库\n\n");
        sb.append("> 🤖 自动生成 | ").append(now).append(" | 经验数: ").append(exps.size());
        sb.append(" | TOOL: ").append(exps.stream().filter(e -> e instanceof ToolExperience).count());
        sb.append(" | COMMON: ").append(exps.stream().filter(e -> e instanceof CommonExperience).count());
        sb.append(" | REACT: ").append(exps.stream().filter(e -> e instanceof ReactExperience).count()).append("\n\n");
        sb.append("---\n\n");

        // ═══ 按商品类型分组 ═══
        // 有 productType 的经验按类型分，没 productType 的归入「通用」
        Map<String, List<ExperienceModel>> byProduct = new LinkedHashMap<>();
        for (ExperienceModel e : exps) {
            String pt = e.getProductType() != null && !e.getProductType().isBlank()
                    ? e.getProductType() : "通用";
            byProduct.computeIfAbsent(pt, k -> new ArrayList<>()).add(e);
        }

        // 通用放最后
        List<String> sortedTypes = new ArrayList<>(byProduct.keySet());
        sortedTypes.sort((a, b) -> {
            if ("通用".equals(a)) return 1;
            if ("通用".equals(b)) return -1;
            return Integer.compare(byProduct.get(b).size(), byProduct.get(a).size());
        });

        for (String productType : sortedTypes) {
            List<ExperienceModel> group = byProduct.get(productType);
            sb.append("## 📦 ").append(productType).append(" (").append(group.size()).append(" 条经验)\n\n");

            // 按现有分类展示
            List<ExperienceModel> toolExps = group.stream()
                    .filter(e -> e instanceof ToolExperience && e.getHitCount() > 0)
                    .sorted((a, b) -> Integer.compare(b.getHitCount(), a.getHitCount()))
                    .toList();
            List<ExperienceModel> commonExps = group.stream()
                    .filter(e -> e instanceof CommonExperience).toList();

            if (!toolExps.isEmpty()) {
                Map<String, List<ExperienceModel>> byTool = new LinkedHashMap<>();
                for (ExperienceModel e : toolExps) {
                    ToolExperience te = (ToolExperience) e;
                    byTool.computeIfAbsent(te.getToolName(), k -> new ArrayList<>()).add(e);
                }
                for (var entry : byTool.entrySet()) {
                    sb.append("### 🔧 `").append(entry.getKey()).append("`\n\n");
                    sb.append("| 意图 | 命中 | 推荐参数 |\n|:---|:---:|:---|\n");
                    for (ExperienceModel e : entry.getValue()) {
                        ToolExperience te = (ToolExperience) e;
                        sb.append(String.format("| %s | %d | `%s` |\n",
                                truncate(e.getIntentTag(), 40), e.getHitCount(),
                                te.getRecommendedParams() != null ? truncate(te.getRecommendedParams(), 50) : "-"));
                    }
                    sb.append("\n");
                }
            }

            if (!commonExps.isEmpty()) {
                sb.append("### 🎯 意图路由\n\n");
                sb.append("| 意图关键词 | Agent | 置信度 | 命中 |\n|:---|:---|:---:|:---:|\n");
                for (ExperienceModel e : commonExps) {
                    sb.append(String.format("| %s | `%s` | %.2f | %d |\n",
                            truncate(e.getIntentTag(), 35), e.getAgentName(),
                            e.getConfidence(), e.getHitCount()));
                }
                sb.append("\n");
            }
        }

        // Footer
        sb.append("---\n\n");
        sb.append("> 此文档由 AgentKnowledgeService 自动生成，按商品类型分组（📦）。\n");

        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.isBlank()) return "-";
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    private Set<String> collectAgentNames() {
        Set<String> names = new HashSet<>();
        Set<String> ids = experienceService.listExperienceIds();
        for (String id : ids) {
            ExperienceModel exp = experienceService.loadExperience(id);
            if (exp != null && exp.getAgentName() != null) {
                names.add(exp.getAgentName());
            }
        }
        return names;
    }
}
