package com.workflow.llm;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-LLM-call record for cost/usage analytics.
 *
 * <p>Populated by {@link LlmClient} after every OpenRouter completion. The run_id and
 * block_id fields are best-effort — when an LLM call happens outside a pipeline context
 * (e.g. RunReturnService feedback structuring) they are null.
 */
@Entity
@Table(name = "llm_call", indexes = {
    @Index(name = "idx_llmcall_timestamp", columnList = "timestamp"),
    @Index(name = "idx_llmcall_run", columnList = "runId"),
    @Index(name = "idx_llmcall_model", columnList = "model"),
})
public class LlmCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    private UUID runId;
    private String blockId;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private int tokensIn;

    @Column(nullable = false)
    private int tokensOut;

    /** USD cost rounded to six decimals. Zero if unavailable. */
    private double costUsd;

    private int durationMs;

    @Column(nullable = false)
    private String projectSlug = "default";

    /**
     * Iteration index within a tool-use loop. Zero for plain {@code complete()} calls
     * (single-shot). 1..N for each API round-trip inside
     * {@code LlmClient.completeWithTools}. Enables per-step audit of agentic blocks.
     */
    @Column(nullable = false)
    private int iteration = 0;

    /**
     * JSON array of tool names invoked in this iteration (e.g. {@code ["Read","Edit"]}).
     * Null for non-tool-use calls. Kept as text to avoid touching the schema when the
     * set of tools grows.
     */
    @Column(columnDefinition = "TEXT")
    private String toolCallsMadeJson;

    public LlmCall() {}

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getBlockId() { return blockId; }
    public void setBlockId(String blockId) { this.blockId = blockId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getTokensIn() { return tokensIn; }
    public void setTokensIn(int tokensIn) { this.tokensIn = tokensIn; }
    public int getTokensOut() { return tokensOut; }
    public void setTokensOut(int tokensOut) { this.tokensOut = tokensOut; }
    public double getCostUsd() { return costUsd; }
    public void setCostUsd(double costUsd) { this.costUsd = costUsd; }
    public int getDurationMs() { return durationMs; }
    public void setDurationMs(int durationMs) { this.durationMs = durationMs; }

    public String getProjectSlug() { return projectSlug; }
    public void setProjectSlug(String projectSlug) {
        this.projectSlug = projectSlug != null ? projectSlug : "default";
    }

    public int getIteration() { return iteration; }
    public void setIteration(int iteration) { this.iteration = iteration; }

    public String getToolCallsMadeJson() { return toolCallsMadeJson; }
    public void setToolCallsMadeJson(String toolCallsMadeJson) { this.toolCallsMadeJson = toolCallsMadeJson; }
}
