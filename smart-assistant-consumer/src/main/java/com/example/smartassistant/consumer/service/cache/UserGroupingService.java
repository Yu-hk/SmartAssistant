/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.cache;

import com.example.smartassistant.consumer.entity.UserProfile;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户画像分组服务
 *
 * <p>功能：</p>
 * <ul>
 *     <li>⭐ 基于用户画像（食物偏好、旅行偏好、预算范围、饮食限制）计算分组 ID</li>
 *     <li>只有相同画像组的用户，在发起语义相同的提问时才会触发语义缓存命中</li>
 *     <li>分组 ID 缓存在内存中（5 分钟），避免频繁查询数据库</li>
 * </ul>
 *
 * <p>分组维度：</p>
 * <ul>
 *     <li><b>food</b>：美食偏好（spicy/light/sweet/other）</li>
 *     <li><b>travel</b>：旅行偏好（nature/culture/relax/other）</li>
 *     <li><b>budget</b>：预算范围（low/medium/high/unknown）</li>
 *     <li><b>diet</b>：饮食限制（vegetarian/halal/none）</li>
 * </ul>
 *
 * <p>分组 ID 示例：</p>
 * <ul>
 *     <li>{@code grp:food=spicy_budget=low_travel=nature_diet=none}</li>
 *     <li>{@code grp:food=light_budget=high_travel=culture_diet=vegetarian}</li>
 * </ul>
 */
@Slf4j
@Service
public class UserGroupingService {

    private final UserProfileService userProfileService;

    // ⭐ 本地分组缓存（userId -> groupId），避免频繁查询 DB
    // key: userId, value: [groupId, expireTimestamp]
    private final ConcurrentHashMap<String, String[]> groupIdCache = new ConcurrentHashMap<>();
    private static final long GROUP_CACHE_TTL_MS = 5 * 60 * 1000L;  // 5 分钟

    public UserGroupingService(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    /**
     * 根据 userId 获取画像分组 ID
     *
     * @param userId 用户 ID（字符串形式，可能是用户名）
     * @return 分组 ID（如 "grp:food=spicy_budget=low_..."），无画像时返回 "grp:default"
     */
    public String getGroupId(String userId) {
        if (userId == null || "anonymous".equalsIgnoreCase(userId)) {
            return "grp:anonymous";
        }

        // 1. 先查本地缓存
        String[] cached = groupIdCache.get(userId);
        if (cached != null && System.currentTimeMillis() < Long.parseLong(cached[1])) {
            log.debug("[UserGroup] 命中本地缓存: userId={}, groupId={}", userId, cached[0]);
            return cached[0];
        }

        // 2. 查询用户画像
        UserProfile profile = null;
        try {
            Long userIdLong = Long.parseLong(userId);
            profile = userProfileService.getProfile(userIdLong);
        } catch (NumberFormatException e) {
            // userId 是字符串（用户名）而非数字 ID，当前暂不支持按用户名查询画像
            log.debug("[UserGroup] userId 非数字，跳过画像查询: userId={}", userId);
        } catch (Exception e) {
            log.warn("[UserGroup] 查询用户画像失败: userId={}, error={}", userId, e.getMessage());
        }

        // 3. 计算分组 ID
        String groupId = computeGroupId(userId, profile);

        // 4. 写入本地缓存
        groupIdCache.put(userId, new String[]{
                groupId,
                String.valueOf(System.currentTimeMillis() + GROUP_CACHE_TTL_MS)
        });

        log.debug("[UserGroup] 计算分组 ID: userId={}, groupId={}", userId, groupId);
        return groupId;
    }

    /**
     * 强制刷新用户分组缓存（用户画像更新后调用）
     */
    public void evictGroupCache(String userId) {
        groupIdCache.remove(userId);
        log.debug("[UserGroup] 已清除分组缓存: userId={}", userId);
    }

    /**
     * 根据 UserProfile 计算分组 ID
     */
    private String computeGroupId(String userId, UserProfile profile) {
        if (profile == null) {
            // 无画像：用 userId 哈希计算一个粗粒度分组（避免无画像用户全部挤进同一组）
            int bucket = Math.abs(userId.hashCode()) % 10;
            return "grp:new_" + bucket;
        }

        // ---- 1. 美食偏好 ----
        String foodGroup = "other";
        String[] foodPrefs = profile.getFoodPreferencesArray();
        if (foodPrefs != null && foodPrefs.length > 0) {
            String joined = String.join(",", foodPrefs).toLowerCase();
            if (joined.contains("辣") || joined.contains("spicy")) {
                foodGroup = "spicy";
            } else if (joined.contains("清淡") || joined.contains("light")) {
                foodGroup = "light";
            } else if (joined.contains("甜") || joined.contains("sweet")) {
                foodGroup = "sweet";
            }
        }

        // ---- 2. 旅行偏好 ----
        String travelGroup = "other";
        String[] travelPrefs = profile.getTravelPreferencesArray();
        if (travelPrefs != null && travelPrefs.length > 0) {
            String joined = String.join(",", travelPrefs).toLowerCase();
            if (joined.contains("自然") || joined.contains("山水") || joined.contains("nature")) {
                travelGroup = "nature";
            } else if (joined.contains("文化") || joined.contains("历史") || joined.contains("culture")) {
                travelGroup = "culture";
            } else if (joined.contains("休闲") || joined.contains("度假") || joined.contains("relax")) {
                travelGroup = "relax";
            }
        }

        // ---- 3. 预算范围 ----
        String budgetGroup = "unknown";
        String budget = profile.getBudgetRange();
        if (budget != null && !budget.isBlank()) {
            String b = budget.toLowerCase();
            if (b.contains("经济") || b.contains("低") || b.contains("low") || b.contains("便宜")) {
                budgetGroup = "low";
            } else if (b.contains("高") || b.contains("高端") || b.contains("high") || b.contains("豪华")) {
                budgetGroup = "high";
            } else {
                budgetGroup = "medium";
            }
        }

        // ---- 4. 饮食限制 ----
        String dietGroup = "none";
        String[] dietRestrictions = profile.getDietaryRestrictionsArray();
        if (dietRestrictions != null && dietRestrictions.length > 0) {
            String joined = String.join(",", dietRestrictions).toLowerCase();
            if (joined.contains("素食") || joined.contains("vegetarian")) {
                dietGroup = "vegetarian";
            } else if (joined.contains("清真") || joined.contains("halal")) {
                dietGroup = "halal";
            }
        }

        // ---- 组合分组 ID ----

        return "grp:food=" + foodGroup
                + "_budget=" + budgetGroup
                + "_travel=" + travelGroup
                + "_diet=" + dietGroup;
    }
}
