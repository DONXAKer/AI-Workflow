package com.workflow.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-block escalation cursor stored in {@code PipelineRun.escalationStateJson}.
 *
 * <p>{@link #stepIndex()} is the index into the resolved ladder; {@link #attemptsAtCurrentStep()}
 * counts retries within the current step (relevant for {@code cloud} steps with their own
 * {@code max_iterations}). When {@code attemptsAtCurrentStep} reaches the step's max, we
 * advance to {@code stepIndex + 1}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EscalationState(int stepIndex, int attemptsAtCurrentStep) {

    public static EscalationState initial() { return new EscalationState(0, 0); }

    @JsonCreator
    public EscalationState(@JsonProperty("stepIndex") int stepIndex,
                           @JsonProperty("attemptsAtCurrentStep") int attemptsAtCurrentStep) {
        this.stepIndex = Math.max(0, stepIndex);
        this.attemptsAtCurrentStep = Math.max(0, attemptsAtCurrentStep);
    }

    public EscalationState incrementAttempt() {
        return new EscalationState(stepIndex, attemptsAtCurrentStep + 1);
    }

    public EscalationState advanceStep() {
        return new EscalationState(stepIndex + 1, 0);
    }
}
