package com.example.smartassistant.common.gateway.tool.compat;

/**
 * 工具版本不兼容异常（REQ-04）。
 * <p>
 * 当 {@link com.example.smartassistant.common.gateway.tool.ToolDefinition} 发生破坏性变更（BREAKING）
 * 但主版本号未升级时，由 {@link com.example.smartassistant.toolregistry.service.RegistryService}
 * 在注册阶段抛出。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-15
 */
public class IncompatibleVersionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 工具名称 */
    private final String toolName;

    /** 旧版本号 */
    private final String oldVersion;

    /** 新版本号 */
    private final String newVersion;

    /**
     * 构造版本不兼容异常。
     *
     * @param toolName   工具名称
     * @param oldVersion 旧版本号
     * @param newVersion 新版本号
     * @param reason     不兼容原因
     */
    public IncompatibleVersionException(String toolName, String oldVersion,
                                        String newVersion, String reason) {
        super(String.format("工具版本不兼容: tool=%s, %s→%s, 原因: %s",
                toolName, oldVersion, newVersion, reason));
        this.toolName = toolName;
        this.oldVersion = oldVersion;
        this.newVersion = newVersion;
    }

    /**
     * 获取工具名称。
     *
     * @return 工具名称
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取旧版本号。
     *
     * @return 旧版本号
     */
    public String getOldVersion() {
        return oldVersion;
    }

    /**
     * 获取新版本号。
     *
     * @return 新版本号
     */
    public String getNewVersion() {
        return newVersion;
    }
}
