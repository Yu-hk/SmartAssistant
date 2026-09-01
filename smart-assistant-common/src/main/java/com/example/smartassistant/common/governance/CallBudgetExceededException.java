package com.example.smartassistant.common.governance;

/** Non-retryable guard raised before a model/tool invocation exceeds its budget. */
public class CallBudgetExceededException extends RuntimeException {
    private final String callKind;
    private final String scope;
    private final int limit;

    public CallBudgetExceededException(String callKind, String scope, String identity, int limit) {
        super(callKind + " call budget exceeded: scope=" + scope + ", id=" + identity + ", limit=" + limit);
        this.callKind = callKind;
        this.scope = scope;
        this.limit = limit;
    }

    public String getCallKind() { return callKind; }
    public String getScope() { return scope; }
    public int getLimit() { return limit; }
}
