package com.workflow.skills;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Collects all {@link Skill} Spring beans and provides lookup by name.
 */
@Component
public class SkillRegistry {

    private final Map<String, Skill> byName;

    @Autowired
    public SkillRegistry(List<Skill> skills) {
        this.byName = skills.stream()
            .collect(Collectors.toMap(Skill::getName, s -> s));
    }

    /** Returns the skill with the given name, or {@code null} if not registered. */
    public Skill get(String name) {
        return byName.get(name);
    }

    /** Returns skills for the given list of names, silently skipping unknown ones. */
    public List<Skill> resolve(List<String> names) {
        if (names == null || names.isEmpty()) return Collections.emptyList();
        return names.stream()
            .map(byName::get)
            .filter(s -> s != null)
            .collect(Collectors.toList());
    }

    public Map<String, Skill> getAll() {
        return Collections.unmodifiableMap(byName);
    }
}
