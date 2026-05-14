package com.workflow.core;

import com.workflow.config.EscalationStep;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot binding of {@code workflow.escalation.*} — global defaults for the
 * escalation ladder applied when neither block YAML nor {@code Project.escalationDefaultsJson}
 * supplies an explicit override.
 *
 * <p>Field {@link StepDefinition} is a flat POJO so Spring's standard property binder
 * handles it; conversion to the polymorphic {@link EscalationStep} happens in
 * {@link #getResolvedDefaults()} based on {@code tier}.
 */
@ConfigurationProperties(prefix = "workflow.escalation")
public class EscalationProperties {

    /**
     * Hard-coded sane defaults: local-tier fails → cloud-tier smart → human approval gate.
     * Replaced (not merged) when {@code workflow.escalation.defaults} is set in application.yaml.
     */
    private List<StepDefinition> defaults = new ArrayList<>(List.of(
            StepDefinition.cloud("openrouter", "smart", 2),
            StepDefinition.human(List.of("ui"), 86_400L)
    ));

    /**
     * Hard cap on cumulative cloud-tier LLM spend per run, in USD. When exceeded,
     * cloud-tier escalation switches to human approval gate instead of running.
     * Read by future cost-budget integration (Phase B2); kept here so the property
     * exists from PR #1 onwards.
     */
    private double maxBudgetUsd = 10.00;

    public List<StepDefinition> getDefaults() { return defaults; }
    public void setDefaults(List<StepDefinition> defaults) {
        this.defaults = defaults != null ? defaults : new ArrayList<>();
    }

    public double getMaxBudgetUsd() { return maxBudgetUsd; }
    public void setMaxBudgetUsd(double maxBudgetUsd) { this.maxBudgetUsd = maxBudgetUsd; }

    /**
     * Converts the flat property bindings into typed {@link EscalationStep} list.
     * Unknown tier values are dropped with no error — keep startup loud-free; the
     * configured value can be validated by an admin endpoint later.
     */
    public List<EscalationStep> getResolvedDefaults() {
        List<EscalationStep> resolved = new ArrayList<>(defaults.size());
        for (StepDefinition d : defaults) {
            EscalationStep step = d.toStep();
            if (step != null) resolved.add(step);
        }
        return resolved;
    }

    /** Flat POJO for Spring property binding; converted to {@link EscalationStep} on read. */
    public static class StepDefinition {
        private String tier;
        private String provider;
        private String model;
        private Integer maxIterations;
        private List<String> notify;
        private Long timeout;

        public StepDefinition() {}

        static StepDefinition cloud(String provider, String model, int maxIterations) {
            StepDefinition d = new StepDefinition();
            d.tier = "cloud";
            d.provider = provider;
            d.model = model;
            d.maxIterations = maxIterations;
            return d;
        }

        static StepDefinition human(List<String> notify, long timeoutSeconds) {
            StepDefinition d = new StepDefinition();
            d.tier = "human";
            d.notify = notify;
            d.timeout = timeoutSeconds;
            return d;
        }

        EscalationStep toStep() {
            if (tier == null) return null;
            return switch (tier.toLowerCase()) {
                case "cloud" -> new EscalationStep.Cloud(provider, model,
                        maxIterations != null ? maxIterations : 0);
                case "human" -> new EscalationStep.Human(notify,
                        timeout != null ? timeout : 0L);
                default -> null;
            };
        }

        public String getTier() { return tier; }
        public void setTier(String tier) { this.tier = tier; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Integer getMaxIterations() { return maxIterations; }
        public void setMaxIterations(Integer maxIterations) { this.maxIterations = maxIterations; }
        public List<String> getNotify() { return notify; }
        public void setNotify(List<String> notify) { this.notify = notify; }
        public Long getTimeout() { return timeout; }
        public void setTimeout(Long timeout) { this.timeout = timeout; }
    }
}
