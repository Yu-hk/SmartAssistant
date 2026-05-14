package com.example.smartassistant.router.model;

public class SubTaskResult {
    private String taskId;
    private String description;
    private String agentName;
    private String result;
    private boolean success;

    public SubTaskResult() {}

    public SubTaskResult(String taskId, String description, String agentName, String result, boolean success) {
        this.taskId = taskId;
        this.description = description;
        this.agentName = agentName;
        this.result = result;
        this.success = success;
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
}
