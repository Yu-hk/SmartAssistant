/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Phase Gate — 阶段验收门禁（对标文章⑥「代码否决 LLM」机制）。
 *
 * <p>当 LLM 评估器或 {@link SmartReActAgent} 认为当前阶段已完成时，
 * Phase Gate 用纯代码做最终验收：</p>
 * <ul>
 *   <li><b>FILE_EXISTS</b>：指定路径的文件是否存在于磁盘</li>
 *   <li><b>FILE_GLOB_COUNT</b>：按通配符搜索文件，匹配数是否达标</li>
 *   <li><b>SCRIPT_CHECK</b>：指定脚本是否在工具调用日志中被成功执行过</li>
 *   <li><b>USER_CONFIRMATION</b>：是否有匹配的结构化用户确认记录</li>
 * </ul>
 *
 * <p>四项检查全部由纯代码执行，不依赖 LLM 判断。任意一项不通过，
 * 门禁即可否决 LLM 的「done」判定。</p>
 */
public class PhaseGate {

    /** 验收检查项。 */
    public record Check(String id, String type, String target, String description) {
        public static final String TYPE_FILE_EXISTS = "file_exists";
        public static final String TYPE_FILE_GLOB_COUNT = "file_glob_count";
        public static final String TYPE_SCRIPT_CHECK = "script_check";
        public static final String TYPE_USER_CONFIRMATION = "user_confirmation";
    }

    /** 验收结果。 */
    public record CheckResult(String checkId, boolean passed, String reason) {
        public static CheckResult pass(String checkId) {
            return new CheckResult(checkId, true, "通过");
        }

        public static CheckResult fail(String checkId, String reason) {
            return new CheckResult(checkId, false, reason);
        }
    }

    /** 门禁验收结果。 */
    public record GateResult(boolean allPassed, List<CheckResult> results, List<String> failedIds) {
        public static GateResult passed(List<CheckResult> results) {
            return new GateResult(true, results, List.of());
        }

        public static GateResult failed(List<CheckResult> results, List<String> failedIds) {
            return new GateResult(false, results, failedIds);
        }
    }

    /**
     * 执行门禁验收。
     *
     * @param checks       验收检查项列表
     * @param toolCallLog  本轮工具调用日志（用于 SCRIPT_CHECK）
     * @param workspace    工作区根目录（用于文件路径解析）
     * @return {@link GateResult}
     */
    public GateResult verify(List<Check> checks,
                             List<String> toolCallLog,
                             String workspace) {
        if (checks == null || checks.isEmpty()) {
            return GateResult.passed(List.of());
        }

        List<CheckResult> results = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();

        for (Check check : checks) {
            CheckResult r = switch (check.type()) {
                case Check.TYPE_FILE_EXISTS -> checkFileExists(check, workspace);
                case Check.TYPE_FILE_GLOB_COUNT -> checkFileGlobCount(check, workspace);
                case Check.TYPE_SCRIPT_CHECK -> checkScriptInLog(check, toolCallLog);
                case Check.TYPE_USER_CONFIRMATION -> checkUserConfirmation(check, List.of());
                default -> CheckResult.fail(check.id(), "未知验收类型: " + check.type());
            };
            results.add(r);
            if (!r.passed()) {
                failedIds.add(r.checkId());
            }
        }

        return failedIds.isEmpty()
                ? GateResult.passed(results)
                : GateResult.failed(results, failedIds);
    }

    /** 文件存在检查。 */
    private CheckResult checkFileExists(Check check, String workspace) {
        try {
            String target = resolvePath(check.target(), workspace);
            boolean exists = Files.exists(Paths.get(target));
            String reason = exists ? "文件存在: " + target : "文件不存在: " + target;
            return exists
                    ? CheckResult.pass(check.id())
                    : CheckResult.fail(check.id(), reason);
        } catch (Exception e) {
            return CheckResult.fail(check.id(), "文件检查异常: " + e.getMessage());
        }
    }

    /** 文件数量检查（通配符搜索）。 */
    private CheckResult checkFileGlobCount(Check check, String workspace) {
        try {
            String[] parts = check.target().split("\\s+");
            if (parts.length < 2) {
                return CheckResult.fail(check.id(), "格式错误: 需要'通配符 最低数量', 如'**/*.md 3'");
            }
            String glob = parts[0];
            int minCount = Integer.parseInt(parts[parts.length - 1]);

            Path base = workspace != null ? Paths.get(workspace) : Paths.get(".");
            java.nio.file.FileSystem fs = base.getFileSystem();
            java.nio.file.PathMatcher matcher = fs.getPathMatcher("glob:" + glob);

            long matched;
            try (var stream = Files.walk(base)) {
                matched = stream.filter(Files::isRegularFile)
                        .filter(matcher::matches)
                        .limit(minCount + 1)
                        .count();
            }

            boolean passed = matched >= minCount;
            return passed
                    ? CheckResult.pass(check.id())
                    : CheckResult.fail(check.id(), "匹配文件数=" + matched + ", 要求≥" + minCount);
        } catch (Exception e) {
            return CheckResult.fail(check.id(), "文件数量检查异常: " + e.getMessage());
        }
    }

    /** 脚本执行检查：在工具调用日志中搜索指定脚本名。 */
    private CheckResult checkScriptInLog(Check check, List<String> toolCallLog) {
        if (toolCallLog == null || toolCallLog.isEmpty()) {
            return CheckResult.fail(check.id(), "无工具调用日志");
        }
        String scriptName = check.target();
        boolean found = toolCallLog.stream()
                .anyMatch(entry -> entry.contains(scriptName) && !entry.contains("失败"));
        return found
                ? CheckResult.pass(check.id())
                : CheckResult.fail(check.id(), "未找到脚本 '" + scriptName + "' 的成功执行记录");
    }

    /** 用户确认检查。 */
    private CheckResult checkUserConfirmation(Check check, List<String> confirmations) {
        // 默认实现：由调用方传入已确认的 check ID 列表
        boolean confirmed = confirmations != null
                && confirmations.contains(check.id());
        return confirmed
                ? CheckResult.pass(check.id())
                : CheckResult.fail(check.id(), "缺少用户确认: " + check.description());
    }

    /** 解析文件路径：相对路径拼接 workspace。 */
    private static String resolvePath(String target, String workspace) {
        Path p = Paths.get(target);
        if (p.isAbsolute() || workspace == null) {
            return target;
        }
        return Paths.get(workspace, target).normalize().toString();
    }
}
