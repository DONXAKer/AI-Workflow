package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Retry policy for transient external failures. Attached to a block via YAML:
 * <pre>
 * retry:
 *   max_attempts: 3
 *   backoff_ms: 2000
 *   max_backoff_ms: 30000
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RetryConfig {
    @JsonProperty("max_attempts")
    private int maxAttempts = 3;

    @JsonProperty("backoff_ms")
    private long backoffMs = 1000;

    @JsonProperty("max_backoff_ms")
    private long maxBackoffMs = 30000;

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getBackoffMs() { return backoffMs; }
    public void setBackoffMs(long backoffMs) { this.backoffMs = backoffMs; }
    public long getMaxBackoffMs() { return maxBackoffMs; }
    public void setMaxBackoffMs(long maxBackoffMs) { this.maxBackoffMs = maxBackoffMs; }
}
