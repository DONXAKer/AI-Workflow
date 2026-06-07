package com.workflow.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple deduplication guard for outbound notifications.
 *
 * <p>Prevents notification storms during high-frequency loopback runs: without this, a
 * block that fails 50 times in a loopback loop would fire 50 identical Slack/Telegram
 * messages and cause operators to mute the channel.
 *
 * <p>The dedup key is {@code runId + ":" + blockId} so all failures of the same block
 * within one run share a cooldown window, regardless of the exact error message.
 * A failure from a different block or a different run always gets its own window.
 *
 * <p>Stale entries older than twice the cooldown are evicted lazily on each
 * {@link #shouldSend} call to prevent unbounded memory growth on long-running processes.
 */
@Component
public class NotificationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(NotificationRateLimiter.class);

    @Value("${workflow.notifications.cooldown-minutes:5}")
    private int cooldownMinutes;

    private final ConcurrentHashMap<String, Instant> lastSentAt = new ConcurrentHashMap<>();

    /**
     * Returns {@code true} if the notification should be sent (either first time, or
     * outside the cooldown window). Records the current time for the key when returning
     * {@code true}.
     *
     * @param runId   pipeline run id
     * @param blockId block that generated the event
     */
    public boolean shouldSend(String runId, String blockId) {
        String key = (runId != null ? runId : "") + ":" + (blockId != null ? blockId : "");
        Instant now = Instant.now();
        Duration cooldown = Duration.ofMinutes(cooldownMinutes);

        evictStale(now, cooldown.multipliedBy(2));

        Instant last = lastSentAt.get(key);
        if (last != null && Duration.between(last, now).compareTo(cooldown) < 0) {
            log.debug("Notification suppressed (cooldown) for run={} block={}", runId, blockId);
            return false;
        }
        lastSentAt.put(key, now);
        return true;
    }

    private void evictStale(Instant now, Duration maxAge) {
        Iterator<Map.Entry<String, Instant>> it = lastSentAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Instant> entry = it.next();
            if (Duration.between(entry.getValue(), now).compareTo(maxAge) > 0) {
                it.remove();
            }
        }
    }
}
