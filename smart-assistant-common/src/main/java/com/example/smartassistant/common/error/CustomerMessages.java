package com.example.smartassistant.common.error;

/** Public wording; keep machine statuses and retry/admission decisions separate. */
public final class CustomerMessages {
    private CustomerMessages() { }
    public static final String UNCONFIRMED = "这次处理的结果还没有确认。请先查看原请求的结果；如果涉及下单、支付或退款，请不要重复提交，以免重复操作。";
    public static final String NOT_SENT = "这次没能连接上服务，您的请求还没有开始处理。您可以稍后再试。";
    public static final String QUEUE_EXPIRED = "抱歉让您久等了，这次排队已结束，请求还没有开始处理。您可以稍后重新发送。";
    public static final String NO_DATA = "目前还没有查到相关信息。您可以补充商品名称或相关资料，我再帮您核对。";
    public static final String INSUFFICIENT_EVIDENCE = "目前查到的资料还不足以确认答案，我不想给您不准确的信息。您可以补充相关说明或需要核对的内容。";
    public static final String UNAVAILABLE = "抱歉，这次没能完成您的请求。如果涉及下单、支付或退款，请先核查原操作的结果，避免重复提交。";
}
