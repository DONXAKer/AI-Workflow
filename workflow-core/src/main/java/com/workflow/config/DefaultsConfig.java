package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DefaultsConfig {

    private AgentConfig agent;

    public DefaultsConfig() {}

    public AgentConfig getAgent() { return agent; }
    public void setAgent(AgentConfig agent) { this.agent = agent; }
}
