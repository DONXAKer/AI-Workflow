package com.workflow.preflight;

import java.time.Duration;

/**
 * L1 reachability check for an OpenAI-compatible LLM provider base URL. Behind an interface so
 * unit tests inject a fake instead of hitting the network. The production implementation
 * ({@link HttpReachabilityProbe}) mirrors {@code IntegrationController.testConnectivity}: probe
 * {@code <base>/models}, treating HTTP 200/401 as reachable (401 = auth-gated but the URL is live).
 */
public interface ReachabilityProbe {

    /**
     * @param baseUrl provider base URL (with or without trailing {@code /v1})
     * @param timeout per-attempt connect+read timeout
     * @return reachability outcome; {@link Result#reachable()} false also covers inconclusive
     *         (timeout / connection refused) — the caller surfaces both as a non-blocking WARN
     */
    Result probe(String baseUrl, Duration timeout);

    record Result(boolean reachable, String detail) {}
}
