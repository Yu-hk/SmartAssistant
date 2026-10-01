import java.lang.reflect.Method;

/** Deterministic proof using the exact deployed/candidate classes, without a model or network. */
public class LoopGuardRegressionProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("baseline") || args[0].equals("candidate")))
            throw new IllegalArgumentException("Expected baseline or candidate");
        boolean fixed = args[0].equals("candidate");
        Class<?> guardType = Class.forName("com.example.smartassistant.common.agent.LoopGuardService");
        Object guard = guardType.getConstructor().newInstance();
        Method analyze = guardType.getMethod("analyze", String.class);
        String[] uncertainty = {
            "知识库仅列出库存状态，暂时无法确定可售库存计算公式。请提供相关规则后核实。",
            "现有资料信息不足，没有说明锁定库存和质检库存如何处理；不能据此推测公式。"
        };
        for (String text : uncertainty) checkAction(analyze, guard, text, fixed ? "CONTINUE" : "PAUSE_BLOCKED");
        checkAction(analyze, guard, "没有权限调用订单接口，无法继续执行下单。", "PAUSE_BLOCKED");
        checkAction(analyze, guard, "API 返回 HTTP 503，服务内部错误。", "PAUSE_INFRASTRUCTURE");
        checkAction(analyze, guard, "订单金额1999元，请确认是否继续下单。", "AWAIT_CONFIRMATION");
        Class<?> qualityType = Class.forName("com.example.smartassistant.service.quality.ProductDomainQualityValidator");
        Class<?> retrievalType = Class.forName("com.example.smartassistant.common.rag.RetrievalQualityResult");
        Class<?> verdictType = Class.forName("com.example.smartassistant.common.rag.eval.FaithfulnessGuard$FaithfulnessVerdict");
        Object validator = qualityType.getConstructor().newInstance();
        Object retrieval = retrievalType.getMethod("highQuality", String.class, double.class)
            .invoke(null, "[E1] 库存状态包括可售和锁定。", 0.9);
        Method evaluate = qualityType.getMethod("evaluate", String.class, retrievalType, verdictType);
        String[] failures = {
            "检测到 Agent 报告被阻塞，无法继续。请提供更多信息或重新描述需求。",
            "检测到基础设施故障，已暂停以避免持续重试。请稍后再试。"
        };
        for (String text : failures) checkQuality(evaluate, validator, retrieval, text, fixed ? "FAIL" : "PASS");
        checkQuality(evaluate, validator, retrieval, uncertainty[0], "PASS");
        System.out.println("{\"mode\":\"" + args[0] + "\",\"status\":\"passed\",\"checks\":8}");
    }

    private static void checkAction(Method analyze, Object guard, String text, String expected) throws Exception {
        Object result = analyze.invoke(guard, text);
        String actual = result.getClass().getMethod("action").invoke(result).toString();
        if (!actual.equals(expected)) throw new IllegalStateException("Guard expected " + expected + " but got " + actual);
    }

    private static void checkQuality(Method evaluate, Object validator, Object retrieval, String text, String expected) throws Exception {
        Object result = evaluate.invoke(validator, text, retrieval, null);
        String actual = result.getClass().getMethod("getStatus").invoke(result).toString();
        if (!actual.equals(expected)) throw new IllegalStateException("Quality expected " + expected + " but got " + actual);
    }
}
