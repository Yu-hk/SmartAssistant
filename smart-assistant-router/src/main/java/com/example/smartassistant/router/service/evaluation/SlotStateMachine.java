/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 词槽状态机——基于意图类型定义词槽表，跟踪填充/缺失/默认/冲突状态。
 * <p>
 * 对应评测维度：词槽填充、词槽缺失、词槽冲突、词槽追问。
 * </p>
 * <p>
 * 为每个高频意图（ORDER/查单、ORDER/下单、PRODUCT/查商品等）维护独立词槽表，
 * 区分：必填、可选、可默认、高风险 四类槽位。
 * </p>
 */
@Service
public class SlotStateMachine {

    private static final Logger log = LoggerFactory.getLogger(SlotStateMachine.class);

    /**
     * 词槽定义。
     *
     * @param name          槽位名称
     * @param description   槽位说明
     * @param required      在当前意图下是否必填
     * @param defaultable   是否可系统默认（如常用出发站）
     * @param highRisk      是否高风险（涉及交易/支付/乘客身份）
     * @param askPriority   追问优先级（1最高）
     */
    public record SlotDef(
            String name,
            String description,
            boolean required,
            boolean defaultable,
            boolean highRisk,
            int askPriority
    ) {}

    // ==================== 意图词槽表定义 ====================

    private final Map<String, List<SlotDef>> slotTables = new LinkedHashMap<>();

    public SlotStateMachine() {
        // ---- ORDER/查单 ----
        slotTables.put("ORDER/查订单", List.of(
                new SlotDef("order_id", "订单号", false, false, false, 1),
                new SlotDef("date", "订单日期", false, false, false, 2)
        ));

        // ---- ORDER/下单 ----
        slotTables.put("ORDER/下单", List.of(
                new SlotDef("departure_station", "出发站", true, true, false, 1),
                new SlotDef("arrival_station", "到达站", true, false, false, 1),
                new SlotDef("departure_date", "出发日期", true, false, false, 2),
                new SlotDef("departure_time", "出发时间", false, false, false, 3),
                new SlotDef("passenger", "乘客姓名", true, false, true, 4),
                new SlotDef("seat_type", "席别", false, true, false, 5),
                new SlotDef("train_type", "车次类型", false, true, false, 6),
                new SlotDef("ticket_count", "票数", true, false, false, 6),
                new SlotDef("price_limit", "价格上限", false, false, false, 7)
        ));

        // ---- ORDER/改签 ----
        slotTables.put("ORDER/改签", List.of(
                new SlotDef("order_id", "原订单号", true, false, true, 1),
                new SlotDef("new_departure_date", "新出发日期", false, false, false, 2),
                new SlotDef("new_departure_time", "新出发时间", false, false, false, 3),
                new SlotDef("new_train", "新车次", false, false, false, 4)
        ));

        // ---- ORDER/退票 ----
        slotTables.put("ORDER/退票", List.of(
                new SlotDef("order_id", "订单号", true, false, true, 1),
                new SlotDef("reason", "退票原因", false, false, false, 2)
        ));

        // ---- PRODUCT/查商品 ----
        slotTables.put("PRODUCT/查商品", List.of(
                new SlotDef("product_name", "商品名称", true, false, false, 1),
                new SlotDef("product_category", "商品分类", false, true, false, 2),
                new SlotDef("price_range", "价格范围", false, false, false, 3)
        ));

        // ---- PRODUCT/查库存 ----
        slotTables.put("PRODUCT/查库存", List.of(
                new SlotDef("product_name", "商品名称", true, false, false, 1),
                new SlotDef("warehouse", "仓库", false, true, false, 2)
        ));
    }

