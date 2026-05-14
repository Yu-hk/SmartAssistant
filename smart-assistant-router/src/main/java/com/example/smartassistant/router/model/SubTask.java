package com.example.smartassistant.router.model;

import java.util.*;

public class SubTask {
    private String id;
    private String description;
    private String targetAgent;
    private List<String> dependsOn;

    public SubTask() {}

    public SubTask(String id, String description, String targetAgent) {
        this(id, description, targetAgent, List.of());
    }

    public SubTask(String id, String description, String targetAgent, List<String> dependsOn) {
        this.id = id;
        this.description = description;
        this.targetAgent = targetAgent;
        this.dependsOn = dependsOn;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTargetAgent() { return targetAgent; }
    public void setTargetAgent(String targetAgent) { this.targetAgent = targetAgent; }
    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }
}
