/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 脚本沙箱配置（{@code sandbox.script.*}）。
 *
 * <p>为 {@link ScriptSandbox} 提供运行时资源限制与超时熔断参数，全部可在
 * {@code application.yml} 中外部化覆盖，避免硬编码。默认值面向「数学计算脚本」
 * 这一受限场景，足以覆盖正常复利/工程公式计算，同时拦截 DoS 级别的超大脚本。</p>
 */
@Component
@ConfigurationProperties(prefix = "sandbox.script")
public class ScriptSandboxProperties {

    /** 是否启用超时隔离执行；false 时退化为内联执行（仅保留静态资源校验，不开线程） */
    private boolean enabled = true;

    /** 单次脚本执行超时（毫秒）。超时后熔断，立即释放请求线程，避免 Tomcat 工作线程被长任务占用 */
    private long timeoutMs = 2000;

    /** 脚本总字符上限，超过直接拒绝 */
    private int maxScriptLength = 2000;

    /** 最大语句行数，超过直接拒绝 */
    private int maxLines = 50;

    /** 最大变量数，防止变量表无界增长导致内存膨胀 */
    private int maxVariables = 50;

    /** 单行表达式字符上限，超过的行跳过执行（不中断整体脚本） */
    private int maxExpressionLength = 200;

    /** 输出字符上限，防止结果文本无界膨胀 */
    private int maxOutputLength = 8000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getMaxScriptLength() { return maxScriptLength; }
    public void setMaxScriptLength(int maxScriptLength) { this.maxScriptLength = maxScriptLength; }

    public int getMaxLines() { return maxLines; }
    public void setMaxLines(int maxLines) { this.maxLines = maxLines; }

    public int getMaxVariables() { return maxVariables; }
    public void setMaxVariables(int maxVariables) { this.maxVariables = maxVariables; }

    public int getMaxExpressionLength() { return maxExpressionLength; }
    public void setMaxExpressionLength(int maxExpressionLength) { this.maxExpressionLength = maxExpressionLength; }

    public int getMaxOutputLength() { return maxOutputLength; }
    public void setMaxOutputLength(int maxOutputLength) { this.maxOutputLength = maxOutputLength; }
}
