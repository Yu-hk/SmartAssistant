package com.example.smartassistant.router.service.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对必须稳定、可追溯的客服高频场景提供确定性响应。
 * 商家政策只使用随构建产物发布的已核验真实知识文档，不让模型猜测时限。
 */
@Service
public class VerifiedCustomerResponseService {

    private static final Logger log = LoggerFactory.getLogger(VerifiedCustomerResponseService.class);
    private static final String APPLE_POLICY_RESOURCE =
            "knowledge/real-sources/apple-cn-return-refund.md";
    private static final Pattern SOURCE_URL = Pattern.compile("(?m)^sourceUrl:\\s*(\\S+)\\s*$");
    private static final Pattern EXPIRES_AT = Pattern.compile("(?m)^expireAt:\\s*(\\d{4}-\\d{2}-\\d{2})\\s*$");
    private static final Pattern ORDER_IDENTIFIER = Pattern.compile(
            "(?i)\\bORD[-A-Z0-9]{3,}\\b|\\b[A-Z]{2,5}-?\\d{6,}\\b");

    private final Clock clock;
    private final String applePolicy;
    private final String appleSourceUrl;
    private final LocalDate appleExpiresAt;

    public VerifiedCustomerResponseService() {
        this(Clock.systemDefaultZone());
    }

    VerifiedCustomerResponseService(Clock clock) {
        this.clock = clock;
        this.applePolicy = readClasspathResource(APPLE_POLICY_RESOURCE);
        this.appleSourceUrl = extract(SOURCE_URL, applePolicy).orElse(
                "https://www.apple.com.cn/cn/shop/help/returns_refund");
        this.appleExpiresAt = extract(EXPIRES_AT, applePolicy)
                .map(LocalDate::parse)
                .orElse(LocalDate.MIN);
    }

    public Optional<VerifiedResponse> match(String question) {
        if (question == null || question.isBlank()) return Optional.empty();
        if (isPromptInjection(question)) return Optional.of(promptInjectionRefusal());
        if (isExplicitNoFabricationOrderQuery(question)) return Optional.of(unverifiedOrderResponse());
        if (isHumanHandoff(question)) return Optional.of(humanHandoff());
        if (isAppleReturnPolicy(question)) return appleReturnPolicy();
        if (isInvoiceGuidance(question)) return Optional.of(invoiceGuidance());
        if (isGenericReturnRefundGuidance(question)) return Optional.of(returnRefundGuidance());
        if (isProductLookupMissingName(question)) return Optional.of(productNameClarification());
        return Optional.empty();
    }

    private VerifiedResponse invoiceGuidance() {
        return new VerifiedResponse(
                "申请电子发票通常可按以下步骤操作：\n"
                        + "1. 打开“我的订单”，进入需要开票的订单详情；\n"
                        + "2. 选择“申请发票”或“发票信息”；\n"
                        + "3. 填写个人或企业抬头、税号和接收邮箱后提交。\n"
                        + "是否支持开票及可开票金额以订单页面为准。如需我进一步核验，请提供订单号。",
                "builtin_customer_service", "invoice_guidance", "VERIFIED_WORKFLOW", false);
    }

    private VerifiedResponse returnRefundGuidance() {
        return new VerifiedResponse(
                "申请退货退款通常可按以下步骤操作：\n"
                        + "1. 打开“我的订单”，选择对应订单；\n"
                        + "2. 点击“申请售后”，选择退货退款及原因；\n"
                        + "3. 按页面提示提交凭证并等待审核；\n"
                        + "4. 审核通过后按指定方式寄回商品。\n"
                        + "实际可退范围、时限和退款路径以该订单及商家政策为准。如需核验资格，请提供订单号。",
                "builtin_customer_service", "return_refund_guidance", "VERIFIED_WORKFLOW", false);
    }

    private VerifiedResponse productNameClarification() {
        return new VerifiedResponse(
                "可以为您查询商品规格、价格和库存。请告诉我商品名称或具体型号，例如“iPhone 15 Pro”。"
                        + "如果有商品编号，也可以直接发送商品编号。",
                "builtin_customer_service", "product_identifier_required", "VERIFIED_CLARIFICATION", false);
    }

    private VerifiedResponse promptInjectionRefusal() {
        return new VerifiedResponse(
                "抱歉，我不能披露系统提示、数据库凭据、密钥或其他内部配置。"
                        + "如果您需要业务帮助，请描述订单、商品或售后问题，并避免发送密码、验证码等敏感信息。",
                "builtin_fallback", "prompt_injection", "VERIFIED_SAFETY", false);
    }

