package com.example.smartassistant.common.error;

/** Model infrastructure failures must never be returned as successful answer text. */
public final class ModelCallFailure extends RuntimeException {
    private final String code;
    private ModelCallFailure(String code, Throwable cause) {
        super("智能回复服务暂时不可用，请稍后再试。", cause);
        this.code = code;
    }
    public String code() { return code; }
    public static ModelCallFailure from(Throwable cause) {
        if (cause instanceof ModelCallFailure failure) return failure;
        String code = "MODEL_CALL_FAILED";
        for (Throwable current = cause; current != null; current = current.getCause()) {
            String message = java.util.Objects.toString(current.getMessage(), "");
            if (message.matches("(?is).*(?:\\b402\\b|insufficient balance).*")) {
                code = "MODEL_BILLING_UNAVAILABLE"; break;
            }
            if (message.matches("(?is).*(?:\\b401\\b|\\b403\\b).*")) code = "MODEL_AUTH_UNAVAILABLE";
        }
        return new ModelCallFailure(code, cause);
    }
    public static boolean retryable(Throwable cause) {
        String code = from(cause).code();
        return !code.equals("MODEL_BILLING_UNAVAILABLE") && !code.equals("MODEL_AUTH_UNAVAILABLE");
    }
}