    /**
     * 分析词槽填充状态。
     *
     * @param intentCategory 意图分类
     * @param entities       已提取实体
     * @return 填充/缺失/可默认列表
     */
    public SlotAnalysisResult analyzeSlots(String intentCategory, Map<String, Object> entities) {
        if (intentCategory == null) {
            return new SlotAnalysisResult(Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        // 查找匹配的词槽表
        List<SlotDef> slotDefs = findSlotTable(intentCategory);
        if (slotDefs.isEmpty()) {
            return new SlotAnalysisResult(Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        List<String> filled = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> defaultable = new ArrayList<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();

        for (SlotDef slot : slotDefs) {
            boolean isFilled = false;

            // 检查 entities 是否包含该槽位
            for (Map.Entry<String, Object> entry : entities.entrySet()) {
                String entityKey = normalizeEntityKey(entry.getKey());
                if (entityKey.equals(slot.name())
                        || entityKey.contains(slot.name().replace("_", ""))) {
                    filled.add(slot.name());
                    isFilled = true;
                    break;
                }
            }

            if (!isFilled) {
                if (slot.required()) {
                    if (slot.defaultable()) {
                        defaultable.add(slot.name());
                    } else {
                        missing.add(slot.name());
                    }
                }
                // 非必填且非可默认的，不记录
            }
        }

        // 检测已知冲突组合
        detectConflicts(entities, conflicts);

        return new SlotAnalysisResult(filled, missing, defaultable, conflicts, slotDefs);
    }

    /**
     * 为缺失词槽生成追问排序。
     *
     * @param intentCategory 意图分类
     * @param entities       已提取实体
     * @return 按优先级排序的追问槽位列表
     */
    public List<String> getClarificationPriority(String intentCategory, Map<String, Object> entities) {
        SlotAnalysisResult analysis = analyzeSlots(intentCategory, entities);

        // 优先级排序：必填缺失 > 可确认默认 > 可选缺失
        List<String> priority = new ArrayList<>();

        // 1. 必填缺失（按 askPriority）
        List<SlotDef> slotDefs = findSlotTable(intentCategory);
        if (!slotDefs.isEmpty()) {
            for (SlotDef def : slotDefs) {
                if (analysis.missingSlots().contains(def.name())) {
                    priority.add(def.name());
                }
            }
        }

        // 2. 可默认但未确认的
        priority.addAll(analysis.defaultableSlots());

        return priority;
    }

    /**
     * 判断当前意图是否需要澄清（缺少必填词槽时）。
     */
    public boolean needsClarification(String intentCategory, Map<String, Object> entities) {
        SlotAnalysisResult analysis = analyzeSlots(intentCategory, entities);
        return !analysis.missingSlots().isEmpty();
    }

    // ==================== 内部方法 ====================

    /** 查找匹配意图的词槽表，支持模糊匹配 */
    private List<SlotDef> findSlotTable(String intentCategory) {
        if (intentCategory == null) return Collections.emptyList();

        // 精确匹配
        if (slotTables.containsKey(intentCategory)) {
            return slotTables.get(intentCategory);
        }

        // 前缀匹配（如 ORDER 匹配 ORDER/下单）
        for (Map.Entry<String, List<SlotDef>> entry : slotTables.entrySet()) {
            if (entry.getKey().startsWith(intentCategory + "/")
                    || intentCategory.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        // 大类匹配
        for (Map.Entry<String, List<SlotDef>> entry : slotTables.entrySet()) {
            if (entry.getKey().startsWith(intentCategory)) {
                return entry.getValue();
            }
        }

        return Collections.emptyList();
    }

    /** 将 entity key 统一格式以匹配 slot name */
    private String normalizeEntityKey(String key) {
        if (key == null) return "";
        return key.toLowerCase()
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
    }

    /** 检测词槽冲突 */
    private void detectConflicts(Map<String, Object> entities, List<Map<String, Object>> conflicts) {
        if (entities == null || entities.isEmpty()) return;

        // 1. 出发站 == 到达站
        String departure = getEntityStr(entities, "departure_station", "departure", "from");
        String arrival = getEntityStr(entities, "arrival_station", "arrival", "to", "destination");
        if (departure != null && arrival != null && departure.equals(arrival)) {
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("slot1", "departure_station");
            conflict.put("slot2", "arrival_station");
            conflict.put("type", "显性冲突");
            conflict.put("reason", "出发站和到达站不能相同");
            conflict.put("values", departure + " == " + arrival);
            conflicts.add(conflict);
        }

        // 2. 早于时间 vs 到达时间冲突
        String departTime = getEntityStr(entities, "departure_time", "time");
        String arriveTime = getEntityStr(entities, "arrival_time", "arrival_time");
        if (departTime != null && arriveTime != null) {
            // 粗略检测：出发时 > 到达时 → 冲突
            int depHour = extractHour(departTime);
            int arrHour = extractHour(arriveTime);
            if (depHour > 0 && arrHour > 0 && depHour >= arrHour) {
                // 如果跨日则不算冲突，但这里简单检测
                Map<String, Object> conflict = new LinkedHashMap<>();
                conflict.put("slot1", "departure_time");
                conflict.put("slot2", "arrival_time");
                conflict.put("type", "显性冲突");
                conflict.put("reason", "出发时间不能晚于到达时间");
                conflict.put("values", departTime + " -> " + arriveTime);
                conflicts.add(conflict);
            }
        }

        // 3. 价格约束矛盾
        String priceMin = getEntityStr(entities, "price_min", "min_price", "min");
        String priceMax = getEntityStr(entities, "price_max", "max_price", "max", "price_limit");
        if (priceMin != null && priceMax != null) {
            try {
                double min = Double.parseDouble(priceMin.replaceAll("[^\\d.]", ""));
                double max = Double.parseDouble(priceMax.replaceAll("[^\\d.]", ""));
                if (min > max) {
                    Map<String, Object> conflict = new LinkedHashMap<>();
                    conflict.put("slot1", "price_min");
                    conflict.put("slot2", "price_max");
                    conflict.put("type", "显性冲突");
                    conflict.put("reason", "最低价格不能高于最高价格");
                    conflict.put("values", priceMin + " > " + priceMax);
                    conflicts.add(conflict);
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    private String getEntityStr(Map<String, Object> entities, String... keys) {
        for (String key : keys) {
            Object val = entities.get(key);
            if (val != null) {
                String str = val.toString();
                if (!str.isBlank() && !"null".equalsIgnoreCase(str)) {
                    return str;
                }
            }
        }
        return null;
    }

    private int extractHour(String timeStr) {
        if (timeStr == null) return -1;
        Pattern p = java.util.regex.Pattern.compile("(\\d{1,2})[:点时]");
        java.util.regex.Matcher m = p.matcher(timeStr);
        if (m.find()) {
            try {
                int hour = Integer.parseInt(m.group(1));
                if ((timeStr.contains("下午") || timeStr.contains("晚上")) && hour <= 12) {
                    hour += 12;
                }
                return hour;
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    // ==================== 内部类 ====================

    public record SlotAnalysisResult(
            List<String> filledSlots,
            List<String> missingSlots,
            List<String> defaultableSlots,
            List<Map<String, Object>> conflicts,
            List<SlotDef> slotDefs
    ) {
        public boolean hasMissing() { return missingSlots != null && !missingSlots.isEmpty(); }
        public boolean hasConflicts() { return conflicts != null && !conflicts.isEmpty(); }
        public boolean hasDefaultable() { return defaultableSlots != null && !defaultableSlots.isEmpty(); }
    }
}
