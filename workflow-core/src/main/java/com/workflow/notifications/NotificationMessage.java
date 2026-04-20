package com.workflow.notifications;

import java.util.Map;

/**
 * Normalized notification payload.
 *
 * @param severity low | medium | high | critical — channels may filter by severity
 * @param title    short headline
 * @param body     human-readable body (markdown accepted; channels render per format)
 * @param link     optional deep-link URL
 * @param context  arbitrary key-value pairs (runId, blockId, ...)
 */
public record NotificationMessage(
    Severity severity,
    String title,
    String body,
    String link,
    Map<String, Object> context
) {
    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
}
