package com.workflow.core;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.EscalationConfig;
import com.workflow.config.EscalationStep;
import com.workflow.project.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolves the effective escalation ladder for a block in a run by walking three
 * levels of overrides:
 * <ol>
 *   <li><b>Block-level</b> ({@code verify.on_fail.escalation} in pipeline YAML) —
 *       explicit override or explicit opt-out ({@code escalation: none}) win immediately.</li>
 *   <li><b>Project-level</b> ({@link Project#getEscalationDefaultsJson()}) — JSON array of
 *       polymorphic {@link EscalationStep}, parsed at resolve time.</li>
 *   <li><b>Global</b> ({@code workflow.escalation.defaults} in {@code application.yaml},
 *       bound by {@link EscalationProperties}) — fallback for all unconfigured blocks.</li>
 * </ol>
 *
 * <p>Mirrors the 3-level pattern of {@code ModelPresetResolver} — same mental model.
 */
@Service
public class EscalationResolver {

    private static final Logger log = LoggerFactory.getLogger(EscalationResolver.class);

    private final EscalationProperties properties;
    private final ObjectMapper objectMapper;
    private final JavaType stepListType;

    public EscalationResolver(EscalationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.stepListType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, EscalationStep.class);
    }

    /**
     * Resolves the effective ladder. Returns an empty list iff the block opted out
     * ({@code escalation: none}) or no defaults are configured at any level.
     */
    public List<EscalationStep> resolve(EscalationConfig blockEscalation, Project project) {
        EscalationConfig effective = blockEscalation != null ? blockEscalation : EscalationConfig.defaults();

        return switch (effective.policy()) {
            case EXPLICIT -> effective.steps();
            case NONE -> List.of();
            case DEFAULT -> resolveDefault(project);
        };
    }

    private List<EscalationStep> resolveDefault(Project project) {
        if (project != null) {
            String json = project.getEscalationDefaultsJson();
            if (json != null && !json.isBlank()) {
                try {
                    List<EscalationStep> parsed = objectMapper.readValue(json, stepListType);
                    return parsed != null ? parsed : List.of();
                } catch (Exception e) {
                    log.warn("Project '{}' has invalid escalation_defaults_json — falling back to global defaults. Error: {}",
                            project.getSlug(), e.getMessage());
                }
            }
        }
        return properties.getResolvedDefaults();
    }

    /**
     * Hard cap on cumulative cloud-tier spend per run. Wired through to the
     * cost-tracking integration in Phase B2; surfaced here so callers have a
     * single resolver to consult.
     */
    public double maxBudgetUsd() {
        return properties.getMaxBudgetUsd();
    }

    /** Registers {@link EscalationProperties} as a Spring-managed bean. */
    @Configuration
    @EnableConfigurationProperties(EscalationProperties.class)
    static class Config {}
}
