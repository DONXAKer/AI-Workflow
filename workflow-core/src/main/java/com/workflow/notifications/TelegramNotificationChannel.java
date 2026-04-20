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
import java.util.Map;

@Component
public class TelegramNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationChannel.class);

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override public String channelName() { return "telegram"; }

    @Override
    public void send(NotificationMessage message, Map<String, Object> config) {
        String token = (String) config.get("botToken");
        Object chatIdObj = config.get("chatId");
        if (token == null || chatIdObj == null) {
            log.warn("Telegram notification skipped: botToken and chatId are required");
            return;
        }
        try {
            String text = "*" + message.title() + "*\n" + message.body()
                + (message.link() != null ? "\n[Open](" + message.link() + ")" : "");
            Map<String, Object> payload = Map.of(
                "chat_id", chatIdObj,
                "text", text,
                "parse_mode", "Markdown");
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .timeout(Duration.ofSeconds(10))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) log.warn("Telegram returned {}: {}", res.statusCode(), res.body());
        } catch (Exception e) {
            log.error("Telegram notification failed: {}", e.getMessage());
        }
    }
}
