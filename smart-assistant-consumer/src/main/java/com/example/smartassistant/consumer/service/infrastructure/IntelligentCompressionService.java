package com.example.smartassistant.consumer.service.infrastructure;

import com.example.smartassistant.consumer.config.ConversationCompressionProperties;
import com.example.smartassistant.consumer.dto.StructuredPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 智能对话压缩服务
 * <p>
 * 采用三级压缩策略，按对话长度自动选择最优方案：
 * <ol>
 *   <li><b>滑动窗口截断</b>：保留最近 N 轮，丢弃更早的（零成本）</li>
 *   <li><b>关键信息提取</b>：对中间轮次做规则化精简，保留实体和意图（低成本）</li>
 *   <li><b>LLM 语义摘要</b>：对最早部分调用 LLM 生成摘要（高保真但有延迟）</li>
 * </ol>
 */
@Service
public class IntelligentCompressionService {

    private static final Logger log = LoggerFactory.getLogger(IntelligentCompressionService.class);

    private final Optional<ChatClient> chatClient;
    private final ConversationCompressionProperties properties;

    // 关键实体词正则：地点、时间、数字、价格、否定等
    private static final Pattern ENTITY_PATTERN = Pattern.compile(
        "(北京|上海|广州|深圳|杭州|成都|重庆|武汉|西安|南京|天津|苏州|长沙|郑州|青岛|大连|宁波|厦门|无锡|福州|济南|" +
        "沈阳|哈尔滨|长春|石家庄|太原|合肥|南昌|昆明|贵阳|南宁|兰州|海口|银川|西宁|拉萨|乌鲁木齐|呼和浩特|" +
                "今天|明天|后天|昨天|下周|下月|明年|上午|下午|晚上|早上|中午|凌晨" +
                "|\\d+块|\\d+元|\\d+折|\\d+%|百分之\\d+|" +
        "不要|不想|不喜欢|讨厌|拒绝|除外|排除|除了|但是|不过|然而|" +
        "推荐|附近|周边|最近|最便宜|最贵|最好|最差|最快|最慢)"
    );

    // 寒暄/冗余内容模式
    private static final Pattern GREETING_PATTERN = Pattern.compile(
            "^(你好|您好|hello|hi|嗨|在吗|在嘛|谢谢|感谢|拜托|麻烦了|请|不客气|再见|拜拜).*",
        Pattern.CASE_INSENSITIVE
    );

    public IntelligentCompressionService(Optional<ChatClient> chatClient,
                                          ConversationCompressionProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    /**
     * 智能压缩历史对话（兼容旧接口）
     *
     * @param history   原始历史对话
     * @param maxRounds 最大保留轮数
     * @return 压缩后的对话
     */
    public List<StructuredPrompt.ConversationMessage> compress(
            List<StructuredPrompt.ConversationMessage> history,
            int maxRounds) {

        if (!properties.isEnabled() || history == null || history.isEmpty()) {
            return history != null ? history : Collections.emptyList();
        }

        int rounds = history.size() / 2;
        if (rounds <= maxRounds) {
            return history;
        }

        return compressWithStrategy(history, maxRounds, properties.getStrategy());
    }

    /**
     * 智能压缩（自动判断是否需要压缩）
     *
     * @param history 原始历史对话
     * @return 压缩后的对话 + 元信息
     */
    public CompressionResult smartCompress(List<StructuredPrompt.ConversationMessage> history) {
        if (!properties.isEnabled() || history == null || history.isEmpty()) {
            return new CompressionResult(history != null ? history : Collections.emptyList(), false, 0, "none");
        }

        int estimatedTokens = estimateTokens(history);
        int rounds = history.size() / 2;
        int maxRounds = properties.getMaxRoundsBeforeCompress();
        int maxTokens = properties.getMaxTokensBeforeCompress();
        int maxChars = properties.getMaxCharsBeforeCompress();
        int totalChars = history.stream().mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0).sum();

        boolean needsCompression = rounds > maxRounds || estimatedTokens > maxTokens || totalChars > maxChars;

        if (!needsCompression) {
            log.debug("[Compression] 无需压缩: rounds={}, tokens={}, chars={}", rounds, estimatedTokens, totalChars);
            return new CompressionResult(history, false, estimatedTokens, "none");
        }

        log.info("[Compression] 开始压缩: rounds={} → max={}, tokens={}, chars={}, strategy={}",
                rounds, properties.getKeepRecentRounds(), estimatedTokens, totalChars, properties.getStrategy());

        List<StructuredPrompt.ConversationMessage> compressed =
                compressWithStrategy(history, properties.getKeepRecentRounds(), properties.getStrategy());

        int newTokens = estimateTokens(compressed);
        String usedStrategy = detectUsedStrategy(rounds, maxRounds);

        log.info("[Compression] 压缩完成: strategy={}, tokens={} → {}, rounds={} → {}",
                usedStrategy, estimatedTokens, newTokens, rounds, compressed.size() / 2);

        return new CompressionResult(compressed, true, newTokens, usedStrategy);
    }

