package com.workflow.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Posts to a Slack incoming webhook. {@code config.webhookUrl} required; {@code channel} optional.
 */
@Component
public class SlackNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SlackNotificationChannel.class);
    private static final String SEV_EMOJI = "🟢🟡🟠🔴";

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override public String channelName() { return "slack"; }

    @Override
    public void send(NotificationMessage message, Map<String, Object> config) {
        String webhookUrl = (String) config.get("webhookUrl");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("Slack notification skipped: no webhookUrl in config");
            return;
        }
        try {
            int sev = message.severity().ordinal();
            String emoji = sev < SEV_EMOJI.length() ? SEV_EMOJI.substring(sev, sev + 1) : "•";

            Map<String, Object> payload = new HashMap<>();
            payload.put("text", emoji + " *" + message.title() + "*\n" + message.body()
                + (message.link() != null ? "\n<" + message.link() + "|Open>" : ""));
            if (config.get("channel") instanceof String ch && !ch.isBlank()) payload.put("channel", ch);

            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .timeout(Duration.ofSeconds(10))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                log.warn("Slack webhook returned {}: {}", res.statusCode(), res.body());
            }
        } catch (Exception e) {
            log.error("Slack notification failed: {}", e.getMessage());
        }
    }
}
