package com.example.smartassistant.common.gateway.tool;

/**
 * 工具分层枚举（REQ-03）。
 * <p>
 * 用于把工具划分为三层，决定其治理与可用性来源：
 * <ul>
 *   <li>{@link #CORE} — agent 内部领域逻辑，由各 agent 自有、常驻、不依赖中心 Registry，
 *       且不可被中心置为 DISABLED。默认层。</li>
 *   <li>{@link #SHARED} — 跨 agent 共享基建（如天气、图片、DataGif 等通用工具），
 *       由中心 Registry 统一治理，但被多个 agent 复用，不应下放到单 agent 自管。</li>
 *   <li>{@link #EXTENSION} — 插件 / 第三方 / 动态加载 / 实验性工具，由中心 Registry
 *       全量治理（status / approval / rateLimit / compat / deprecation / health）。</li>
 * </ul>
 *
 * <p>分层对执行路径的影响（见 {@link ToolGateway} 与 {@code SpringToolProvider}）：
 * CORE 工具始终在本地可用、不依赖中心存活；SHARED/EXTENSION 经中心 Registry 的
 * allowlist 过滤并受完整治理，中心不可用时仅 CORE 保证可用。</p>
 *
 * @author Yu-hk
 * @since 2026-07-11
 */
public enum ToolTier {

    /** agent 内部领域逻辑（常驻、不可禁用、不依赖中心） */
    CORE,

    /** 跨 agent 共享基建（中心治理、多 agent 复用） */
    SHARED,

    /** 扩展/插件/第三方/动态工具（中心全量治理） */
    EXTENSION
}