    /**
     * 根据策略选择压缩方式
     */
    private List<StructuredPrompt.ConversationMessage> compressWithStrategy(
            List<StructuredPrompt.ConversationMessage> history,
            int keepRecent,
            String strategy) {

        int rounds = history.size() / 2;
        int maxRounds = properties.getMaxRoundsBeforeCompress();

        // 自动策略：根据轮数选择最佳压缩方式
        if ("auto".equalsIgnoreCase(strategy)) {
            if (rounds <= maxRounds * 2) {
                return slidingWindowTruncate(history, keepRecent);
            } else if (rounds <= maxRounds * 4) {
                return keyInfoExtraction(history, keepRecent);
            } else {
                return llmSummaryCompress(history, keepRecent);
            }
        }

        return switch (strategy.toLowerCase()) {
            case "truncate" -> slidingWindowTruncate(history, keepRecent);
            case "extract" -> keyInfoExtraction(history, keepRecent);
            case "llm" -> llmSummaryCompress(history, keepRecent);
            default -> slidingWindowTruncate(history, keepRecent);
        };
    }

    /**
     * 策略 1：滑动窗口截断（保留最近 N 轮）
     */
    private List<StructuredPrompt.ConversationMessage> slidingWindowTruncate(
            List<StructuredPrompt.ConversationMessage> history, int keepRecent) {

        int keepMessages = keepRecent * 2;
        if (history.size() <= keepMessages) {
            return new ArrayList<>(history);
        }

        List<StructuredPrompt.ConversationMessage> result = new ArrayList<>();
        result.add(createSystemMessage("[历史对话摘要] 此前 " + (history.size() / 2 - keepRecent) +
                " 轮对话已截断，仅保留最近 " + keepRecent + " 轮。"));

        result.addAll(history.subList(history.size() - keepMessages, history.size()));
        return result;
    }

    /**
     * 策略 2：关键信息提取（规则化精简中间轮次）
     */
    private List<StructuredPrompt.ConversationMessage> keyInfoExtraction(
            List<StructuredPrompt.ConversationMessage> history, int keepRecent) {

        int keepMessages = keepRecent * 2;
        int totalMessages = history.size();

        // 1. 保留最近 N 轮（硬保真）
        List<StructuredPrompt.ConversationMessage> recent = history.subList(
                Math.max(0, totalMessages - keepMessages), totalMessages);

        // 2. 对更早的轮次做关键信息提取
        List<StructuredPrompt.ConversationMessage> older = totalMessages > keepMessages
                ? history.subList(0, totalMessages - keepMessages)
                : Collections.emptyList();

        List<StructuredPrompt.ConversationMessage> extracted = older.stream()
                .map(this::extractKeyInfo)
                .filter(Objects::nonNull)
                .toList();

        // 3. 合并：提取的关键信息 + 保留的最近轮次
        List<StructuredPrompt.ConversationMessage> result = new ArrayList<>();

        if (!extracted.isEmpty()) {
            result.add(createSystemMessage("[历史对话关键信息] 此前对话中的关键信息已提取如下："));
            result.addAll(extracted);
        }

        result.addAll(recent);
        return result;
    }

    /**
     * 从单条消息中提取关键信息
     */
    private StructuredPrompt.ConversationMessage extractKeyInfo(
            StructuredPrompt.ConversationMessage message) {

        if (message == null || message.getContent() == null) {
            return null;
        }

        String content = message.getContent().trim();
        boolean isUser = "user".equalsIgnoreCase(message.getRole());

        // 如果是寒暄，直接丢弃
        if (GREETING_PATTERN.matcher(content).matches() && content.length() < 15) {
            return null;
        }

        // 提取包含关键实体的句子
        String[] sentences = content.split("[。！？\\n]");
        StringBuilder keyInfo = new StringBuilder();

        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (sentence.isEmpty()) continue;

            // 包含实体词、数字、地点、时间等，保留
            if (ENTITY_PATTERN.matcher(sentence).find() ||
                    sentence.matches(".*\\d+.*") ||
                    sentence.length() > 20) {
                keyInfo.append(sentence).append("；");
            }
        }

