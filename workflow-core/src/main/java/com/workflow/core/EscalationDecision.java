package com.workflow.core;

import com.workflow.config.EscalationStep;

import java.util.List;
import java.util.Map;

/**
 * Outcome of consulting the escalation ladder when a block exhausts {@code max_iterations}.
 *
 * <p>Three terminal shapes:
 * <ul>
 *   <li>{@link #retryWithCloud} — apply override on target block, reset loop counter, retry.</li>
 *   <li>{@link #pauseForHuman} — pause the run; operator approval resumes or fails it.</li>
 *   <li>{@link #exhausted} — ladder is empty or fully consumed; fall through to original failure.</li>
 * </ul>
 */
public sealed interface EscalationDecision
        permits EscalationDecision.RetryWithCloud,
                EscalationDecision.PauseForHuman,
                EscalationDecision.Exhausted {

    /** Apply a model/provider swap on the target block and retry from scratch. */
    record RetryWithCloud(String targetBlockId, RuntimeOverride override) implements EscalationDecision {}

    /** Pause for operator approval, including a minimal bundle to show in the gate UI. */
    record PauseForHuman(
            String failingBlockId,
            String targetBlockId,
            EscalationStep.Human step,
            Map<String, Object> bundle
    ) implements EscalationDecision {}

    /** No more steps; caller should fail the run as before. */
    record Exhausted(String reason, List<EscalationStep> attemptedLadder) implements EscalationDecision {}

    static EscalationDecision retryWithCloud(String targetBlockId, RuntimeOverride override) {
        return new RetryWithCloud(targetBlockId, override);
    }

    static EscalationDecision pauseForHuman(String failingBlockId, String targetBlockId,
                                             EscalationStep.Human step, Map<String, Object> bundle) {
        return new PauseForHuman(failingBlockId, targetBlockId, step, bundle);
    }

    static EscalationDecision exhausted(String reason, List<EscalationStep> ladder) {
        return new Exhausted(reason, ladder);
    }
}
