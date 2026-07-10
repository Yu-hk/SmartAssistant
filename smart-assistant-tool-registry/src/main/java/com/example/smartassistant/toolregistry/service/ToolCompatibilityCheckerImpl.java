package com.example.smartassistant.toolregistry.service;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.compat.CompatibilityResult;
import com.example.smartassistant.common.gateway.tool.compat.ToolCompatibilityChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 工具兼容性检查实现（REQ-04）。
 * <p>
 * v1 基于元数据字段比对，判定新旧 {@link ToolDefinition} 之间的兼容性：
 * <ul>
 *   <li><b>BREAKING</b>（需升级 MAJOR）：
 *     <ul>
 *       <li>风险等级降级（HIGH→MEDIUM 等）</li>
 *       <li>超时缩短</li>
 *       <li>scopes 收缩（删除已有 scope）</li>
 *       <li>needsApproval 由 false→true</li>
 *     </ul>
 *   </li>
 *   <li><b>COMPATIBLE</b>：其他变更（超时增大、scopes 扩展、rateLimit 调整等）</li>
 * </ul>
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-15
 */
@Service
public class ToolCompatibilityCheckerImpl implements ToolCompatibilityChecker {

    private static final Logger log = LoggerFactory.getLogger(ToolCompatibilityCheckerImpl.class);

    /** ThreadLocal 存储最近一次 check 的原因，确保线程安全 */
    private final ThreadLocal<String> reasonHolder = ThreadLocal.withInitial(() -> "N/A");

    @Override
    public CompatibilityResult check(ToolDefinition oldDef, ToolDefinition newDef) {
        // 1. 风险等级降级（ordinal 减小 = 风险降低）
        if (newDef.getRiskLevel().ordinal() < oldDef.getRiskLevel().ordinal()) {
            String reason = String.format("风险等级降级: %s → %s",
                    oldDef.getRiskLevel(), newDef.getRiskLevel());
            reasonHolder.set(reason);
            log.debug("[ToolCompatibilityCheckerImpl] BREAKING: {}", reason);
            return CompatibilityResult.BREAKING;
        }

        // 2. 超时缩短
        if (newDef.getTimeout().compareTo(oldDef.getTimeout()) < 0) {
            String reason = String.format("超时缩短: %s → %s",
                    oldDef.getTimeout(), newDef.getTimeout());
            reasonHolder.set(reason);
            log.debug("[ToolCompatibilityCheckerImpl] BREAKING: {}", reason);
            return CompatibilityResult.BREAKING;
        }

        // 3. scopes 收缩（旧 scopes 中有被删除的）
        Set<String> oldScopes = toSet(oldDef.getScopes());
        Set<String> newScopes = toSet(newDef.getScopes());
        if (!newScopes.containsAll(oldScopes)) {
            Set<String> removed = new HashSet<>(oldScopes);
            removed.removeAll(newScopes);
            String reason = "scopes 收缩: 移除 " + removed;
            reasonHolder.set(reason);
            log.debug("[ToolCompatibilityCheckerImpl] BREAKING: {}", reason);
            return CompatibilityResult.BREAKING;
        }

        // 4. needsApproval 由 false → true
        if (!oldDef.isNeedsApproval() && newDef.isNeedsApproval()) {
            String reason = "needsApproval 由 false 变为 true";
            reasonHolder.set(reason);
            log.debug("[ToolCompatibilityCheckerImpl] BREAKING: {}", reason);
            return CompatibilityResult.BREAKING;
        }

        // 其他变更视为兼容
        reasonHolder.set("无破坏性变更");
        return CompatibilityResult.COMPATIBLE;
    }

    @Override
    public String getReason() {
        return reasonHolder.get();
    }

    /**
     * 将 String 数组转为 Set，null 安全。
     *
     * @param arr 字符串数组
     * @return 不可变的 Set（null 输入返回空 Set）
     */
    private static Set<String> toSet(String[] arr) {
        if (arr == null || arr.length == 0) {
            return new HashSet<>();
        }
        return new HashSet<>(Arrays.asList(arr));
    }
}
