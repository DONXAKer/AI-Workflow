package com.workflow.core;

import java.time.Instant;

/**
 * Scalar projection used by the GET /api/runs list endpoint.
 * Avoids N+1 queries by not loading any collection associations.
 * blockCount is derived from a subquery in the repository JPQL.
 * id is returned as String to avoid byte[]→UUID conversion issues with H2 native queries.
 */
public interface PipelineRunSummary {
    String getId();
    String getPipelineName();
    String getRequirement();
    String getStatus();
    String getCurrentBlock();
    String getError();
    Instant getStartedAt();
    Instant getCompletedAt();
    long getBlockCount();
}
