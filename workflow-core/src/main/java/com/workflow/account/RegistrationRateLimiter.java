package com.workflow.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-IP sliding-window rate limit for self-serve registration — an anti-abuse
 * guard for the public {@code POST /api/auth/register} endpoint.
 *
 * <p>Per-instance (not shared across replicas): adequate for a single-instance launch; a
 * shared store (Redis) is the follow-up if the platform scales horizontally. Limits are
 * configurable via {@code workflow.auth.register-rate-limit} /
 * {@code workflow.auth.register-rate-window-minutes}.
 */
@Component
public class RegistrationRateLimiter {

    /** Hard cap on tracked IPs — prevents the map from growing unbounded. */
    private static final int MAX_TRACKED_IPS = 50_000;

    private final int maxPerWindow;
    private final long windowMs;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RegistrationRateLimiter(
            @Value("${workflow.auth.register-rate-limit:5}") int maxPerWindow,
            @Value("${workflow.auth.register-rate-window-minutes:60}") long windowMinutes) {
        this.maxPerWindow = maxPerWindow;
        this.windowMs = Math.max(1, windowMinutes) * 60_000L;
    }

    /**
     * Records a registration attempt from {@code ip} and reports whether it is allowed.
     * A non-positive configured limit disables the check entirely.
     */
    public boolean tryRegister(String ip) {
        if (ip == null || ip.isBlank() || maxPerWindow <= 0) return true;
        if (hits.size() > MAX_TRACKED_IPS) hits.clear();   // crude overflow guard
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
                q.pollFirst();
            }
            if (q.size() >= maxPerWindow) return false;
            q.addLast(now);
            return true;
        }
    }
}
