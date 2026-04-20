package com.workflow.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Email stub. Wiring JavaMailSender + SMTP config is a follow-up — the channel exists
 * so YAML can declare {@code email:} in notification_channels without failing startup.
 */
@Component
public class EmailNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationChannel.class);

    @Override public String channelName() { return "email"; }

    @Override
    public void send(NotificationMessage message, Map<String, Object> config) {
        log.info("[email-stub] {} / {} → {}", message.severity(), message.title(),
            config.getOrDefault("to", "(no recipient configured)"));
    }
}
