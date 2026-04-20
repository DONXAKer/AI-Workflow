package com.workflow.notifications;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class NotificationChannelRegistry {

    private final Map<String, NotificationChannel> byName = new HashMap<>();

    @Autowired
    public NotificationChannelRegistry(List<NotificationChannel> channels) {
        for (NotificationChannel c : channels) byName.put(c.channelName().toLowerCase(), c);
    }

    public NotificationChannel get(String name) {
        NotificationChannel c = byName.get(name.toLowerCase());
        if (c == null) throw new IllegalArgumentException("No notification channel: " + name
            + " (available: " + byName.keySet() + ")");
        return c;
    }

    public boolean supports(String name) {
        return name != null && byName.containsKey(name.toLowerCase());
    }
}
