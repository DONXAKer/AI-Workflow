package com.workflow.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.workflow.model.AgentProfile;
import com.workflow.model.AgentProfileRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Loads built-in skills from {@code resources/skills/*.yaml} on startup.
 *
 * <p>Rules (Q21 Option C — built-in + custom DB):
 * <ul>
 *   <li>Each YAML file defines a skill profile (role, tools, knowledge, preset, useExamples).</li>
 *   <li>On startup: if a profile with the same name exists in DB, DB wins (operator overrides
 *       via UI take precedence). Otherwise the built-in is upserted with {@code builtin=true}.</li>
 *   <li>Built-in profiles are read-only in the UI (enforced at the controller layer later).</li>
 * </ul>
 */
@Component
public class BuiltinSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(BuiltinSkillLoader.class);

    private final YAMLMapper yamlMapper = new YAMLMapper();

    @Autowired
    private AgentProfileRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void load() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/*.yaml");
            int loaded = 0;
            for (Resource r : resources) {
                try (InputStream in = r.getInputStream()) {
                    Map<String, Object> parsed = yamlMapper.readValue(in, Map.class);
                    upsertIfMissing(parsed, r.getFilename());
                    loaded++;
                } catch (Exception e) {
                    log.error("Failed to load built-in skill {}: {}", r.getFilename(), e.getMessage());
                }
            }
            log.info("Built-in skills loaded: {}", loaded);
        } catch (Exception e) {
            log.warn("No built-in skills found: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void upsertIfMissing(Map<String, Object> yaml, String source) throws Exception {
        String name = (String) yaml.get("name");
        if (name == null || name.isBlank()) {
            log.warn("Skipping skill from {}: missing 'name'", source);
            return;
        }

        AgentProfile profile = repository.findByName(name).orElse(null);
        if (profile != null && !profile.isBuiltin()) {
            // Operator-customised — don't overwrite.
            return;
        }
        if (profile == null) profile = new AgentProfile();

        profile.setName(name);
        profile.setDisplayName(stringOr(yaml.get("displayName"), name));
        profile.setDescription(stringOr(yaml.get("description"), ""));
        profile.setRolePrompt(stringOr(yaml.get("rolePrompt"), ""));
        profile.setRecommendedPreset(stringOr(yaml.get("recommendedPreset"), "smart"));

        Object skills = yaml.get("skills");
        profile.setSkillNames(skills instanceof List<?> l ? ((List<Object>) l).stream().map(Object::toString).toList() : List.of());

        Object ks = yaml.get("knowledgeSources");
        profile.setKnowledgeSources(ks instanceof List<?> l ? ((List<Object>) l).stream().map(Object::toString).toList() : List.of());

        Object useExamples = yaml.get("useExamples");
        profile.setUseExamples(Boolean.TRUE.equals(useExamples));

        profile.setBuiltin(true);
        repository.save(profile);
    }

    private String stringOr(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }
}
