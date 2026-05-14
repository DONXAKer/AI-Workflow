package com.workflow.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Single step in an escalation ladder for a failed verify / loopback block.
 * Polymorphic on {@code tier}: {@code cloud} switches the block to a heavier
 * model and re-runs; {@code human} pauses the run for operator approval.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "tier", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = EscalationStep.Cloud.class, name = "cloud"),
        @JsonSubTypes.Type(value = EscalationStep.Human.class, name = "human")
})
public sealed interface EscalationStep permits EscalationStep.Cloud, EscalationStep.Human {

    String tier();

    /**
     * Switch the failing block to a more capable model/provider, reset its
     * loop counter, and rerun. Examples:
     * <pre>{tier: cloud, provider: openrouter, model: smart, max_iterations: 2}</pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cloud(
            String provider,
            String model,
            @JsonProperty("max_iterations") @JsonAlias("maxIterations") int maxIterations
    ) implements EscalationStep {
        public Cloud {
            if (provider == null || provider.isBlank()) provider = "openrouter";
            if (model == null || model.isBlank()) model = "smart";
            if (maxIterations <= 0) maxIterations = 2;
        }

        @Override public String tier() { return "cloud"; }
    }

    /**
     * Pause the run for operator approval. {@code timeout} is in seconds;
     * on timeout the run is escalated further or failed (per pipeline policy).
     *
     * <p>Component is named {@code notifyChannels} (not {@code notify}) because Java
     * records auto-generate an accessor of the same name, which would shadow
     * {@link Object#notify()}. YAML still uses key {@code notify} thanks to {@link JsonProperty}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Human(
            @JsonProperty("notify") @JsonAlias("notify_channels") List<String> notifyChannels,
            @JsonProperty("timeout") @JsonAlias("timeout_seconds") long timeoutSeconds
    ) implements EscalationStep {
        public Human {
            notifyChannels = (notifyChannels == null || notifyChannels.isEmpty())
                    ? List.of("ui") : List.copyOf(notifyChannels);
            if (timeoutSeconds <= 0) timeoutSeconds = 86_400L;
        }

        @Override public String tier() { return "human"; }
    }
}
