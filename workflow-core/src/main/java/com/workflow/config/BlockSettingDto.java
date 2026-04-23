package com.workflow.config;

import java.util.List;

public class BlockSettingDto {

    private String id;
    private boolean enabled = true;
    private boolean approval = false;
    private String profile;
    private List<String> skills;
    private AgentOverride agent;

    public BlockSettingDto() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isApproval() { return approval; }
    public void setApproval(boolean approval) { this.approval = approval; }

    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }

    public AgentOverride getAgent() { return agent; }
    public void setAgent(AgentOverride agent) { this.agent = agent; }

    public static class AgentOverride {
        private String model;
        private Integer maxTokens;
        private Double temperature;
        private String systemPrompt;

        public AgentOverride() {}

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }

        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    }
}
