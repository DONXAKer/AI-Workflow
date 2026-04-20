package com.workflow.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Collects every {@link Tool} Spring bean and exposes lookup by name.
 *
 * <p>Distinct from {@code SkillRegistry}: skills are the legacy LLM-callable layer bound
 * to {@code AgentProfile}s; tools are the native agentic-loop primitives (Read, Edit,
 * Write, Bash, Glob, Grep) used by {@code agent_with_tools} blocks. The two registries do
 * not share a namespace.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> byName;

    @Autowired
    public ToolRegistry(List<Tool> tools) {
        this.byName = tools.stream()
            .collect(Collectors.toUnmodifiableMap(Tool::name, t -> t));
        log.info("Registered {} native tools: {}", byName.size(), byName.keySet());
    }

    public Tool get(String name) {
        Tool t = byName.get(name);
        if (t == null) {
            throw new IllegalArgumentException("unknown tool: '" + name
                + "' — registered: " + byName.keySet());
        }
        return t;
    }

    public boolean has(String name) {
        return byName.containsKey(name);
    }

    public Collection<Tool> all() {
        return Collections.unmodifiableCollection(byName.values());
    }

    public List<Tool> resolve(List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        return names.stream().map(this::get).toList();
    }
}
