package com.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties("workflow.prompt-context")
public class PromptContextConfig {

    private List<String> allow = new ArrayList<>(List.of(
        "git *", "find", "ls", "cat", "head", "tail", "tree"
    ));
    private int timeoutMs = 5000;

    public List<String> getAllow() { return allow; }
    public void setAllow(List<String> allow) { this.allow = allow; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
