/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.cache;

import com.example.smartassistant.consumer.entity.UserProfile;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 答案个性化服务 - 避免缓存答案重复感
 * 
 * <p>功能：</p>
 * <ul>
 *     <li>对缓存答案进行轻量级重述，避免完全相同的回复</li>
 *     <li>根据用户画像调整语气和侧重点</li>
 *     <li>保持核心信息不变，但表达方式多样化</li>
 * </ul>
 * 
 * <p>使用场景：</p>
 * <ul>
 *     <li>L2 语义缓存命中时，对答案进行重述</li>
 *     <li>同一用户多次命中相同缓存时，提供不同表达</li>
 * </ul>
 */
@Slf4j
@Service
public class AnswerPersonalizationService {
    
    private final ChatClient chatClient;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;  // ⭐ Micrometer 指标
    
    // 配置参数
    private static final Duration HIT_COUNT_TTL = Duration.ofHours(24);
    private static final Duration PROFILE_CACHE_TTL = Duration.ofHours(1);
    private static final int QUICK_REWRITE_THRESHOLD = 1;
    private static final int LLM_REWRITE_THRESHOLD = 3;
    
    // ⭐ Micrometer 指标
    private final Counter preloadTotalCounter;
    private final Counter preloadSuccessCounter;
    private final Counter preloadFailureCounter;
    private final Counter preloadSkippedCounter;
    private final Timer preloadTimer;
    
    public AnswerPersonalizationService(ChatClient.Builder chatClientBuilder,
                                        ReactiveStringRedisTemplate redisTemplate,
                                        UserProfileService userProfileService,
                                        ObjectMapper objectMapper,
                                        MeterRegistry meterRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.redisTemplate = redisTemplate;
        this.userProfileService = userProfileService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;  // ⭐
        
        // ⭐ 初始化 Micrometer 指标
        this.preloadTotalCounter = Counter.builder("cache.preload.total")
            .description("用户画像缓存预热总次数")
            .register(meterRegistry);
        
        this.preloadSuccessCounter = Counter.builder("cache.preload.success")
            .description("用户画像缓存预热成功次数")
            .register(meterRegistry);
        
        this.preloadFailureCounter = Counter.builder("cache.preload.failure")
            .description("用户画像缓存预热失败次数")
            .register(meterRegistry);
        
        this.preloadSkippedCounter = Counter.builder("cache.preload.skipped")
            .description("用户画像缓存预热跳过次数（缓存已存在）")
            .register(meterRegistry);
        
        this.preloadTimer = Timer.builder("cache.preload.duration")
            .description("用户画像缓存预热耗时")
            .register(meterRegistry);
    }
    
    /**
     * 个性化重述答案（方案B+C：混合策略 + 用户画像）
     * 
     * <p>策略：</p>
     * <ul>
     *     <li>第1次命中：快速重述 + 画像前缀（< 10ms）</li>
     *     <li>第2-3次命中：LLM 重述 + 画像上下文（600ms）</li>
     *     <li>第4次及以上：返回原始答案 + 个性化引导语</li>
     * </ul>
     */
    public Mono<String> personalizeAnswer(String originalAnswer, String question, String userId) {
        String hitCountKey = "personalization:hit_count:" + hash(question + ":" + userId);
        
        // 获取用户画像（异步）
        Mono<UserProfile> profileMono = getUserProfile(userId);
        
        // 获取并增加命中次数
        return redisTemplate.opsForValue().increment(hitCountKey)
            .flatMap(hitCount -> {
                if (hitCount == 1) {
                    redisTemplate.expire(hitCountKey, HIT_COUNT_TTL).subscribe();
                }
                
                log.debug("[AnswerPersonalization] 命中次数: {}, userId: {}", hitCount, userId);
                
                // 根据命中次数选择策略
                if (hitCount <= QUICK_REWRITE_THRESHOLD) {
                    // 策略1：快速重述 + 画像前缀
                    return profileMono
                        .map(profile -> quickRewriteWithProfile(originalAnswer, profile))
                        .defaultIfEmpty(quickRewrite(originalAnswer));
                    
                } else if (hitCount <= LLM_REWRITE_THRESHOLD) {
                    // 策略2：LLM 重述 + 画像上下文
                    return profileMono
                        .flatMap(profile -> llmRewriteWithProfile(originalAnswer, question, profile))
                        .switchIfEmpty(llmRewrite(originalAnswer, question));
                    
                } else {
                    // 策略3：个性化引导语
                    return profileMono
                        .map(profile -> addPersonalizedGuidance(originalAnswer, profile))
                        .defaultIfEmpty(addGuidance(originalAnswer));
                }
            })
            .onErrorResume(e -> {
                log.error("[AnswerPersonalization] 个性化失败，返回原始答案: {}", e.getMessage());
                return Mono.just(originalAnswer);
            });
    }
    
