package com.workflow.notifications;

import java.util.Map;

/**
 * Provider-agnostic outbound notification contract.
 */
public interface NotificationChannel {

    String channelName();

    /** Deliver a message. Implementations must never throw — log + swallow on failure. */
    void send(NotificationMessage message, Map<String, Object> config);
}
