package com.workflow.tools;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit row per tool invocation inside a tool-use loop. Correlates with
 * {@link com.workflow.llm.LlmCall} by {@code (runId, blockId, iteration)}.
 *
 * <p>Stored as plain JSON text rather than structured columns so additions to tool
 * inputs/outputs do not need schema migrations — analytics parses the JSON on read.
 */
@Entity
@Table(name = "tool_call_audit", indexes = {
    @Index(name = "idx_toolcall_timestamp", columnList = "timestamp"),
    @Index(name = "idx_toolcall_run", columnList = "runId"),
    @Index(name = "idx_toolcall_tool", columnList = "toolName"),
})
public class ToolCallAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    private UUID runId;
    private String blockId;

    /** Loop iteration (1..N), null if invoked outside a tool-use loop. */
    private Integer iteration;

    @Column(nullable = false)
    private String toolName;

    /** LLM-issued tool_use id, used to correlate call/result in provider logs. */
    private String toolUseId;

    @Column(columnDefinition = "TEXT")
    private String inputJson;

    @Column(columnDefinition = "TEXT")
    private String outputText;

    @Column(nullable = false)
    private boolean isError;

    private int durationMs;

    @Column(nullable = false)
    private String projectSlug = "default";

    public ToolCallAudit() {}

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant t) { this.timestamp = t; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getBlockId() { return blockId; }
    public void setBlockId(String blockId) { this.blockId = blockId; }
    public Integer getIteration() { return iteration; }
    public void setIteration(Integer iteration) { this.iteration = iteration; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getToolUseId() { return toolUseId; }
    public void setToolUseId(String toolUseId) { this.toolUseId = toolUseId; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }
    public String getOutputText() { return outputText; }
    public void setOutputText(String outputText) { this.outputText = outputText; }
    public boolean isError() { return isError; }
    public void setError(boolean error) { this.isError = error; }
    public int getDurationMs() { return durationMs; }
    public void setDurationMs(int durationMs) { this.durationMs = durationMs; }
    public String getProjectSlug() { return projectSlug; }
    public void setProjectSlug(String projectSlug) {
        this.projectSlug = projectSlug != null ? projectSlug : "default";
    }
}
