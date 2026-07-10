package com.example.smartassistant.common.gateway.tool.hook;

/**
 * 工具执行钩子接口（REQ-02）。
 * <p>
 * 定义工具执行前、执行后、异常时的回调扩展点，通过 Spring {@code @Component} + {@code @Order}
 * 自动收集为 {@code List<ToolExecutionHook>}，由 {@link com.example.smartassistant.common.gateway.tool.ToolGateway}
 * 在执行链中按序调用。
 * </p>
 *
 * <h3>调用时机</h3>
 * <ul>
 *   <li>{@link #preExecute} — 步骤0（获取 ToolDefinition）之后、步骤1（幂等检查）之前。
 *       抛异常可阻断执行。</li>
 *   <li>{@link #postExecute} — 步骤6（审计日志）之后。返回可能被修改的结果（如脱敏）。
 *       链式传递：前一个 Hook 的返回值作为后一个 Hook 的输入。</li>
 *   <li>{@link #onError} — catch 块中（recordFailure 之后、throw 之前）。</li>
 * </ul>
 *
 * <h3>排序约定</h3>
 * <ul>
 *   <li>preExecute 正序执行（@Order 值从小到大），确保状态检查先于审批检查</li>
 *   <li>postExecute 正序执行，确保脱敏先于审计</li>
 * </ul>
 *
 * @author Yu-hk
 * @since 2026-07-15
 */
public interface ToolExecutionHook {

    /**
     * 执行前钩子。
     * <p>在步骤0之后、步骤1之前调用。抛出 {@link RuntimeException} 可阻断执行。</p>
     *
     * @param context Hook 上下文
     */
    void preExecute(ToolHookContext context);

    /**
     * 执行后钩子。
     * <p>在步骤6之后调用。可修改并返回结果（如脱敏处理）。
     * 链式传递：返回值将作为下一个 Hook 的输入。</p>
     *
     * @param context Hook 上下文（elapsedMs 已填充）
     * @param result  当前结果（可能是前一个 Hook 处理后的）
     * @return 处理后的结果（如无需修改则原样返回）
     */
    String postExecute(ToolHookContext context, String result);

    /**
     * 异常钩子。
     * <p>在 catch 块中调用（recordFailure 之后、throw 之前）。用于记录失败审计等。</p>
     *
     * @param context Hook 上下文（elapsedMs 已填充）
     * @param ex      捕获的异常
     */
    void onError(ToolHookContext context, Exception ex);

    /**
     * 获取 Hook 名称（用于日志和排序标识）。
     *
     * @return Hook 名称
     */
    String getName();
}
