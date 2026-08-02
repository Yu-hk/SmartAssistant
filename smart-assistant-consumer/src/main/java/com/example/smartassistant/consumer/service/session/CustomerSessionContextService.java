package com.example.smartassistant.consumer.service.session;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 保存客服会话中的业务实体。键同时包含认证用户和 sessionId，避免会话 ID 猜测导致跨账号串用。
 */
@Service
public class CustomerSessionContextService {

    private static final Pattern ORDER_ID = Pattern.compile("(?i)ORD-[A-Z0-9][A-Z0-9_-]{2,63}");
    private static final Pattern ORDINAL_SELECTION = Pattern.compile(
            "第\\s*([1-9]|一|二|三|四|五|六|七|八|九)\\s*(?:笔|个|单|条)");
    private static final Set<String> ORDER_FOLLOW_UP_TERMS = Set.of(
            "它", "这个订单", "该订单", "订单", "物流", "快递", "运单", "单号", "签收", "退款", "退货");
    private static final Duration CONTEXT_TTL = Duration.ofHours(1);
    private static final int MAX_HISTORY_MESSAGES = 10;
    private static final String ORDER_KEY_PREFIX = "customer-session:order:";
    private static final String ORDER_CANDIDATES_KEY_PREFIX = "customer-session:order-candidates:";
    private static final String LAST_USER_MESSAGE_KEY_PREFIX = "customer-session:last-user-message:";
    private static final String LAST_ASSISTANT_MESSAGE_KEY_PREFIX = "customer-session:last-assistant-message:";
    private static final String ROUTER_HISTORY_KEY_PREFIX = "chat:history:";

    private final StringRedisTemplate redisTemplate;

    public CustomerSessionContextService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String enrichOrderReference(String userId, String sessionId, String message) {
        if (message == null || message.isBlank() || !isSafeScope(userId, sessionId)) {
            return message;
        }

        String explicitOrderId = extractOrderId(message);
        String key = contextKey(userId, sessionId);
        if (explicitOrderId != null) {
            redisTemplate.opsForValue().set(key, explicitOrderId, CONTEXT_TTL);
            if (isRememberedCandidate(userId, sessionId, explicitOrderId)) {
                return contextualizeWithPreviousTurn(userId, sessionId, message);
            }
            return message;
        }

        Integer selectedIndex = extractOrdinalSelection(message);
        if (selectedIndex != null) {
            String candidatesKey = candidatesKey(userId, sessionId);
            String storedCandidates = redisTemplate.opsForValue().get(candidatesKey);
            List<String> candidates = parseCandidates(storedCandidates);
            if (selectedIndex > 0 && selectedIndex <= candidates.size()) {
                String selectedOrderId = candidates.get(selectedIndex - 1);
                redisTemplate.opsForValue().set(key, selectedOrderId, CONTEXT_TTL);
                redisTemplate.expire(candidatesKey, CONTEXT_TTL);
                return contextualizeWithPreviousTurn(
                        userId, sessionId,
                        message + "（用户选择的订单号：" + selectedOrderId + "）");
            }
        }

        if (!isOrderFollowUp(message)) {
            return message;
        }

        String rememberedOrderId = redisTemplate.opsForValue().get(key);
        if (rememberedOrderId == null || rememberedOrderId.isBlank()) {
            return message;
        }
        redisTemplate.expire(key, CONTEXT_TTL);
        return contextualizeWithPreviousTurn(
                userId, sessionId,
                message + "（当前会话订单号：" + rememberedOrderId + "）");
    }

    /**
     * 保存已完成的一轮对话，供下一轮恢复语义。所有键都按认证用户和 sessionId 隔离。
     */
    public void rememberConversationTurn(String userId, String sessionId,
                                         String userMessage, String assistantMessage) {
        if (!isSafeScope(userId, sessionId)
                || userMessage == null || userMessage.isBlank()
                || assistantMessage == null || assistantMessage.isBlank()) {
            return;
        }

        redisTemplate.opsForValue().set(
                lastUserMessageKey(userId, sessionId), userMessage, CONTEXT_TTL);
        redisTemplate.opsForValue().set(
                lastAssistantMessageKey(userId, sessionId), assistantMessage, CONTEXT_TTL);

        String historyKey = routerHistoryKey(userId, sessionId);
        redisTemplate.opsForList().rightPush(historyKey, "用户：" + userMessage);
        redisTemplate.opsForList().rightPush(historyKey, "助手：" + assistantMessage);
        redisTemplate.opsForList().trim(historyKey, -MAX_HISTORY_MESSAGES, -1);
        redisTemplate.expire(historyKey, CONTEXT_TTL);
    }

