package com.example.smartassistant.router.model;

import java.util.*;

public class SubTask {
    private String id;
    private String description;
    private String targetAgent;
    private List<String> dependsOn;
    /**
     * ⭐ 验收标准——定义"怎样算完成"。
     * <p>
     * 例如：
     * <ul>
     *   <li>"queryOrder 返回非空结果且含 orderId"</li>
     *   <li>"回答包含城市名和景点列表"</li>
     *   <li>"返回的金额为数字格式"</li>
     * </ul>
     * 由 TaskPlannerService 的 LLM 在规划时产出，
     * 供 ReflectionService / checker 做精准的目标达成度校验。
     * </p>
     */
    private String successCriteria;

    public SubTask() {}

    public SubTask(String id, String description, String targetAgent) {
        this(id, description, targetAgent, List.of(), null);
    }

    public SubTask(String id, String description, String targetAgent, List<String> dependsOn) {
        this(id, description, targetAgent, dependsOn, null);
    }

    public SubTask(String id, String description, String targetAgent, List<String> dependsOn, String successCriteria) {
        this.id = id;
        this.description = description;
        this.targetAgent = targetAgent;
        this.dependsOn = dependsOn;
        this.successCriteria = successCriteria;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTargetAgent() { return targetAgent; }
    public void setTargetAgent(String targetAgent) { this.targetAgent = targetAgent; }
    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }

    public String getSuccessCriteria() { return successCriteria; }
    public void setSuccessCriteria(String successCriteria) { this.successCriteria = successCriteria; }
}