    /**
     * 获取用户画像（带 Redis 缓存）
     * 
     * <p>缓存策略：</p>
     * <ul>
     *     <li>L1: Redis 缓存（TTL=1小时）</li>
     *     <li>L2: 文件存储的用户画像 data/users/{userId}/preferences.json</li>
     * </ul>
     */
    private Mono<UserProfile> getUserProfile(String userId) {
        if (userId == null || "anonymous".equals(userId)) {
            return Mono.empty();
        }
        
        try {
            Long uid = Long.parseLong(userId);
            String cacheKey = "user_profile:" + uid;
            
            // L1: 先查 Redis 缓存
            return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(cachedJson -> {
                    log.debug("[UserProfileCache] ✅ L1 命中: userId={}", uid);
                    try {
                        UserProfile profile = objectMapper.readValue(cachedJson, UserProfile.class);
                        return Mono.just(profile);
                    } catch (Exception e) {
                        log.warn("[UserProfileCache] 反序列化失败，降级到 L2: {}", e.getMessage());
                        return Mono.empty();
                    }
                })
                .switchIfEmpty(
                    // L1 未命中，查 L2: 文件存储的用户画像
                    Mono.fromCallable(() -> userProfileService.getProfile(uid))
                        .doOnNext(profile -> {
                            if (profile != null) {
                                // 存入 L1 缓存
                                try {
                                    String json = objectMapper.writeValueAsString(profile);
                                    redisTemplate.opsForValue()
                                        .set(cacheKey, json, PROFILE_CACHE_TTL)
                                        .subscribe();
                                    log.debug("[UserProfileCache] 📝 已缓存到 L1: userId={}", uid);
                                } catch (Exception e) {
                                    log.warn("[UserProfileCache] 序列化失败: {}", e.getMessage());
                                }
                            }
                        })
                        .onErrorResume(e -> {
                            log.error("[UserProfileCache] L2 查询失败: {}", e.getMessage());
                            return Mono.empty();
                        })
                );
                
        } catch (NumberFormatException e) {
            return Mono.empty();
        }
    }
    
    /**
     * 清除用户画像缓存（当画像更新时调用）
     * 
     * @param userId 用户ID
     */
    public void invalidateProfileCache(String userId) {
        if (userId == null || "anonymous".equals(userId)) {
            return;
        }
        
        try {
            Long uid = Long.parseLong(userId);
            String cacheKey = "user_profile:" + uid;
            
            redisTemplate.delete(cacheKey)
                .subscribe(
                    count -> {
                        if (count > 0) {
                            log.info("[UserProfileCache] 🗑️ 已清除缓存: userId={}, count={}", uid, count);
                        }
                    },
                    e -> {
                        log.warn("[UserProfileCache] 清除缓存失败: {}", e.getMessage());
                    }
                );
                
        } catch (NumberFormatException e) {
            log.warn("[UserProfileCache] 无效的用户ID: {}", userId);
        }
    }
    
