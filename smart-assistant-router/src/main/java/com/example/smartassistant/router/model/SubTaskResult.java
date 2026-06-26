package com.example.smartassistant.router.model;

import java.util.List;
import java.util.Map;

public class SubTaskResult {
    private String taskId;
    private String description;
    private String agentName;
    private String result;
    private boolean success;
    private List<String> realTitles;
    private Map<String, String> tagsByTitle;
    /** ⭐ Handoff 交接命令：执行完毕后如果需显式移交其他 Agent，非空表示有交接请求 */
    private HandoffCommand handoffCommand;

    public SubTaskResult() {}

    public SubTaskResult(String taskId, String description, String agentName, String result, boolean success) {
        this(taskId, description, agentName, result, success, List.of(), Map.of());
    }

    public SubTaskResult(String taskId, String description, String agentName, String result, boolean success, List<String> realTitles) {
        this(taskId, description, agentName, result, success, realTitles, Map.of());
    }

    public SubTaskResult(String taskId, String description, String agentName, String result, boolean success, List<String> realTitles, Map<String, String> tagsByTitle) {
        this.taskId = taskId;
        this.description = description;
        this.agentName = agentName;
        this.result = result;
        this.success = success;
        this.realTitles = realTitles != null ? realTitles : List.of();
        this.tagsByTitle = tagsByTitle != null ? tagsByTitle : Map.of();
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public List<String> getRealTitles() { return realTitles; }
    public void setRealTitles(List<String> realTitles) { this.realTitles = realTitles != null ? realTitles : List.of(); }
    public Map<String, String> getTagsByTitle() { return tagsByTitle; }
    public void setTagsByTitle(Map<String, String> tagsByTitle) { this.tagsByTitle = tagsByTitle != null ? tagsByTitle : Map.of(); }

    public HandoffCommand getHandoffCommand() { return handoffCommand; }
    public void setHandoffCommand(HandoffCommand handoffCommand) { this.handoffCommand = handoffCommand; }
    public boolean hasHandoff() { return handoffCommand != null; }
}
