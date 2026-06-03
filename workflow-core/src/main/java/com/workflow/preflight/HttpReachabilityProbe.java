package com.workflow.preflight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Production {@link ReachabilityProbe}: GET {@code <base>/models}. Reuses the URL-normalisation
 * and 200/401-tolerant logic from {@code IntegrationController.testConnectivity} so an operator
 * sees the same verdict from the pre-run gate as from the "Test connection" button.
 */
@Component
public class HttpReachabilityProbe implements ReachabilityProbe {

    private static final Logger log = LoggerFactory.getLogger(HttpReachabilityProbe.class);

    @Override
    public Result probe(String baseUrl, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new Result(false, "no base URL");
        }

        String normalized = baseUrl.trim().replace(" ", "");
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("<") && normalized.endsWith(">"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        String stripped = normalized.replaceAll("/+$", "");
        String probeUrl = stripped.endsWith("/models")
                ? stripped
                : (stripped.endsWith("/v1") ? stripped : stripped + "/v1") + "/models";

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(probeUrl))
                    .GET()
                    .timeout(timeout)
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            // 401 = reachable but unauthenticated (we send no key) — the URL is correct.
            boolean reachable = code == 200 || code == 401;
            return new Result(reachable,
                    reachable ? "HTTP " + code : "HTTP " + code + " from " + probeUrl);
        } catch (Exception e) {
            // Timeout / DNS / connection refused → inconclusive. Caller treats as WARN.
            log.debug("Reachability probe failed for {}: {}", probeUrl, e.toString());
            return new Result(false, "unreachable: " + e.getMessage());
        }
    }
}
