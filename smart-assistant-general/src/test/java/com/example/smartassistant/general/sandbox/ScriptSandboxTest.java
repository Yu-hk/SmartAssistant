/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.sandbox;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.general.sandbox.ScriptSandbox.SandboxResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ScriptSandbox} 单元测试。
 *
 * <p>验证脚本运行时沙箱的三层防御：危险关键字黑名单、静态资源限制、超时熔断。</p>
 *
 * <h3>变更前后对比</h3>
 * <table border="1">
 *   <caption>executeScript 执行模型变更</caption>
 *   <tr><th>维度</th><th>变更前（内联执行）</th><th>变更后（ScriptSandbox）</th></tr>
 *   <tr><td>执行线程</td><td>Tomcat 工作线程同步执行</td><td>独立虚拟线程，Future.get 超时熔断</td></tr>
 *   <tr><td>超时控制</td><td>无（可挂死请求线程）</td><td>默认 2000ms，超时返回 SCRIPT_TIMEOUT</td></tr>
 *   <tr><td>脚本长度</td><td>无限制</td><td>≤2000 字符，超限 SCRIPT_RESOURCE_LIMIT</td></tr>
 *   <tr><td>行数</td><td>无限制（可 CPU 风暴）</td><td>≤50 行，超限 SCRIPT_RESOURCE_LIMIT</td></tr>
 *   <tr><td>变量数</td><td>无限制（可内存膨胀）</td><td>≤50 个，超限 SCRIPT_RESOURCE_LIMIT</td></tr>
 *   <tr><td>单行长度</td><td>无限制</td><td>≤200 字符，过长行跳过</td></tr>
 *   <tr><td>输出长度</td><td>无限制</td><td>≤8000 字符，超限截断</td></tr>
 *   <tr><td>危险关键字</td><td>正则黑名单（唯一防线）</td><td>正则黑名单（防御纵深之一）</td></tr>
 *   <tr><td>错误返回</td><td>纯文本/部分结构化</td><td>统一标准错误码 SandboxResult</td></tr>
 * </table>
 */
class ScriptSandboxTest {

    private final ScriptSandbox sandbox = new ScriptSandbox(new ScriptSandboxProperties());

    // ========== 正常执行路径 ==========

    @Test
    void compoundInterest_producesFinalResult() {
        SandboxResult r = sandbox.execute(
                "principal = 10000\nrate = 0.045\nyears = 5\nresult = principal * (1 + rate)^years");
        assertTrue(r.success());
        assertTrue(r.output().contains("✅ 最终结果"));
        assertTrue(r.output().contains("result ="));
    }

    @Test
    void pureExpression_evaluated() {
        SandboxResult r = sandbox.execute("3 + 4 * 2");
        assertTrue(r.success());
        assertTrue(r.output().contains("= 11"));
    }

    @Test
    void variableReferenceAcrossLines() {
        SandboxResult r = sandbox.execute("a = 3\nb = 4\nc = sqrt(a^2+b^2)");
        assertTrue(r.success());
        assertTrue(r.output().contains("c = 5"));
    }

    @Test
    void commentLine_rendered() {
        SandboxResult r = sandbox.execute("# 计算圆面积\nr = 2\narea = 3.14159 * r^2");
        assertTrue(r.success());
        assertTrue(r.output().contains("计算圆面积"));
    }

    @Test
    void divisionByZero_returnsFailure() {
        // exp4j 的 1/0 抛出 ArithmeticException，沙箱捕捉后 stepCount=0 → VALIDATION_SCRIPT_EMPTY
        SandboxResult r = sandbox.execute("x = 1/0");
        assertFalse(r.success());
        assertEquals(AgentErrorCode.VALIDATION_SCRIPT_EMPTY, r.errorCode());
        assertTrue(r.message().contains("没有有效的计算语句"));
    }

    // ========== 输入校验 ==========

    @Test
    void emptyScript_rejected() {
        SandboxResult r = sandbox.execute("");
        assertFalse(r.success());
        assertEquals(AgentErrorCode.VALIDATION_SCRIPT_EMPTY, r.errorCode());
    }

