package com.workflow.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.config.EscalationStep;
import com.workflow.llm.LlmProvider;
import com.workflow.project.Project;
import com.workflow.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the escalation ladder when a block exhausts {@code max_iterations}.
 *
 * <p>Reads block- and project-level escalation config via {@link EscalationResolver},
 * tracks per-block cursor in {@link PipelineRun#getEscalationStateJson()}, and writes
 * cloud-step overrides into {@link PipelineRun#getRuntimeOverridesJson()} so the next
 * iteration of the target block picks them up.
 *
 * <p>Bundle composer is minimal for PR #1: last verify issues + attempt summary.
 * Full history-aware bundle with auto-hint generation lands in PR #2.
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);
    private static final TypeReference<Map<String, EscalationState>> STATE_MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, RuntimeOverride>> OVERRIDE_MAP_TYPE = new TypeReference<>() {};

    private final EscalationResolver resolver;
    private final ObjectMapper objectMapper;
    private final ProjectRepository projectRepository;

    public EscalationService(EscalationResolver resolver,
                             ObjectMapper objectMapper,
                             ProjectRepository projectRepository) {
        this.resolver = resolver;
        this.objectMapper = objectMapper;
        this.projectRepository = projectRepository;
    }

    /**
     * Consult the ladder for a block that just exhausted its retries.
     * Advances state, persists overrides for cloud-steps, and returns the decision
     * the caller should act on.
     *
     * @param run the active run
     * @param failingBlockId verify / agent_verify / CI block whose retries are exhausted
     * @param targetBlockId the block to retry (usually {@code on_fail.target})
     * @param ladder resolved escalation steps (caller resolves via {@link #resolveLadder})
     * @param failureContext minimal context for bundles (e.g. {@code Map.of("issues", [...])})
     */
    public EscalationDecision attemptEscalation(PipelineRun run,
                                                  String failingBlockId,
                                                  String targetBlockId,
                                                  List<EscalationStep> ladder,
                                                  Map<String, Object> failureContext) {
        if (ladder == null || ladder.isEmpty()) {
            return EscalationDecision.exhausted("no_escalation_configured", List.of());
        }

        Map<String, EscalationState> stateMap = readStateMap(run);
        EscalationState state = stateMap.getOrDefault(failingBlockId, EscalationState.initial());

        // Walk forward through the ladder, skipping fully-consumed cloud steps and
        // unknown step types. Each iteration either returns a decision or advances state.
        while (state.stepIndex() < ladder.size()) {
            EscalationStep step = ladder.get(state.stepIndex());

            if (step instanceof EscalationStep.Cloud cloud) {
                if (state.attemptsAtCurrentStep() >= cloud.maxIterations()) {
                    state = state.advanceStep();
                    continue;
                }
                state = state.incrementAttempt();
                persistState(run, failingBlockId, state, stateMap);

                RuntimeOverride override = new RuntimeOverride(cloud.provider(), cloud.model());
                recordOverride(run, targetBlockId, override);

                log.info("Escalation: run={} failingBlock={} -> cloud-tier (provider={}, model={}, attempt {}/{})",
                        run.getId(), failingBlockId, cloud.provider(), cloud.model(),
                        state.attemptsAtCurrentStep(), cloud.maxIterations());
                return EscalationDecision.retryWithCloud(targetBlockId, override);
            }

            if (step instanceof EscalationStep.Human human) {
                // Human step is consumed in a single shot — pause once, then advance.
                EscalationState advanced = state.advanceStep();
                persistState(run, failingBlockId, advanced, stateMap);

                Map<String, Object> bundle = composeMinimalBundle(failingBlockId, targetBlockId,
                        state.attemptsAtCurrentStep(), failureContext);

                log.info("Escalation: run={} failingBlock={} -> human gate (notify={}, timeout={}s)",
                        run.getId(), failingBlockId, human.notifyChannels(), human.timeoutSeconds());
                return EscalationDecision.pauseForHuman(failingBlockId, targetBlockId, human, bundle);
            }

            // Unrecognized step type — advance and try next.
            log.warn("Unknown escalation step type {} for block {}; skipping", step.tier(), failingBlockId);
            state = state.advanceStep();
        }

        // Persist the final state so re-entry doesn't accidentally restart from step 0.
        persistState(run, failingBlockId, state, stateMap);
        return EscalationDecision.exhausted("ladder_consumed", ladder);
    }

    /**
     * Resolve the effective ladder for a block. Loads {@link Project} from the run's
     * slug so 3-level fallback works.
     */
    public List<EscalationStep> resolveLadder(PipelineRun run, com.workflow.config.EscalationConfig blockEscalation) {
        Project project = findProject(run);
        return resolver.resolve(blockEscalation, project);
    }

    /**
     * Apply any pending runtime override to a block's effective agent config.
     * Returns the input unchanged when no override is registered for {@code blockId}.
     * Called by {@link PipelineRunner} just before block execution.
     */
    public BlockConfig applyRuntimeOverride(BlockConfig effectiveBlockConfig, PipelineRun run) {
        Optional<RuntimeOverride> override = getOverride(run, effectiveBlockConfig.getId());
        if (override.isEmpty() || override.get().isEmpty()) return effectiveBlockConfig;

        RuntimeOverride o = override.get();
        AgentConfig oldAgent = effectiveBlockConfig.getAgent();
        if (o.model() != null) {
            AgentConfig newAgent = cloneAgentWithModel(oldAgent, o.model());
            effectiveBlockConfig.setAgent(newAgent);
            log.info("Runtime override applied: block={} model={}->{}",
                    effectiveBlockConfig.getId(),
                    oldAgent != null ? oldAgent.getEffectiveModel() : null, o.model());
        }
        return effectiveBlockConfig;
    }

    /** Resolve the provider to use for this block invocation, applying override if present. */
    public LlmProvider effectiveProvider(PipelineRun run, String blockId, LlmProvider fallback) {
        return getOverride(run, blockId)
                .map(RuntimeOverride::provider)
                .filter(p -> p != null && !p.isBlank())
                .map(p -> {
                    try {
                        return LlmProvider.valueOf(p.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        log.warn("Runtime override has unknown provider '{}', falling back to {}", p, fallback);
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    public Optional<RuntimeOverride> getOverride(PipelineRun run, String blockId) {
        Map<String, RuntimeOverride> map = readOverrideMap(run);
        return Optional.ofNullable(map.get(blockId));
    }

    /** Clear all overrides — called on run completion or unrecoverable failure. */
    public void clearOverrides(PipelineRun run) {
        run.setRuntimeOverridesJson("{}");
    }

    public double maxBudgetUsd() { return resolver.maxBudgetUsd(); }

    // --- helpers ---

    private void recordOverride(PipelineRun run, String blockId, RuntimeOverride override) {
        Map<String, RuntimeOverride> map = readOverrideMap(run);
        map.put(blockId, override);
        try {
            run.setRuntimeOverridesJson(objectMapper.writeValueAsString(map));
        } catch (Exception e) {
            log.error("Failed to persist runtime override for block {}: {}", blockId, e.getMessage());
        }
    }

    private void persistState(PipelineRun run, String blockId, EscalationState state,
                              Map<String, EscalationState> stateMap) {
        stateMap.put(blockId, state);
        try {
            run.setEscalationStateJson(objectMapper.writeValueAsString(stateMap));
        } catch (Exception e) {
            log.error("Failed to persist escalation state for block {}: {}", blockId, e.getMessage());
        }
    }

    private Map<String, EscalationState> readStateMap(PipelineRun run) {
        String json = run.getEscalationStateJson();
        if (json == null || json.isBlank() || "{}".equals(json)) return new HashMap<>();
        try {
            Map<String, EscalationState> parsed = objectMapper.readValue(json, STATE_MAP_TYPE);
            return parsed != null ? new HashMap<>(parsed) : new HashMap<>();
        } catch (Exception e) {
            log.warn("Corrupt escalationStateJson on run {}: {}; starting fresh", run.getId(), e.getMessage());
            return new HashMap<>();
        }
    }

    private Map<String, RuntimeOverride> readOverrideMap(PipelineRun run) {
        String json = run.getRuntimeOverridesJson();
        if (json == null || json.isBlank() || "{}".equals(json)) return new HashMap<>();
        try {
            Map<String, RuntimeOverride> parsed = objectMapper.readValue(json, OVERRIDE_MAP_TYPE);
            return parsed != null ? new HashMap<>(parsed) : new HashMap<>();
        } catch (Exception e) {
            log.warn("Corrupt runtimeOverridesJson on run {}: {}; clearing", run.getId(), e.getMessage());
            return new HashMap<>();
        }
    }

    private Project findProject(PipelineRun run) {
        if (projectRepository == null || run.getProjectSlug() == null) return null;
        try {
            return projectRepository.findBySlug(run.getProjectSlug()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Shallow clone of an AgentConfig with just the model swapped. */
    private AgentConfig cloneAgentWithModel(AgentConfig source, String newModel) {
        AgentConfig clone = new AgentConfig();
        if (source != null) {
            clone.setTier(source.getTier());
            clone.setSystemPrompt(source.getSystemPrompt());
            clone.setMaxTokens(source.getMaxTokens());
            clone.setTemperature(source.getTemperature());
            clone.setPromptContextAllow(source.getPromptContextAllow());
            clone.setCompletionSignal(source.getCompletionSignal());
        }
        clone.setModel(newModel);
        return clone;
    }

    /** Minimal bundle for PR #1: just enough for the human gate UI / notification body. */
    private Map<String, Object> composeMinimalBundle(String failingBlockId, String targetBlockId,
                                                       int attempts, Map<String, Object> failureContext) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("reason", "max_iterations_exhausted");
        bundle.put("failing_block_id", failingBlockId);
        bundle.put("target_block_id", targetBlockId);
        bundle.put("attempts_at_last_step", attempts);
        if (failureContext != null) {
            bundle.put("last_failure", failureContext);
        }
        return bundle;
    }
}
