/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.sandbox;

import com.example.smartassistant.common.error.AgentErrorCode;
import jakarta.annotation.PreDestroy;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脚本运行时沙箱（Runtime Sandbox）。
 *
 * <p>为 {@code executeScript} 工具提供受控执行环境，借鉴 AgentScope 的 Runtime Sandbox 思想，
 * 在「数学计算脚本」这一受限场景下建立分层防御：</p>
 *
 * <ol>
 *   <li><b>关键字黑名单</b>（防御纵深）：拦截 {@code import/exec/system} 等危险关键字；</li>
 *   <li><b>静态资源限制</b>（主防线）：脚本长度、行数、变量数、单行表达式长度、输出长度上限，
 *       静态地界定计算量，从源头杜绝 CPU/内存耗尽；</li>
 *   <li><b>超时隔离</b>（安全网）：在独立线程执行，{@code Future.get(timeout)} 熔断，
 *       超时立即释放调用方（Tomcat 工作）线程，避免请求被长任务挂死。</li>
 * </ol>
 *
 * <p><b>线程安全</b>：每次执行使用独立的局部变量表与输出缓冲，无共享可变状态；
 * 仅持有只读的 {@link ScriptSandboxProperties} 与线程安全的 {@link ExecutorService}。</p>
 *
 * <p><b>取消语义说明</b>：exp4j 计算不响应中断，{@code future.cancel(true)} 仅释放调用方线程，
 * 被取消的工作线程会自然跑完（计算量已被「行数 × 单行长度」上限界定，最坏情况毫秒级）。
 * 采用虚拟线程承载，单次孤儿线程成本可忽略。</p>
 */
@Component
public class ScriptSandbox {

    private static final Logger log = LoggerFactory.getLogger(ScriptSandbox.class);