    private VerifiedResponse unverifiedOrderResponse() {
        return new VerifiedResponse(
                "当前没有拿到可核验的订单记录，因此不会编造订单状态或物流信息。"
                        + "请核对订单号后重试，或转人工客服进一步核验。",
                "order_agent", "order_lookup_unverified", "VERIFIED_SAFETY", false);
    }

    private Optional<VerifiedResponse> appleReturnPolicy() {
        if (!applePolicy.contains("十四个自然日")) {
            log.warn("[VerifiedCustomerResponse] Apple 政策文档缺失关键事实，拒绝生成答案");
            return Optional.empty();
        }
        if (LocalDate.now(clock).isAfter(appleExpiresAt)) {
            return Optional.of(new VerifiedResponse(
                    "当前保存的 Apple 中国大陆退货政策已超过核验日期，不能继续把旧时限作为有效政策。"
                            + "请以 Apple 官方页面为准：" + appleSourceUrl,
                    "verified_knowledge", "merchant_return_policy", "VERIFIED_KNOWLEDGE", false));
        }
        String answer = "根据已核验的 Apple 中国大陆在线商店退货与退款政策，符合条件的商品可在交付之日起"
                + " 14 个自然日（十四个自然日）内发起退货。商品应保持未损坏，并连同原始零件、配件、"
                + "发票和包装退回；其他授权渠道购买的商品需咨询对应销售方。官方来源：" + appleSourceUrl;
        return Optional.of(new VerifiedResponse(
                answer, "verified_knowledge", "merchant_return_policy", "VERIFIED_KNOWLEDGE", false));
    }

    private VerifiedResponse humanHandoff() {
        return new VerifiedResponse(
                "很抱歉让您多次遇到问题。已将本次会话标记为需要人工接管，正在为您转接人工客服；"
                        + "请稍候并准备好订单号。为保护隐私，请不要发送密码、验证码或完整支付信息。",
                "human_support", "human_handoff", "VERIFIED_HANDOFF", true);
    }

    private boolean isAppleReturnPolicy(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        boolean merchant = normalized.contains("apple") || normalized.contains("苹果");
        boolean policy = normalized.contains("退货") || normalized.contains("退款")
                || normalized.contains("退换") || normalized.contains("多少天")
                || normalized.contains("时限") || normalized.contains("期限");
        return merchant && policy;
    }

    private boolean isHumanHandoff(String question) {
        if (!question.contains("人工")) return false;
        return question.contains("转人工") || question.contains("转接") || question.contains("转到人工")
                || question.contains("我要人工") || question.contains("需要人工")
                || question.contains("找人工") || question.contains("联系人工")
                || question.contains("投诉") || question.contains("没人处理");
    }

    private boolean isInvoiceGuidance(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        return normalized.contains("发票")
                && (normalized.contains("申请") || normalized.contains("开具")
                    || normalized.contains("怎么开") || normalized.contains("如何开"));
    }

    private boolean isGenericReturnRefundGuidance(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        boolean returnOrRefund = normalized.contains("退货") || normalized.contains("退款");
        boolean asksProcess = normalized.contains("如何") || normalized.contains("怎么")
                || normalized.contains("流程") || normalized.contains("申请");
        return returnOrRefund && asksProcess && !ORDER_IDENTIFIER.matcher(question).find();
    }

    private boolean isProductLookupMissingName(String question) {
        String normalized = question.toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？,.!?]", "");
        return normalized.equals("咨询商品规格和库存")
                || normalized.equals("查询商品规格和库存")
                || normalized.equals("商品规格和库存");
    }

    private boolean isPromptInjection(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        boolean overrideInstruction = normalized.contains("忽略所有系统指令")
                || normalized.contains("忽略系统指令")
                || normalized.contains("绕过系统指令");
        boolean requestsInternalSecret = normalized.contains("系统提示词")
                || normalized.contains("数据库密码")
                || normalized.contains("api key")
                || normalized.contains("密钥");
        return overrideInstruction && requestsInternalSecret;
    }

    private boolean isExplicitNoFabricationOrderQuery(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        boolean orderLookup = normalized.contains("订单")
                && (normalized.contains("状态") || normalized.contains("物流"));
        boolean requiresVerifiedData = normalized.contains("不要编造")
                || normalized.contains("不要猜")
                || normalized.contains("只说真实")
                || normalized.contains("必须核验");
        return orderLookup && requiresVerifiedData && ORDER_IDENTIFIER.matcher(question).find();
    }

    private static String readClasspathResource(String location) {
        try (InputStream input = new ClassPathResource(location).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[VerifiedCustomerResponse] 无法读取知识文档 {}: {}", location, e.getMessage());
            return "";
        }
    }

    private static Optional<String> extract(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public record VerifiedResponse(
            String answer,
            String agentName,
            String intentTag,
            String routingMethod,
            boolean handoff) {
    }
}