    public void rememberOrderCandidates(String userId, String sessionId, String responseText) {
        if (responseText == null || responseText.isBlank() || !isSafeScope(userId, sessionId)) {
            return;
        }

        Matcher matcher = ORDER_ID.matcher(responseText);
        Set<String> uniqueOrderIds = new LinkedHashSet<>();
        while (matcher.find()) {
            uniqueOrderIds.add(matcher.group().toUpperCase());
        }
        if (uniqueOrderIds.isEmpty()) {
            return;
        }

        List<String> candidates = new ArrayList<>(uniqueOrderIds);
        redisTemplate.opsForValue().set(
                candidatesKey(userId, sessionId), String.join(",", candidates), CONTEXT_TTL);
        if (candidates.size() == 1) {
            redisTemplate.opsForValue().set(
                    contextKey(userId, sessionId), candidates.get(0), CONTEXT_TTL);
        } else {
            redisTemplate.delete(contextKey(userId, sessionId));
        }
    }

    static String extractOrderId(String message) {
        if (message == null) return null;
        Matcher matcher = ORDER_ID.matcher(message);
        return matcher.find() ? matcher.group().toUpperCase() : null;
    }

    static boolean isOrderFollowUp(String message) {
        if (message == null) return false;
        return ORDER_FOLLOW_UP_TERMS.stream().anyMatch(message::contains);
    }

    static Integer extractOrdinalSelection(String message) {
        if (message == null) return null;
        Matcher matcher = ORDINAL_SELECTION.matcher(message);
        if (!matcher.find()) return null;
        return switch (matcher.group(1)) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            default -> Integer.parseInt(matcher.group(1));
        };
    }

    private List<String> parseCandidates(String storedCandidates) {
        if (storedCandidates == null || storedCandidates.isBlank()) {
            return List.of();
        }
        return List.of(storedCandidates.split(","));
    }

    private boolean isRememberedCandidate(String userId, String sessionId, String orderId) {
        String storedCandidates = redisTemplate.opsForValue().get(candidatesKey(userId, sessionId));
        return parseCandidates(storedCandidates).stream().anyMatch(orderId::equalsIgnoreCase);
    }

    private String contextualizeWithPreviousTurn(String userId, String sessionId, String currentMessage) {
        String previousUserMessage = redisTemplate.opsForValue().get(
                lastUserMessageKey(userId, sessionId));
        String previousAssistantMessage = redisTemplate.opsForValue().get(
                lastAssistantMessageKey(userId, sessionId));
        if (previousUserMessage == null || previousUserMessage.isBlank()
                || previousAssistantMessage == null || previousAssistantMessage.isBlank()) {
            return currentMessage;
        }

        return "【当前问题】\n" + currentMessage
                + "\n【历史对话】\n用户：" + previousUserMessage
                + "\n助手：" + previousAssistantMessage
                + "\n【处理要求】\n请结合上一轮用户的查询目标，继续处理本轮选择的订单。";
    }

    private boolean isSafeScope(String userId, String sessionId) {
        return userId != null && !userId.isBlank() && !"anonymous".equalsIgnoreCase(userId)
                && sessionId != null && !sessionId.isBlank();
    }

    private String contextKey(String userId, String sessionId) {
        return ORDER_KEY_PREFIX + userId + ":" + sessionId;
    }

    private String candidatesKey(String userId, String sessionId) {
        return ORDER_CANDIDATES_KEY_PREFIX + userId + ":" + sessionId;
    }

    private String lastUserMessageKey(String userId, String sessionId) {
        return LAST_USER_MESSAGE_KEY_PREFIX + userId + ":" + sessionId;
    }

    private String lastAssistantMessageKey(String userId, String sessionId) {
        return LAST_ASSISTANT_MESSAGE_KEY_PREFIX + userId + ":" + sessionId;
    }

    private String routerHistoryKey(String userId, String sessionId) {
        return ROUTER_HISTORY_KEY_PREFIX + userId + ":" + sessionId;
    }
}
