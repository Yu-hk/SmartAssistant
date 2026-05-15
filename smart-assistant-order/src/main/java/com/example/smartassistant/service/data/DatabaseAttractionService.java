/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.data;

import com.example.smartassistant.entity.TouristAttraction;
import com.example.smartassistant.knowledge.TouristAttractionKnowledgeBase;
import com.example.smartassistant.mapper.TouristAttractionMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据库驱动的景点知识库服务
 * 从 PostgreSQL 数据库读取景点数据，替代硬编码方式
 */
@Service
public class DatabaseAttractionService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseAttractionService.class);

    /**
     * 初始化幂等性标记 - 避免重复初始化
     */
    private static volatile boolean initialized = false;

    private final TouristAttractionMapper mapper;
    private final AttractionDataImportService importService;

    /**
     * 是否允许自动初始化（可通过配置关闭）
     */
    @Value("${attraction.auto-init:true}")
    private boolean autoInitEnabled;

    public DatabaseAttractionService(TouristAttractionMapper mapper,
                                    AttractionDataImportService importService) {
        this.mapper = mapper;
        this.importService = importService;
    }

    /**
     * 初始化：如果数据库为空且尚未初始化过，自动导入基础数据
     * 使用 volatile 标记确保幂等性 - 多次调用只执行一次
     */
    @PostConstruct
    public synchronized void initialize() {
        // 幂等性检查：已初始化过则跳过
        if (initialized) {
            log.debug("[DatabaseAttraction] 已初始化过，跳过");
            return;
        }

        // 如果自动初始化被禁用，也跳过
        if (!autoInitEnabled) {
            log.info("[DatabaseAttraction] 自动初始化已禁用");
            initialized = true;
            return;
        }

        long count = mapper.selectCount(null);
        if (count == 0) {
            log.info("[DatabaseAttraction] 数据库为空，正在导入基础数据集...");
            importService.importExtendedDataset();
        } else {
            log.info("[DatabaseAttraction] 数据库已有 {} 个景点，跳过初始化", count);
        }

        // 标记为已初始化
        initialized = true;
    }

    /**
     * 强制重新初始化（供管理接口调用）
     */
    public synchronized void forceInitialize() {
        log.info("[DatabaseAttraction] 强制重新初始化...");
        initialized = false;
        initialize();
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 根据城市查询景点
     */
    public List<TouristAttractionKnowledgeBase.AttractionInfo> getAttractionsByCity(String city) {
        List<TouristAttraction> entities = mapper.findByCity(city);
        return entities.stream()
                .map(this::convertToAttractionInfo)
                .collect(Collectors.toList());
    }

    /**
     * 根据关键词搜索景点
     */
    public List<TouristAttractionKnowledgeBase.AttractionInfo> searchAttractions(String keyword) {
        List<TouristAttraction> byName = mapper.findByNameContaining(keyword);
        return byName.stream()
                .map(this::convertToAttractionInfo)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有支持的城市
     */
    public List<String> getAllCities() {
        return mapper.findAllCities();
    }

    /**
     * 获取热门景点（按城市）
     */
    public List<TouristAttractionKnowledgeBase.AttractionInfo> getTopAttractions(String city, int limit) {
        List<TouristAttraction> attractions = mapper.findByCity(city);
        return attractions.stream()
                .limit(limit)
                .map(this::convertToAttractionInfo)
                .collect(Collectors.toList());
    }

    /**
     * 生成旅游攻略
     */
    public String generateTravelGuide(String city) {
        List<TouristAttractionKnowledgeBase.AttractionInfo> attractions = getAttractionsByCity(city);
        
        if (attractions.isEmpty()) {
            return "抱歉，暂未收录 " + city + " 的景点信息。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🗺️ 【").append(city).append("旅游攻略】\n\n");
        sb.append("📊 共有 ").append(attractions.size()).append(" 个推荐景点\n\n");
        
        // 推荐 TOP 景点
        sb.append("🌟 必游景点推荐：\n");
        int count = Math.min(3, attractions.size());
        for (int i = 0; i < count; i++) {
            var attraction = attractions.get(i);
            sb.append((i + 1)).append(". ").append(attraction.name());
            if (attraction.ticketPrice() != null && attraction.ticketPrice() > 0) {
                sb.append(" (¥").append(String.format("%.0f", attraction.ticketPrice())).append(")");
            } else {
                sb.append(" (免费)");
            }
            sb.append("\n");
        }
        
        sb.append("\n💡 旅行贴士：\n");
        sb.append("• 建议游玩时间：").append(getSuggestedDays(city)).append("天\n");
        sb.append("• 最佳季节：根据具体景点而定\n");
        sb.append("• 交通方式：建议使用地铁/公交/打车\n");
        
        return sb.toString();
    }

    /**
     * 格式化景点信息
     */
    public String formatAttractionInfo(TouristAttractionKnowledgeBase.AttractionInfo attraction) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏛️ 【").append(attraction.name()).append("】\n");
        sb.append("📍 位置：").append(attraction.province()).append(" ").append(attraction.city()).append("\n");
        sb.append("⭐ 等级：").append(attraction.level()).append("级景区\n");
        
        if (attraction.ticketPrice() != null && attraction.ticketPrice() > 0) {
            sb.append("💰 门票：").append(String.format("%.0f", attraction.ticketPrice())).append("元\n");
        } else {
            sb.append("💰 门票：免费\n");
        }
        
        sb.append("⏰ 开放：").append(attraction.openTime()).append("\n");
        
        if (attraction.suggestDuration() != null) {
            sb.append("⏱️ 建议游玩：").append(attraction.suggestDuration()).append("分钟\n");
        }
        
        sb.append("🏷️ 标签：").append(String.join("、", attraction.tags())).append("\n");
        sb.append("📝 简介：").append(attraction.description()).append("\n");
        
        if (attraction.highlights() != null && !attraction.highlights().isEmpty()) {
            sb.append("✨ 亮点：").append(String.join("、", attraction.highlights())).append("\n");
        }
        
        return sb.toString();
    }

    /**
     * 获取统计信息
     */
    public String getStatistics() {
        return importService.getStatistics();
    }

    /**
     * 转换实体为 AttracionInfo
     */
    private TouristAttractionKnowledgeBase.AttractionInfo convertToAttractionInfo(TouristAttraction entity) {
        return new TouristAttractionKnowledgeBase.AttractionInfo(
                entity.getName(),
                entity.getCity(),
                entity.getProvince(),
                entity.getDescription(),
                entity.getLevel(),
                entity.getTicketPrice(),
                entity.getOpenTime(),
                entity.getSuggestDuration(),
                entity.getTags() != null ? entity.getTags() : List.of(),
                entity.getHighlights() != null ? entity.getHighlights() : List.of()
        );
    }

    /**
     * 获取建议游玩天数
     */
    private int getSuggestedDays(String city) {
        var attractions = getAttractionsByCity(city);
        if (attractions.size() <= 3) {
            return 1;
        } else if (attractions.size() <= 6) {
            return 2;
        } else {
            return 3;
        }
    }
}
