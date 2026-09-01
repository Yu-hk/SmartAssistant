package com.example.smartassistant.common.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Unified model and tool invocation budgets. */
@ConfigurationProperties("assistant.call-limits")
public class CallLimitProperties {

    private final Model model = new Model();
    private final Tool tool = new Tool();

    public Model getModel() { return model; }
    public Tool getTool() { return tool; }

    public static class Model {
        private int maxPerRequest = 16;
        private int maxPerSession = 80;
        public int getMaxPerRequest() { return maxPerRequest; }
        public void setMaxPerRequest(int value) { this.maxPerRequest = value; }
        public int getMaxPerSession() { return maxPerSession; }
        public void setMaxPerSession(int value) { this.maxPerSession = value; }
    }

    public static class Tool {
        private int maxPerRequest = 24;
        private int maxPerSession = 120;
        private int springAiMaxPerTool = 8;
        private int springAiMaxTotal = 24;
        public int getMaxPerRequest() { return maxPerRequest; }
        public void setMaxPerRequest(int value) { this.maxPerRequest = value; }
        public int getMaxPerSession() { return maxPerSession; }
        public void setMaxPerSession(int value) { this.maxPerSession = value; }
        public int getSpringAiMaxPerTool() { return springAiMaxPerTool; }
        public void setSpringAiMaxPerTool(int value) { this.springAiMaxPerTool = value; }
        public int getSpringAiMaxTotal() { return springAiMaxTotal; }
        public void setSpringAiMaxTotal(int value) { this.springAiMaxTotal = value; }
    }
}
