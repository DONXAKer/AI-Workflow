package com.workflow.integrations.tracker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring-collected registry of every {@link TaskTracker} bean in the context.
 * Callers resolve adapters by name at runtime.
 */
@Component
public class TaskTrackerRegistry {

    private final Map<String, TaskTracker> byProvider = new HashMap<>();

    @Autowired
    public TaskTrackerRegistry(List<TaskTracker> trackers) {
        for (TaskTracker t : trackers) byProvider.put(t.providerName().toLowerCase(), t);
    }

    public TaskTracker get(String providerName) {
        if (providerName == null) {
            throw new IllegalArgumentException("Tracker provider name is required");
        }
        TaskTracker tracker = byProvider.get(providerName.toLowerCase());
        if (tracker == null) {
            throw new IllegalArgumentException("No task tracker registered for provider: " + providerName
                + " (available: " + byProvider.keySet() + ")");
        }
        return tracker;
    }

    public boolean supports(String providerName) {
        return providerName != null && byProvider.containsKey(providerName.toLowerCase());
    }
}
