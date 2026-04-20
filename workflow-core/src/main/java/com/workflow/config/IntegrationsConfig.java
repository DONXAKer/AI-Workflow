package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class IntegrationsConfig {

    private String youtrack;
    private String gitlab;
    private String github;
    private String openrouter;

    public IntegrationsConfig() {}

    public String getYoutrack() {
        return youtrack;
    }

    public void setYoutrack(String youtrack) {
        this.youtrack = youtrack;
    }

    public String getGitlab() {
        return gitlab;
    }

    public void setGitlab(String gitlab) {
        this.gitlab = gitlab;
    }

    public String getGithub() {
        return github;
    }

    public void setGithub(String github) {
        this.github = github;
    }

    public String getOpenrouter() {
        return openrouter;
    }

    public void setOpenrouter(String openrouter) {
        this.openrouter = openrouter;
    }
}