    @Test
    void nullScript_rejected() {
        SandboxResult r = sandbox.execute(null);
        assertFalse(r.success());
        assertEquals(AgentErrorCode.VALIDATION_SCRIPT_EMPTY, r.errorCode());
    }

    // ========== 安全黑名单（防御纵深） ==========

    @Test
    void dangerousKeyword_rejected() {
        SandboxResult r = sandbox.execute("y = system(1)");
        assertFalse(r.success());
        assertEquals(AgentErrorCode.SECURITY_SCRIPT_REJECTED, r.errorCode());
    }

    // ========== 资源限制 ==========

    @Test
    void scriptTooLong_rejected() {
        String huge = "1+".repeat(1001); // 2002 字符 > 2000 上限
        SandboxResult r = sandbox.execute(huge);
        assertFalse(r.success());
        assertEquals(AgentErrorCode.SECURITY_SCRIPT_RESOURCE_LIMIT, r.errorCode());
        assertTrue(r.message().contains("长度"));
    }

    @Test
    void tooManyLines_rejected() {
        String manyLines = "a=1\n".repeat(60); // 60 行 > 50 上限，总长 240 < 2000
        SandboxResult r = sandbox.execute(manyLines);
        assertFalse(r.success());
        assertEquals(AgentErrorCode.SECURITY_SCRIPT_RESOURCE_LIMIT, r.errorCode());
        assertTrue(r.message().contains("行数"));
    }

    @Test
    void tooManyVariables_rejected() {
        ScriptSandboxProperties p = new ScriptSandboxProperties();
        p.setMaxVariables(3);
        p.setMaxLines(100); // 放开行数，单独验证变量数限制
        ScriptSandbox sb = new ScriptSandbox(p);

        SandboxResult r = sb.execute("a=1\nb=2\nc=3\nd=4"); // 4 个变量 > 3 上限
        assertFalse(r.success());
        assertEquals(AgentErrorCode.SECURITY_SCRIPT_RESOURCE_LIMIT, r.errorCode());
        assertTrue(r.message().contains("变量"));
        sb.shutdown();
    }

    @Test
    void expressionTooLong_skippedButScriptSucceeds() {
        ScriptSandboxProperties p = new ScriptSandboxProperties();
        p.setMaxExpressionLength(10);
        ScriptSandbox sb = new ScriptSandbox(p);

        // 第一行有效（5 字符），第二行过长（>10 字符）被跳过
        SandboxResult r = sb.execute("a = 1\nb = 1+1+1+1+1+1+1+1+1+1+1");
        assertTrue(r.success());
        assertTrue(r.output().contains("表达式过长"));
        sb.shutdown();
    }

    // ========== 禁用沙箱 → 内联回退 ==========

    @Test
    void disabledSandbox_runsInline() {
        ScriptSandboxProperties p = new ScriptSandboxProperties();
        p.setEnabled(false);
        ScriptSandbox sb = new ScriptSandbox(p);

        SandboxResult r = sb.execute("x = 2 * 3");
        assertTrue(r.success());
        assertTrue(r.output().contains("= 6"));
        sb.shutdown();
    }

    // ========== 超时保护：保证调用方线程不被挂死 ==========

    @Test
    void timeoutDoesNotHangCaller() {
        ScriptSandboxProperties p = new ScriptSandboxProperties();
        p.setTimeoutMs(1); // 极小超时，验证请求线程在有界时间内被释放
        ScriptSandbox sb = new ScriptSandbox(p);

        long start = System.currentTimeMillis();
        SandboxResult r = sb.execute("a=1\nb=2\nc=a+b\nd=c*2\ne=d/3");
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(r);
        // 无论是正常完成还是超时熔断，调用方都必须在有界时间内返回（不被挂死）
        assertTrue(elapsed < 2000, "调用方线程应在有界时间内返回，实际耗时 " + elapsed + "ms");
        assertTrue(r.success() || r.errorCode() == AgentErrorCode.SECURITY_SCRIPT_TIMEOUT);
        sb.shutdown();
    }
}
