package com.workflow.notifications;

import com.workflow.api.RunWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Delivers notifications via WebSocket to the frontend. {@code context.runId} routes
 * to a run-specific topic; absence fans out to the global topic.
 */
@Component
public class UiNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(UiNotificationChannel.class);

    @Autowired(required = false)
    private RunWebSocketHandler wsHandler;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Override public String channelName() { return "ui"; }

    @Override
    public void send(NotificationMessage message, Map<String, Object> config) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "NOTIFICATION");
            payload.put("severity", message.severity().name());
            payload.put("title", message.title());
            payload.put("body", message.body());
            if (message.link() != null) payload.put("link", message.link());
            if (message.context() != null) payload.putAll(message.context());

            Object runIdObj = message.context() != null ? message.context().get("runId") : null;
            if (runIdObj != null) {
                try {
                    UUID runId = runIdObj instanceof UUID u ? u : UUID.fromString(runIdObj.toString());
                    messagingTemplate.convertAndSend("/topic/runs/" + runId, payload);
                } catch (Exception e) {
                    messagingTemplate.convertAndSend("/topic/runs", payload);
                }
            } else {
                messagingTemplate.convertAndSend("/topic/runs", payload);
            }
        } catch (Exception e) {
            log.error("UI notification failed: {}", e.getMessage());
        }
    }
}
