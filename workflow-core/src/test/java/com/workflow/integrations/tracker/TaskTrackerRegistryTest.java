package com.workflow.integrations.tracker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskTrackerRegistryTest {

    private static TaskTracker stub(String name) {
        return new TaskTracker() {
            @Override public String providerName() { return name; }
            @Override public TaskIssue fetchIssue(String id, Map<String, Object> c) { return TaskIssue.empty(id); }
            @Override public List<TaskIssue> listSubtasks(String p, Map<String, Object> c) { return List.of(); }
            @Override public List<String> createSubtasks(String p, List<SubtaskSpec> s, Map<String, Object> c) { return List.of(); }
            @Override public void updateStatus(String i, String s, Map<String, Object> c) {}
            @Override public void addComment(String i, String txt, Map<String, Object> c) {}
        };
    }

    @Test
    void registryResolvesAdapterByProviderName() {
        TaskTrackerRegistry registry = new TaskTrackerRegistry(List.of(stub("youtrack"), stub("jira")));
        assertEquals("youtrack", registry.get("youtrack").providerName());
        assertEquals("jira", registry.get("jira").providerName());
    }

    @Test
    void lookupIsCaseInsensitive() {
        TaskTrackerRegistry registry = new TaskTrackerRegistry(List.of(stub("youtrack")));
        assertEquals("youtrack", registry.get("YouTrack").providerName());
        assertEquals("youtrack", registry.get("YOUTRACK").providerName());
    }

    @Test
    void unknownProviderThrowsWithAvailableList() {
        TaskTrackerRegistry registry = new TaskTrackerRegistry(List.of(stub("youtrack"), stub("jira")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> registry.get("linear"));
        assertTrue(ex.getMessage().contains("linear"));
        assertTrue(ex.getMessage().contains("youtrack") || ex.getMessage().contains("jira"));
    }

    @Test
    void supportsReportsKnownAdapters() {
        TaskTrackerRegistry registry = new TaskTrackerRegistry(List.of(stub("youtrack")));
        assertTrue(registry.supports("youtrack"));
        assertTrue(registry.supports("YouTrack"));
        assertFalse(registry.supports("linear"));
        assertFalse(registry.supports(null));
    }

    @Test
    void nullProviderThrows() {
        TaskTrackerRegistry registry = new TaskTrackerRegistry(List.of());
        assertThrows(IllegalArgumentException.class, () -> registry.get(null));
    }
}
