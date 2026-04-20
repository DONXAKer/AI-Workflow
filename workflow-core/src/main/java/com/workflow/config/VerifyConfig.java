package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/** Full configuration for the verify block. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerifyConfig {

    /** ID of the block whose output should be verified. */
    private String subject;

    private List<FieldCheckConfig> checks = new ArrayList<>();

    @JsonProperty("llm_check")
    private LLMCheckConfig llmCheck;

    @JsonProperty("on_fail")
    private OnFailConfig onFail;

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public List<FieldCheckConfig> getChecks() { return checks; }
    public void setChecks(List<FieldCheckConfig> checks) {
        this.checks = checks != null ? checks : new ArrayList<>();
    }

    public LLMCheckConfig getLlmCheck() { return llmCheck; }
    public void setLlmCheck(LLMCheckConfig llmCheck) { this.llmCheck = llmCheck; }

    public OnFailConfig getOnFail() { return onFail; }
    public void setOnFail(OnFailConfig onFail) { this.onFail = onFail; }
}
