/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tokenizer;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.corpus.tag.Nature;
import com.hankcs.hanlp.seg.common.Term;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// ⭐ 直接导入 IKAnalyzer 类型，替代反射调用
import org.wltea.analyzer.core.IKSegmenter;
import org.wltea.analyzer.core.Lexeme;

/**
 * 中文分词器工具类
 * <p>
 * 基于 IKAnalyzer 实现，提供：
 * - 智能中文分词
 * - 停用词过滤
 * - 关键词匹配增强
 * - 结果缓存
 * 
 * @since 1.0.0
 */
@Slf4j
@Component
public class ChineseTokenizer {
    
    /** 停用词集合 */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
            "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去",
            "你", "会", "着", "没有", "看", "好", "自己", "这", "那", "他",
            "她", "它", "们", "来", "个", "吗", "呢", "吧", "啊", "哦",
            "嗯", "呀", "哈", "嘿", "哎", "喂", "诶", "哇", "呃",
            "怎么", "什么", "哪", "这个", "那个", "这些", "那些", "一下",
            "一点", "一些", "这样", "那样", "如何", "为啥"
    );
    
    /** IKAnalyzer 是否可用（始终为 true，因 pom.xml 已声明依赖） */
    private static final boolean IK_AVAILABLE = true;
    
    /** 分词结果缓存（防止重复分词） */
    private final Map<String, Set<String>> cache = new ConcurrentHashMap<>();
    
    /** 最大缓存大小 */
    private static final int MAX_CACHE_SIZE = 10000;

    /** HanLP 是否可用 */
    private boolean hanlpAvailable = false;
    
    /** 同义词词典：标准词 -> 同义词集合 */
    private final Map<String, Set<String>> synonymDictionary = new ConcurrentHashMap<>();
    
    /** 反向同义词词典：同义词 -> 标准词 */
    private final Map<String, String> reverseSynonymMap = new ConcurrentHashMap<>();
    
    /** 同义词词典路径 */
    private static final String SYNONYM_DICT_PATH = "hanlp_synonym.txt";
    
    /** HanLP 数据文件路径配置 */
    @Value("${hanlp.data-path:}")
    private String hanlpDataPath;
    
    @PostConstruct
    public void init() {
        // IKAnalyzer 已通过 pom.xml 声明依赖，直接可用
        log.info("[ChineseTokenizer] IKAnalyzer 8.4.0 就绪（直接依赖模式）");
        
        // 加载同义词词典（独立于 HanLP）
        loadSynonymDictionary();
        
        // 初始化 HanLP（词性标注）- 延迟初始化，失败时标记不可用
        try {
            // 配置 HanLP 数据文件路径
            if (hanlpDataPath != null && !hanlpDataPath.isBlank()) {
                // 设置系统属性，HanLP 会自动读取
                System.setProperty("hanlp.home", hanlpDataPath);
                log.info("[ChineseTokenizer] HanLP 数据路径配置: {}", hanlpDataPath);
            } else {
                log.info("[ChineseTokenizer] HanLP 使用默认数据路径（未配置 hanlp.data-path）");
            }
            
            // 测试 HanLP 是否可用（首次调用会初始化词典）
            List<Term> test = HanLP.segment("测试");
            hanlpAvailable = true;
            log.info("[ChineseTokenizer] HanLP 初始化成功（词性标注可用）");
        } catch (Throwable e) {
            hanlpAvailable = false;
            log.warn("[ChineseTokenizer] HanLP 不可用，词性标注功能将受限: {}", e.getMessage());
            log.warn("[ChineseTokenizer] 提示：如需启用词性标注，请下载 HanLP 数据文件并配置 hanlp.data-path");
            log.warn("[ChineseTokenizer] 下载地址: https://github.com/hankcs/HanLP/releases");
        }
    }
    
    /**
     * 加载同义词词典
     */
    private void loadSynonymDictionary() {
        try {
            ClassPathResource resource = new ClassPathResource(SYNONYM_DICT_PATH);
            if (!resource.exists()) {
                log.warn("[ChineseTokenizer] 同义词词典文件不存在: {}", SYNONYM_DICT_PATH);
                return;
            }
            
            try (InputStream is = resource.getInputStream();
                 BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
                
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    // 跳过注释和空行
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    
                    String[] parts = line.split("\t");
                    if (parts.length >= 2) {
                        String standardWord = parts[0].trim().toLowerCase();
                        String[] synonyms = parts[1].split(",");
                        
                        Set<String> synonymSet = new HashSet<>();
                        for (String syn : synonyms) {
                            String trimmed = syn.trim().toLowerCase();
                            if (!trimmed.isEmpty()) {
                                synonymSet.add(trimmed);
                                // 构建反向映射
                                reverseSynonymMap.put(trimmed, standardWord);
                            }
                        }
                        
                        synonymDictionary.put(standardWord, synonymSet);
                        count++;
                    }
                }
                
                log.info("[ChineseTokenizer] 同义词词典加载成功，共 {} 条记录", count);
            }
        } catch (Exception e) {
            log.warn("[ChineseTokenizer] 同义词词典加载失败: {}", e.getMessage());
        }
    }
    
    /**
     * 判断 IKAnalyzer 是否可用（始终为 true）
     */
    public boolean isIkAvailable() {
        return IK_AVAILABLE;
    }
    
    /**
     * 对输入文本进行分词
     * <p>
     * 使用 IKAnalyzer 智能分词，自动过滤停用词
     *
     * @param text 输入文本
     * @return 分词结果集合（小写）
     */
    public Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptySet();
        }
        
        String key = text.trim();
        
        // 检查缓存
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        
        Set<String> result;
        if (IK_AVAILABLE) {
            result = tokenizeWithIK(key);
        } else {
            result = tokenizeBasic(key);
        }
        
        // 缓存结果
        if (cache.size() < MAX_CACHE_SIZE) {
            cache.put(key, result);
        }
        
        return result;
    }
    
    /**
     * 使用 IKAnalyzer 分词（直接调用模式，替代旧反射调用）
     */
    private Set<String> tokenizeWithIK(String text) {
        Set<String> words = new HashSet<>();
        
        try {
            StringReader reader = new StringReader(text);
            IKSegmenter segmenter = new IKSegmenter(reader, true);
            
            // 遍历分词结果
            Lexeme lexeme;
            while ((lexeme = segmenter.next()) != null) {
                String word = lexeme.getLexemeText();
                String lower = word.toLowerCase();
                
                // 过滤停用词和单字符
                if (!STOP_WORDS.contains(lower) && lower.length() > 1) {
                    words.add(lower);
                }
            }
            
        } catch (Exception e) {
            log.warn("[ChineseTokenizer] IK 分词失败，使用基础分词: {}", e.getMessage());
            return tokenizeBasic(text);
        }
        
        return words;
    }
    
    /**
     * 基础分词（按字符/bigram）
     */
    private Set<String> tokenizeBasic(String text) {
        Set<String> words = new HashSet<>();
        String lower = text.toLowerCase();
        
        // 过滤停用词
        for (String stopWord : STOP_WORDS) {
            lower = lower.replace(stopWord, " ");
        }
        
        // 按空格拆分
        String[] parts = lower.split("\\s+");
        for (String part : parts) {
            if (part.length() > 1) {
                words.add(part);
            }
        }
        
        // Bigram：相邻两个字组成词
        char[] chars = lower.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (!STOP_WORDS.contains(String.valueOf(chars[i])) && 
                !STOP_WORDS.contains(String.valueOf(chars[i + 1]))) {
                words.add(String.valueOf(chars[i]) + chars[i + 1]);
            }
        }
        
        return words;
    }
    
    /**
     * 检查文本是否包含任意一个关键词
     * <p>
     * 匹配逻辑：
     * 1. 优先完整匹配（子串匹配）
     * 2. 分词后匹配（更智能）
     *
     * @param text     输入文本
     * @param keywords 关键词集合
     * @return 是否匹配
     */
    public boolean containsAnyKeyword(String text, Set<String> keywords) {
        if (text == null || keywords == null || keywords.isEmpty()) {
            return false;
        }
        
        String lowerText = text.toLowerCase();
        
        // 1. 完整匹配
        for (String kw : keywords) {
            if (lowerText.contains(kw.toLowerCase())) {
                return true;
            }
        }
        
        // 2. 分词匹配
        Set<String> tokens = tokenize(text);
        for (String kw : keywords) {
            if (tokens.contains(kw.toLowerCase())) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 统计文本中匹配的关键词数量
     * <p>
     * 重复关键词只计一次
     *
     * @param text     输入文本
     * @param keywords 关键词集合
     * @return 匹配的关键词数量
     */
    public int countKeywordMatches(String text, Set<String> keywords) {
        if (text == null || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        String lowerText = text.toLowerCase();
        Set<String> tokens = tokenize(text);
        
        for (String kw : keywords) {
            String lowerKw = kw.toLowerCase();
            // 完整匹配或分词匹配
            if (lowerText.contains(lowerKw) || tokens.contains(lowerKw)) {
                count++;
            }
        }
        
        return count;
    }
    
    /**
     * 计算文本与关键词的相似度
     * <p>
     * 相似度 = 匹配关键词数 / 关键词总数
     *
     * @param text     输入文本
     * @param keywords 关键词集合
     * @return 相似度（0.0 ~ 1.0）
     */
    public double calculateSimilarity(String text, Set<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return 0.0;
        }
        
        int matched = countKeywordMatches(text, keywords);
        return (double) matched / keywords.size();
    }
    
    /**
     * 清除分词缓存
     */
    public void clearCache() {
        cache.clear();
        log.info("[ChineseTokenizer] 缓存已清除");
    }
    
    /**
     * 获取当前缓存大小
     */
    public int getCacheSize() {
        return cache.size();
    }
    
    // ==================== HanLP 词性标注 ====================
    
    /**
     * 判断 HanLP 是否可用
     */
    public boolean isHanlpAvailable() {
        return hanlpAvailable;
    }
    
    /**
     * 对文本进行词性标注
     *
     * @param text 输入文本
     * @return 词性标注结果列表
     */
    public List<TaggedWord> posTag(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        
        if (!hanlpAvailable) {
            // 降级：使用基础分词，词性设为 null
            Set<String> tokens = tokenize(text);
            return tokens.stream()
                    .map(token -> new TaggedWord(token, null))
                    .collect(Collectors.toList());
        }
        
        try {
            List<Term> terms = HanLP.segment(text);
            return terms.stream()
                    .map(term -> new TaggedWord(term.word, term.nature.toString()))
                    .collect(Collectors.toList());
        } catch (Throwable e) {
            // 使用基础分词降级
            Set<String> tokens = tokenize(text);
            return tokens.stream()
                    .map(token -> new TaggedWord(token, null))
                    .collect(Collectors.toList());
        }
    }
    
    /**
     * 提取文本中的实词（名词、动词、形容词）
     *
     * @param text 输入文本
     * @return 实词列表
     */
    public List<TaggedWord> extractContentWords(String text) {
        return posTag(text).stream()
                .filter(TaggedWord::isContentWord)
                .collect(Collectors.toList());
    }
    
    /**
     * 提取名词
     *
     * @param text 输入文本
     * @return 名词列表
     */
    public List<String> extractNouns(String text) {
        return posTag(text).stream()
                .filter(TaggedWord::isNoun)
                .map(TaggedWord::getWord)
                .collect(Collectors.toList());
    }
    
    /**
     * 提取动词
     *
     * @param text 输入文本
     * @return 动词列表
     */
    public List<String> extractVerbs(String text) {
        return posTag(text).stream()
                .filter(TaggedWord::isVerb)
                .map(TaggedWord::getWord)
                .collect(Collectors.toList());
    }
    
    /**
     * 提取形容词
     *
     * @param text 输入文本
     * @return 形容词列表
     */
    public List<String> extractAdjectives(String text) {
        return posTag(text).stream()
                .filter(TaggedWord::isAdjective)
                .map(TaggedWord::getWord)
                .collect(Collectors.toList());
    }
    
    // ==================== 同义词扩展 ====================
    
    /**
     * 将同义词扩展为标准词集合
     * <p>
     * 例如：输入 "溜达" → 输出 {"散步", "溜达"}
     *       输入 "散步" → 输出 {"散步", "溜达", "转悠", ...}
     *
     * @param word 输入词
     * @return 标准词 + 所有同义词
     */
    public Set<String> expandToStandardForm(String word) {
        if (word == null || word.isBlank()) {
            return Collections.emptySet();
        }
        
        String lower = word.toLowerCase();
        Set<String> result = new HashSet<>();
        result.add(lower);
        
        // 查找标准词
        String standard = reverseSynonymMap.get(lower);
        if (standard != null) {
            result.add(standard);
            Set<String> synonyms = synonymDictionary.get(standard);
            if (synonyms != null) {
                result.addAll(synonyms);
            }
        } else if (synonymDictionary.containsKey(lower)) {
            // 输入本身就是标准词
            result.addAll(synonymDictionary.get(lower));
        }
        
        return result;
    }
    
    /**
     * 检查文本是否包含任意一个关键词（支持同义词扩展）
     *
     * @param text            输入文本
     * @param keywords        关键词集合（标准词）
     * @param enableSynonym   是否启用同义词匹配
     * @return 是否匹配
     */
    public boolean containsAnyKeyword(String text, Set<String> keywords, boolean enableSynonym) {
        if (text == null || keywords == null || keywords.isEmpty()) {
            return false;
        }
        
        String lowerText = text.toLowerCase();
        
        // 1. 完整匹配
        for (String kw : keywords) {
            if (lowerText.contains(kw.toLowerCase())) {
                return true;
            }
        }
        
        // 2. 分词匹配
        Set<String> tokens = tokenize(text);
        for (String kw : keywords) {
            if (tokens.contains(kw.toLowerCase())) {
                return true;
            }
        }
        
        // 3. 同义词匹配（可选）
        if (enableSynonym) {
            Set<String> allExpandedKeywords = new HashSet<>();
            for (String kw : keywords) {
                allExpandedKeywords.addAll(expandToStandardForm(kw));
            }
            
            for (String expanded : allExpandedKeywords) {
                if (lowerText.contains(expanded) || tokens.contains(expanded)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 统计匹配的关键词数量（支持同义词扩展）
     *
     * @param text            输入文本
     * @param keywords        关键词集合（标准词）
     * @param enableSynonym   是否启用同义词匹配
     * @return 匹配的关键词数量
     */
    public int countKeywordMatches(String text, Set<String> keywords, boolean enableSynonym) {
        if (text == null || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        String lowerText = text.toLowerCase();
        Set<String> tokens = tokenize(text);
        
        for (String kw : keywords) {
            String lowerKw = kw.toLowerCase();
            boolean matched = lowerText.contains(lowerKw) || tokens.contains(lowerKw);
            
            // 同义词匹配
            if (!matched && enableSynonym) {
                Set<String> expanded = expandToStandardForm(lowerKw);
                for (String exp : expanded) {
                    if (lowerText.contains(exp) || tokens.contains(exp)) {
                        matched = true;
                        break;
                    }
                }
            }
            
            if (matched) {
                count++;
            }
        }
        
        return count;
    }
    
    /**
     * 基于词性标注的意图识别
     * <p>
     * 返回识别的意图标签
     *
     * @param text     输入文本
     * @param patterns 意图模式定义
     * @return 匹配的意图标签，未匹配返回 null
     */
    public String recognizeIntentByPos(String text, Map<String, IntentPattern> patterns) {
        if (text == null || patterns == null || patterns.isEmpty()) {
            return null;
        }
        
        List<TaggedWord> taggedWords = posTag(text);
        String lowerText = text.toLowerCase();
        
        for (Map.Entry<String, IntentPattern> entry : patterns.entrySet()) {
            String intent = entry.getKey();
            IntentPattern pattern = entry.getValue();
            
            // 检查核心词
            boolean coreMatch = false;
            if (pattern.coreWords != null && !pattern.coreWords.isEmpty()) {
                for (String core : pattern.coreWords) {
                    if (lowerText.contains(core.toLowerCase())) {
                        coreMatch = true;
                        break;
                    }
                }
            } else {
                coreMatch = true; // 无核心词要求
            }
            
            if (!coreMatch) continue;
            
            // 检查词性要求
            boolean posMatch = true;
            if (pattern.requiredPos != null && !pattern.requiredPos.isEmpty()) {
                Set<String> posSet = pattern.requiredPos;
                boolean hasRequiredPos = taggedWords.stream()
                        .anyMatch(tw -> tw.getPos() != null && posSet.contains(tw.getPos()));
                posMatch = hasRequiredPos;
            }
            
            // 检查排除词
            boolean excludeMatch = false;
            if (pattern.excludeWords != null && !pattern.excludeWords.isEmpty()) {
                for (String exclude : pattern.excludeWords) {
                    if (lowerText.contains(exclude.toLowerCase())) {
                        excludeMatch = true;
                        break;
                    }
                }
            }
            
            if (coreMatch && posMatch && !excludeMatch) {
                return intent;
            }
        }
        
        return null;
    }
    
    /**
     * 获取同义词词典大小
     */
    public int getSynonymDictionarySize() {
        return synonymDictionary.size();
    }
    
    // ==================== 内部类：意图模式 ====================
    
    /**
     * 意图模式定义
     * <p>
     * 用于基于词性标注的精细意图识别
     */
    @Data
    public static class IntentPattern {
        /**
         * 核心关键词（必须包含）
         */
        private Set<String> coreWords;
        
        /**
         * 必需词性（至少包含一种）
         */
        private Set<String> requiredPos;
        
        /**
         * 排除词（包含则不匹配）
         */
        private Set<String> excludeWords;
        
        public IntentPattern() {}
        
        public IntentPattern(Set<String> coreWords) {
            this.coreWords = coreWords;
        }
        
        public IntentPattern(Set<String> coreWords, Set<String> requiredPos) {
            this.coreWords = coreWords;
            this.requiredPos = requiredPos;
        }
        
        public static IntentPattern of(String... coreWords) {
            IntentPattern p = new IntentPattern();
            p.setCoreWords(Set.of(coreWords));
            return p;
        }
        
        public IntentPattern withPos(String... pos) {
            this.requiredPos = Set.of(pos);
            return this;
        }
        
        public IntentPattern exclude(String... words) {
            this.excludeWords = Set.of(words);
            return this;
        }
    }
}