    /** 危险关键字黑名单（防御纵深，非唯一防线）——由 GeneralTools 迁入 */
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "(?i)(class|import|exec|eval|runtime|process|system|file|socket|url|jdbc|http|reflect|new\\s|;\\s*\\w)"
    );

    /** 变量赋值匹配：{@code var = expr} */
    private static final Pattern ASSIGN_PATTERN =
            Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(.+)$");

    private final ScriptSandboxProperties props;

    /** 超时隔离专用执行器：虚拟线程（守护线程，不阻塞 JVM 退出），每任务一线程 */
    private final ExecutorService sandboxExecutor;

    public ScriptSandbox(ScriptSandboxProperties props) {
        this.props = props;
        this.sandboxExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("script-sandbox-", 0).factory());
    }

    /**
     * 在沙箱中执行多步计算脚本。
     *
     * @param script 多行脚本（{@code \n} 分隔）
     * @return 结构化结果：成功携带格式化输出，失败携带标准错误码
     */
    public SandboxResult execute(String script) {
        // 1) 空脚本
        if (script == null || script.isBlank()) {
            return SandboxResult.fail(AgentErrorCode.VALIDATION_SCRIPT_EMPTY,
                    "脚本中没有有效的计算语句",
                    "格式：每行一条语句，'变量名 = 表达式' 或直接写表达式");
        }
        // 2) 危险关键字（防御纵深）
        if (DANGEROUS_PATTERN.matcher(script).find()) {
            log.warn("[ScriptSandbox] 脚本包含危险关键字: {}", abbreviate(script, 80));
            return SandboxResult.fail(AgentErrorCode.SECURITY_SCRIPT_REJECTED,
                    "脚本包含不允许的内容，仅支持数学运算和变量赋值。",
                    "请检查是否包含 import/exec/system 等关键字");
        }
        // 3) 脚本长度上限（防 DoS 级超大输入）
        if (script.length() > props.getMaxScriptLength()) {
            return SandboxResult.fail(AgentErrorCode.SECURITY_SCRIPT_RESOURCE_LIMIT,
                    "脚本长度 " + script.length() + " 字符超过上限 " + props.getMaxScriptLength() + "。",
                    "请缩减脚本长度");
        }
        // 4) 行数上限
        String[] lines = script.replace("\\n", "\n").split("\n");
        if (lines.length > props.getMaxLines()) {
            return SandboxResult.fail(AgentErrorCode.SECURITY_SCRIPT_RESOURCE_LIMIT,
                    "脚本行数 " + lines.length + " 超过上限 " + props.getMaxLines() + " 行。",
                    "请拆分或精简脚本步骤");
        }

        // 5) 禁用隔离 → 内联执行（仍受静态资源限制约束）
        if (!props.isEnabled()) {
            return runScript(lines);
        }

        // 6) 超时隔离执行：独立线程 + Future.get(timeout) 熔断
        Future<SandboxResult> future = sandboxExecutor.submit(() -> runScript(lines));
        try {
            return future.get(props.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[ScriptSandbox] 脚本执行超时 (>{}ms)，已熔断", props.getTimeoutMs());
            return SandboxResult.fail(AgentErrorCode.SECURITY_SCRIPT_TIMEOUT,
                    "脚本执行超时（超过 " + props.getTimeoutMs() + "ms），已熔断。",
                    "请简化计算步骤后重试");
        } catch (ExecutionException e) {
            log.error("[ScriptSandbox] 脚本执行内部异常", e.getCause());
            return SandboxResult.fail(AgentErrorCode.TOOL_EXECUTION_ERROR,
                    "脚本执行内部错误：" + rootMessage(e), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SandboxResult.fail(AgentErrorCode.TOOL_EXECUTION_ERROR,
                    "脚本执行被中断", null);
        }
    }

    /**
     * 实际逐行执行（运行在隔离线程内）。仅使用线程局部状态，保证线程安全。
     */
    private SandboxResult runScript(String[] lines) {
        Map<String, Double> variables = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        sb.append("📊 计算过程\n\n");

        int stepCount = 0;
        boolean truncated = false;

        for (String rawLine : lines) {
            // 输出长度熔断（防输出膨胀）
            if (sb.length() > props.getMaxOutputLength()) {
                truncated = true;
                break;
            }

            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            // 注释行
            if (line.startsWith("#")) {
                sb.append("  ").append(line.substring(1).trim()).append("\n");
                continue;
            }

            // 单行表达式长度上限：跳过过长行，不中断整体脚本
            if (line.length() > props.getMaxExpressionLength()) {
                sb.append("  [表达式过长，已跳过] ")
                        .append(abbreviate(line, 40)).append("\n");
                continue;
            }

            // 变量赋值：var = expr
            Matcher assignMatcher = ASSIGN_PATTERN.matcher(line);
            if (assignMatcher.matches()) {
                String varName = assignMatcher.group(1);
                String expr = assignMatcher.group(2).trim();

                // 变量数量上限（防内存膨胀）——仅新增变量时计数
                if (!variables.containsKey(varName) && variables.size() >= props.getMaxVariables()) {
                    return SandboxResult.fail(AgentErrorCode.SECURITY_SCRIPT_RESOURCE_LIMIT,
                            "变量数量超过上限 " + props.getMaxVariables() + "。",
                            "请减少变量定义数量");
                }
                try {
                    double val = evalExpression(expr, variables);
                    variables.put(varName, val);
                    stepCount++;
                    sb.append("  ").append(varName).append(" = ").append(formatResult(val)).append("\n");
                } catch (Exception e) {
                    sb.append("  ").append(varName).append(" = [计算失败: ").append(e.getMessage()).append("]\n");
                }
                continue;
            }

            // 纯表达式：直接计算并输出
            try {
                double val = evalExpression(line, variables);
                stepCount++;
                sb.append("  ").append(line).append(" = ").append(formatResult(val)).append("\n");
            } catch (Exception e) {
                sb.append("  [").append(line).append("] 无法解析: ").append(e.getMessage()).append("\n");
            }
        }

        if (stepCount == 0) {
            return SandboxResult.fail(AgentErrorCode.VALIDATION_SCRIPT_EMPTY,
                    "脚本中没有有效的计算语句",
                    "格式：每行一条语句，'变量名 = 表达式' 或直接写表达式");
        }

        if (truncated) {
            sb.append("\n  [输出已截断：超过 ").append(props.getMaxOutputLength()).append(" 字符]\n");
        }

        // 最终结果：取最后一个变量
        if (!variables.isEmpty()) {
            List<Map.Entry<String, Double>> entries = new ArrayList<>(variables.entrySet());
            Map.Entry<String, Double> last = entries.get(entries.size() - 1);
            sb.append("\n✅ 最终结果：").append(last.getKey()).append(" = ").append(formatResult(last.getValue()));
        }

        return SandboxResult.ok(sb.toString());
    }

    /**
     * 使用 exp4j 计算表达式，支持已定义的变量（由 GeneralTools 迁入）。
     */
    private double evalExpression(String expr, Map<String, Double> variables) {
        if (variables.isEmpty()) {
            return new ExpressionBuilder(expr)
                    .implicitMultiplication(false)
                    .build()
                    .evaluate();
        }
        ExpressionBuilder builder = new ExpressionBuilder(expr)
                .implicitMultiplication(false)
                .variables(variables.keySet());
        Expression e = builder.build();
        for (Map.Entry<String, Double> entry : variables.entrySet()) {
            e.setVariable(entry.getKey(), entry.getValue());
        }
        return e.evaluate();
    }

    /**
     * 数值格式化（与 GeneralTools.formatResult 语义一致；属脚本输出表现层，
     * 此处保留独立私有副本以保证沙箱自包含可测，逻辑稳定、改动概率低）。
     */
    private String formatResult(double value) {
        if (Double.isInfinite(value)) return "结果无穷大";
        if (Double.isNaN(value))      return "结果不是有效数字";
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        BigDecimal bd = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
        return bd.stripTrailingZeros().toPlainString();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    @PreDestroy
    public void shutdown() {
        sandboxExecutor.shutdownNow();
        log.info("[ScriptSandbox] 沙箱执行器已关闭");
    }

    /**
     * 沙箱执行结果。成功携带格式化输出；失败携带标准错误码 + 描述 + 建议。
     */
    public record SandboxResult(boolean success, String output,
                                AgentErrorCode errorCode, String message, String hint) {

        public static SandboxResult ok(String output) {
            return new SandboxResult(true, output, null, null, null);
        }

        public static SandboxResult fail(AgentErrorCode code, String message, String hint) {
            return new SandboxResult(false, null, code, message, hint);
        }
    }
}