    /**
     * 预热用户画像缓存（用户登录时调用）
     * 
     * <p>优势：</p>
     * <ul>
     *     <li>首次请求即可命中 L1 缓存（0额外延迟）</li>
     *     <li>提升新用户的第一印象</li>
     *     <li>异步执行，不阻塞登录流程</li>
     * </ul>
     * 
     * @param userId 用户ID
     * @return Mono<Void> 异步完成信号
     */
    public Mono<Void> preloadProfile(String userId) {
        if (userId == null || "anonymous".equals(userId)) {
            return Mono.empty();
        }
        
        // ⭐ 记录总次数
        preloadTotalCounter.increment();
        
        try {
            Long uid = Long.parseLong(userId);
            String cacheKey = "user_profile:" + uid;
            
            // 开始计时
            Timer.Sample sample = Timer.start(meterRegistry);
            
            // 先检查是否已有缓存
            return redisTemplate.hasKey(cacheKey)
                .flatMap(exists -> {
                    if (exists) {
                        log.debug("[UserProfileCache] ⏭️ 缓存已存在，跳过预热: userId={}", uid);
                        
                        // ⭐ 记录跳过次数
                        preloadSkippedCounter.increment();
                        sample.stop(preloadTimer);
                        
                        return Mono.empty();
                    }
                    
                    // 从文件加载用户画像并缓存
                    return Mono.fromCallable(() -> userProfileService.getProfile(uid))
                        .doOnNext(profile -> {
                            if (profile != null) {
                                try {
                                    String json = objectMapper.writeValueAsString(profile);
                                    redisTemplate.opsForValue()
                                        .set(cacheKey, json, PROFILE_CACHE_TTL)
                                        .subscribe();
                                    
                                    // ⭐ 记录成功次数
                                    preloadSuccessCounter.increment();
                                    sample.stop(preloadTimer);
                                    
                                    log.info("[UserProfileCache] 🔥 预热成功: userId={}, budget={}, diet={}", 
                                            uid, 
                                            profile.getBudgetRange(),
                                            profile.getDietaryRestrictionsArray() != null ? profile.getDietaryRestrictionsArray().length : 0);
                                } catch (Exception e) {
                                    // ⭐ 记录失败次数
                                    preloadFailureCounter.increment();
                                    sample.stop(preloadTimer);
                                    
                                    log.error("[UserProfileCache] 预热序列化失败: {}", e.getMessage());
                                }
                            } else {
                                log.debug("[UserProfileCache] 用户画像不存在: userId={}", uid);
                                sample.stop(preloadTimer);
                            }
                        })
                        .onErrorResume(e -> {
                            // ⭐ 记录失败次数
                            preloadFailureCounter.increment();
                            sample.stop(preloadTimer);
                            
                            log.error("[UserProfileCache] 预热失败: {}", e.getMessage());
                            return Mono.empty();
                        })
                        .then();
                });
                
        } catch (NumberFormatException e) {
            log.warn("[UserProfileCache] 无效的用户ID: {}", userId);
            return Mono.empty();
        }
    }
    
    /**
     * 快速重述 + 用户画像前缀
     */
    private String quickRewriteWithProfile(String originalAnswer, UserProfile profile) {
        String prefix = buildProfilePrefix(profile);
        String rewritten = quickRewrite(originalAnswer);
        
        if (prefix != null && !prefix.isEmpty()) {
            return prefix + "\n\n" + rewritten;
        }
        return rewritten;
    }
    
    /**
     * LLM 重述 + 用户画像上下文
     */
    private Mono<String> llmRewriteWithProfile(String originalAnswer, String question, UserProfile profile) {
        return Mono.fromCallable(() -> {
            try {
                String prompt = buildPersonalizedPrompt(originalAnswer, question, profile);
                
                String rewritten = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
                
                log.debug("[AnswerPersonalization] LLM 个性化重述完成");
                return rewritten;
                
            } catch (Exception e) {
                log.error("[AnswerPersonalization] LLM 个性化重述失败: {}", e.getMessage());
                return quickRewriteWithProfile(originalAnswer, profile);
            }
        });
    }
    