        if (keyInfo.isEmpty()) {
            // 如果没有明显实体，保留前 30 字作为摘要
            keyInfo.append(content, 0, Math.min(30, content.length())).append("...");
        }

        return StructuredPrompt.ConversationMessage.builder()
                .role(isUser ? "user" : "agent")
                .content(keyInfo.toString())
                .timestamp(message.getTimestamp())
                .build();
    }

    /**
     * 策略 3：LLM 语义摘要（最高保真，但有额外调用成本）
     */
    private List<StructuredPrompt.ConversationMessage> llmSummaryCompress(
            List<StructuredPrompt.ConversationMessage> history, int keepRecent) {

        int keepMessages = keepRecent * 2;
        int totalMessages = history.size();

        // 1. 保留最近 N 轮
        List<StructuredPrompt.ConversationMessage> recent = history.subList(
                Math.max(0, totalMessages - keepMessages), totalMessages);

        // 2. 需要被摘要的更早对话
        if (totalMessages <= keepMessages || chatClient.isEmpty()) {
            return slidingWindowTruncate(history, keepRecent);
        }

        List<StructuredPrompt.ConversationMessage> toSummarize = history.subList(0, totalMessages - keepMessages);

        try {
            StringBuilder oldHistory = new StringBuilder();
            for (var msg : toSummarize) {
                oldHistory.append("user".equalsIgnoreCase(msg.getRole()) ? "用户: " : "助手: ")
                        .append(msg.getContent())
                        .append("\n");
            }

            String prompt = String.format("""
                请对以下历史对话进行精简摘要，保留关键信息（地点、时间、意图、偏好、约束条件等），去除寒暄和冗余内容。

                原始对话：
                %s

                要求：
                1. 用简洁的语言概括对话要点
                2. 保留所有关键实体（地点、时间、物品、价格等）
                3. 控制在 %d 字以内
                4. 只输出摘要内容，不要其他说明

                摘要：
                """, oldHistory, properties.getLlmSummaryMaxChars());

            String summary = chatClient.get()
                    .prompt(prompt)
                    .call()
                    .content();

            List<StructuredPrompt.ConversationMessage> result = new ArrayList<>();
            result.add(createSystemMessage("[历史对话摘要] " + summary));
            result.addAll(recent);

            log.info("[Compression] LLM 摘要完成: 摘要长度={} 字符", summary.length());
            return result;

        } catch (Exception e) {
            log.warn("[Compression] LLM 摘要失败，降级为关键信息提取: {}", e.getMessage());
            return keyInfoExtraction(history, keepRecent);
        }
    }

    /**
     * 估算 Token 数（保守估计）
     * <p>中文字符 ≈ 0.8 token，英文单词 ≈ 1.3 token，数字/标点 ≈ 0.5 token</p>
     */
    public int estimateTokens(List<StructuredPrompt.ConversationMessage> history) {
        if (history == null || history.isEmpty()) return 0;

        int totalChars = 0;
        int chineseChars = 0;
        int englishWords = 0;

        for (var msg : history) {
            if (msg == null || msg.getContent() == null) continue;
            String content = msg.getContent();
            totalChars += content.length();

            for (char c : content.toCharArray()) {
                if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                        || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                        || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                    chineseChars++;
                }
            }

            // 英文单词估算（连续字母序列）
            englishWords += content.split("[\\s\\p{Punct}]+").length;
        }

        // 混合估算公式
        double tokens = chineseChars * properties.getTokenEstimateRatio()
                + (totalChars - chineseChars) * 0.4
                + englishWords * 0.3;

        return (int) Math.ceil(tokens);
    }

    /**
     * 检测实际使用的压缩策略（用于日志和监控）
     */
    private String detectUsedStrategy(int rounds, int maxRounds) {
        if (rounds <= maxRounds * 2) return "truncate";
        if (rounds <= maxRounds * 4) return "extract";
        return "llm";
    }

    private StructuredPrompt.ConversationMessage createSystemMessage(String content) {
        return StructuredPrompt.ConversationMessage.builder()
                .role("system")
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 压缩结果封装
     */
    public record CompressionResult(
            List<StructuredPrompt.ConversationMessage> history,
            boolean compressed,
            int estimatedTokens,
            String strategy
    ) {
    }
}
