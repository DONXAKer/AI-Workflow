package com.workflow.integrations.vcs;

import java.util.Map;

/**
 * Provider-agnostic CI pipeline / workflow run status.
 *
 * @param id      pipeline/run ID
 * @param url     web URL
 * @param status  normalized: success | failed | running | canceled | unknown
 * @param stages  provider-specific stage breakdown
 */
public record CiPipelineStatus(long id, String url, String status, Map<String, Object> stages) {}