    /**
     * 构建用户画像前缀
     */
    private String buildProfilePrefix(UserProfile profile) {
        if (profile == null) {
            return null;
        }
        
        List<String> prefixes = new java.util.ArrayList<>();
        
        // 根据饮食限制
        String[] dietArr = profile.getDietaryRestrictionsArray();
        if (dietArr != null && dietArr.length > 0) {
            String restrictions = String.join("、", dietArr);
            prefixes.add("根据您的饮食限制（" + restrictions + "），");
        }
        
        // 根据预算
        if (profile.getBudgetRange() != null) {
            switch (profile.getBudgetRange()) {
                case "low":
                    prefixes.add("为您推荐性价比高的选择，");
                    break;
                case "medium":
                    prefixes.add("为您精选中等价位的选择，");
                    break;
                case "high":
                    prefixes.add("为您推荐高品质选择，");
                    break;
            }
        }
        
        // 根据偏好
        String[] foodArr = profile.getFoodPreferencesArray();
        if (foodArr != null && foodArr.length > 0) {
            String prefs = String.join("、", Arrays.copyOfRange(foodArr, 0, Math.min(3, foodArr.length)));
            prefixes.add("结合您的口味偏好（" + prefs + "），");
        }
        
        return prefixes.isEmpty() ? null : String.join("", prefixes);
    }
    
    /**
     * 构建个性化 Prompt
     */
    private String buildPersonalizedPrompt(String originalAnswer, String question, UserProfile profile) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个智能助手的回答优化专家。请根据用户画像对以下答案进行个性化重述。\n\n");
        
        // 添加用户画像信息
        if (profile != null) {
            prompt.append("【用户画像】\n");
            
            String[] dietRes = profile.getDietaryRestrictionsArray();
            if (dietRes != null && dietRes.length > 0) {
                prompt.append("- 饮食限制: ").append(String.join("、", dietRes)).append("\n");
            }
            
            if (profile.getBudgetRange() != null) {
                String budgetText = switch (profile.getBudgetRange()) {
                    case "low" -> "低预算（注重性价比）";
                    case "medium" -> "中等预算";
                    case "high" -> "高预算（注重品质）";
                    default -> profile.getBudgetRange();
                };
                prompt.append("- 预算范围: ").append(budgetText).append("\n");
            }
            
            String[] foodPrefs = profile.getFoodPreferencesArray();
            if (foodPrefs != null && foodPrefs.length > 0) {
                prompt.append("- 口味偏好: ").append(String.join("、", foodPrefs)).append("\n");
            }
            
            String[] travelPrefs = profile.getTravelPreferencesArray();
            if (travelPrefs != null && travelPrefs.length > 0) {
                prompt.append("- 旅行偏好: ").append(String.join("、", travelPrefs)).append("\n");
            }
            
            prompt.append("\n");
        }
        
        prompt.append("""
            【重述要求】
            1. 保持核心信息和事实准确性不变
            2. 根据用户画像调整侧重点和语气
            3. 如果用户有饮食限制，强调符合条件的选项
            4. 如果用户有预算限制，突出性价比或品质
            5. 语气友好、自然，像真人对话
            6. 长度保持在原文的 90%%-110%% 之间
            
            用户问题：%s
            
            原始答案：
            %s
            
            请输出个性化重述后的答案（只输出答案本身，不要加任何说明）：
            """.formatted(question, originalAnswer));
        
        return prompt.toString();
    }
    
    /**
     * 个性化引导语
     */
    private String addPersonalizedGuidance(String originalAnswer, UserProfile profile) {
        if (profile == null) {
            return addGuidance(originalAnswer);
        }
        
        String guidance = switch (profile.getBudgetRange() != null ? profile.getBudgetRange() : "medium") {
            case "low" -> "\n\n💡 以上是高性价比推荐。如需更详细的预算规划，可以告诉我您的具体预算范围！";
            case "high" -> "\n\n💡 以上是高品质推荐。如需预订服务或更多细节，请告诉我您的具体需求！";
            default -> "\n\n💡 以上是通用建议。如需个性化推荐，可以告诉我您的偏好（如预算、口味等）。";
        };
        
        // 如果有饮食限制，额外提示
        String[] dietRestrictions = profile.getDietaryRestrictionsArray();
        if (dietRestrictions != null && dietRestrictions.length > 0) {
            guidance += "\n💡 已考虑您的饮食限制（" + String.join("、", dietRestrictions) + "）。";
        }
        
        return originalAnswer + guidance;
    }
    
    /**
     * LLM 重述（异步）
     */
    private Mono<String> llmRewrite(String originalAnswer, String question) {
        return Mono.fromCallable(() -> {
            try {
                String prompt = buildPersonalizationPrompt(originalAnswer, question);
                
                String rewritten = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
                
                log.debug("[AnswerPersonalization] LLM 重述完成: originalLength={}, newLength={}", 
                        originalAnswer.length(), rewritten.length());
                
                return rewritten;
                
            } catch (Exception e) {
                log.error("[AnswerPersonalization] LLM 重述失败，降级为快速重述: {}", e.getMessage());
                // 降级：快速重述
                return quickRewrite(originalAnswer);
            }
        });
    }
    
    /**
     * 构建重述 Prompt
     */
    private String buildPersonalizationPrompt(String originalAnswer, String question) {
        return String.format("""
            你是一个智能助手的回答优化专家。请对以下答案进行自然重述，要求：
            
            1. 保持核心信息和事实准确性不变
            2. 改变表达方式和句式结构
            3. 语气友好、自然，像真人对话
            4. 可以适当调整段落顺序或增减过渡句
            5. 不要添加原文中没有的新信息
            6. 长度保持在原文的 90%%-110%% 之间
            
            用户问题：%s
            
            原始答案：
            %s
            
            请输出重述后的答案（只输出答案本身，不要加任何说明）：
            """, question, originalAnswer);
    }
    
    /**
     * 快速重述（不使用 LLM，基于规则的简单变换）
     * 
     * @param originalAnswer 原始答案
     * @return 简单变换后的答案
     */
    public String quickRewrite(String originalAnswer) {
        if (originalAnswer == null || originalAnswer.length() < 50) {
            return originalAnswer; // 短答案不处理
        }
        
        String rewritten = originalAnswer;
        
        // 1. 替换常见开头
        rewritten = rewritten.replaceAll("^根据.*?，", "");
        rewritten = rewritten.replaceAll("^总的来说，", "");
        rewritten = rewritten.replaceAll("^首先，", "第一，");
        
        // 2. 添加个性化前缀
        String[] prefixes = {
            "为您推荐：",
            "建议您考虑：",
            "我个人推荐：",
            "不妨试试：",
            "您可以关注："
        };
        
        int prefixIndex = Math.abs(originalAnswer.hashCode()) % prefixes.length;
        rewritten = prefixes[prefixIndex] + rewritten;
        
        log.debug("[AnswerPersonalization] 快速重述完成");
        return rewritten;
    }
    
    /**
     * 添加引导语（第4次及以上命中）
     */
    private String addGuidance(String originalAnswer) {
        String[] guidances = {
            "\n\n💡 这是常见问题的标准答案。如果您需要更详细的解答或有特定需求，请告诉我！",
            "\n\n💡 以上是通用建议。如需个性化推荐，可以告诉我您的偏好（如预算、口味等）。",
            "\n\n💡 这是基础信息。您是否想了解某个方面的更多细节？"
        };
        
        int index = Math.abs(originalAnswer.hashCode()) % guidances.length;
        return originalAnswer + guidances[index];
    }
    
    /**
     * 计算哈希值（用于 Redis Key）
     */
    private String hash(String input) {
        return Integer.toString(Math.abs(input.hashCode()));
    }
}
