package com.workflow.config;

import com.workflow.blocks.Block;
import com.workflow.blocks.Phase;
import com.workflow.core.BlockRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates a {@link PipelineConfig} against three levels of rules. Single source of truth
 * for "is this YAML actually runnable?" — invoked from {@link com.workflow.core.PipelineRunner},
 * the run-creation REST endpoint, the pipeline-write path, and the explicit
 * {@code POST /api/pipelines/validate} endpoint.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>Level 1 — structure:</b> required fields ({@code id}, {@code block}); unique {@code id};
 *       known block type per {@link BlockRegistry}.</li>
 *   <li><b>Level 2 — graph:</b> {@code depends_on} targets exist; DAG is acyclic;
 *       entry point {@code from_block} exists; verify subject + verify/on_failure loopback
 *       targets exist.</li>
 *   <li><b>Level 3 — data flow:</b> string interpolations ({@code ${X.Y}} and {@code $.X.Y})
 *       found in {@code config}, {@code condition}, {@code agent.systemPrompt}, and
 *       {@code verify.on_fail.inject_context} must reference a block that (a) exists,
 *       (b) is enabled, and (c) is topologically before the referencing block.
 *       {@code ${input.X}} is NOT validated (runtime concern).</li>
 * </ul>
 *
 * <p>Errors are <em>collected</em> (not bail-on-first) so the operator gets a complete
 * picture in one round-trip. Level 3 is skipped when level 2 reports a cycle: the topology
 * is meaningless without a valid order.
 */
@Service
public class PipelineConfigValidator {

    // ── Error codes (stable, surfaced via API) ────────────────────────────────
    public static final String MISSING_FIELD            = "MISSING_FIELD";
    public static final String DUPLICATE_BLOCK_ID       = "DUPLICATE_BLOCK_ID";
    public static final String UNKNOWN_BLOCK_TYPE       = "UNKNOWN_BLOCK_TYPE";
    public static final String DEPENDS_ON_UNKNOWN       = "DEPENDS_ON_UNKNOWN";
    public static final String DAG_CYCLE                = "DAG_CYCLE";
    public static final String ENTRY_POINT_UNKNOWN_BLOCK = "ENTRY_POINT_UNKNOWN_BLOCK";
    public static final String VERIFY_SUBJECT_UNKNOWN   = "VERIFY_SUBJECT_UNKNOWN";
    public static final String VERIFY_TARGET_UNKNOWN    = "VERIFY_TARGET_UNKNOWN";
    public static final String ON_FAILURE_TARGET_UNKNOWN = "ON_FAILURE_TARGET_UNKNOWN";
    public static final String FORWARD_REF              = "FORWARD_REF";
    public static final String REF_UNKNOWN_BLOCK        = "REF_UNKNOWN_BLOCK";
    public static final String REF_DISABLED_BLOCK       = "REF_DISABLED_BLOCK";
    public static final String REF_UNKNOWN_FIELD        = "REF_UNKNOWN_FIELD";
    public static final String INVALID_PHASE            = "INVALID_PHASE";
    public static final String PHASE_MONOTONICITY       = "PHASE_MONOTONICITY";
    public static final String PHASE_LOOPBACK_FORWARD   = "PHASE_LOOPBACK_FORWARD";
    public static final String PHASE_OVERRIDE_MISSING   = "PHASE_OVERRIDE_MISSING";
    public static final String WEAK_LLM_CHECK_PROMPT    = "WEAK_LLM_CHECK_PROMPT";
    public static final String ESCALATION_EMPTY_LADDER  = "ESCALATION_EMPTY_LADDER";
    public static final String ESCALATION_UNKNOWN_PROVIDER = "ESCALATION_UNKNOWN_PROVIDER";
    // PR-1 (hardening): config-time fail-fast for what used to blow up at runtime.
    public static final String UNKNOWN_TIER             = "UNKNOWN_TIER";
    public static final String TEMPERATURE_OUT_OF_RANGE = "TEMPERATURE_OUT_OF_RANGE";
    public static final String MAX_TOKENS_INVALID       = "MAX_TOKENS_INVALID";
    public static final String RETRY_INVALID            = "RETRY_INVALID";
    public static final String TIMEOUT_INVALID          = "TIMEOUT_INVALID";
    public static final String GATE_EXPR_SYNTAX         = "GATE_EXPR_SYNTAX";
    public static final String VERIFY_CHECK_UNKNOWN_RULE  = "VERIFY_CHECK_UNKNOWN_RULE";
    public static final String VERIFY_CHECK_UNKNOWN_FIELD = "VERIFY_CHECK_UNKNOWN_FIELD";
    public static final String LLM_CHECK_SCORE_RANGE    = "LLM_CHECK_SCORE_RANGE";
    public static final String SKILL_UNKNOWN            = "SKILL_UNKNOWN";
    public static final String ENTRY_INJECT_SOURCE_UNKNOWN = "ENTRY_INJECT_SOURCE_UNKNOWN";
    public static final String ENTRY_INJECT_BLOCK_UNKNOWN  = "ENTRY_INJECT_BLOCK_UNKNOWN";

    /** Highest legal {@code agent.temperature}. OpenAI/OpenRouter accept up to 2.0. */
    static final double MAX_TEMPERATURE = 2.0;

    /** LLM-check score scale upper bound (prompts ask the model to rate 0..10). */
    static final double LLM_CHECK_MAX_SCORE = 10.0;

    /**
     * Rule names recognised by {@code VerifyBlock.evaluateCheck}. Anything else falls
     * to the runtime "Unknown rule" branch — surface it at config time as a WARN.
     */
    private static final Set<String> KNOWN_VERIFY_RULES = Set.of(
        "equals", "not_empty", "min_length", "max_length",
        "min_items", "max_items", "one_of", "regex", "gt", "lt"
    );

    /**
     * Injection sources recognised by {@code EntryPointResolver.resolveInjections}.
     * Anything else is silently coerced to empty at runtime — warn instead.
     */
    private static final Set<String> VALID_INJECT_SOURCES = Set.of(
        "empty", "youtrack", "youtrack_tasks", "gitlab_branch", "gitlab_mr", "github_pr"
    );

    /**
     * Minimum acceptable length for {@code verify.llm_check.prompt}. Anything shorter
     * is almost certainly a stub (the canonical bad example: "Оцени качество 0-10.").
     */
    static final int LLM_CHECK_PROMPT_MIN_LENGTH = 100;

    /**
     * Case-insensitive markers indicating the prompt actually names some evaluation
     * criteria rather than asking the model to score in a vacuum. Matched as substrings
     * after lowercasing; one hit is enough.
     */
    private static final List<String> LLM_CHECK_CRITERIA_MARKERS = List.of(
        "security", "performance", "производительност",
        "criteria", "criterion", "критер",
        "dod", "definition of done", "acceptance",
        "test", "тест",
        "regression", "регресс",
        "logic", "логи", "баг", "bug"
    );

    /** Matches {@code ${...}} interpolations in YAML strings. */
    private static final Pattern DOLLAR_BRACE_REF = Pattern.compile("\\$\\{([^}]+)}");
    /**
     * Matches {@code $.block_id.field.subfield} form. Captures the dot-path
     * starting after {@code $.} up to the first non-identifier character. PR-1
     * widened this from {@code \w+} to {@code [\w.]+} so the field-tail is
     * preserved for {@link #REF_UNKNOWN_FIELD} checking.
     */
    private static final Pattern DOLLAR_DOT_REF = Pattern.compile("\\$\\.([\\w.]+)");

    private final BlockRegistry blockRegistry;
    /** Nullable — only the tier check needs it; legacy 1-arg constructor leaves it null. */
    private final com.workflow.llm.ModelPresetResolver presetResolver;
    /** Nullable — only the skills check needs it; legacy 1-arg constructor leaves it null. */
    private final com.workflow.skills.SkillRegistry skillRegistry;

    @Autowired
    public PipelineConfigValidator(BlockRegistry blockRegistry,
                                   com.workflow.llm.ModelPresetResolver presetResolver,
                                   com.workflow.skills.SkillRegistry skillRegistry) {
        this.blockRegistry = blockRegistry;
        this.presetResolver = presetResolver;
        this.skillRegistry = skillRegistry;
    }

    /**
     * Backwards-compat constructor for unit tests that only exercise structure/graph/data-flow
     * rules. Tier and skill checks are no-ops without the resolver/registry.
     */
    public PipelineConfigValidator(BlockRegistry blockRegistry) {
        this(blockRegistry, null, null);
    }

    /**
     * Runs all three levels and collects every error encountered. Never throws — invalid
     * configs are reported via {@link ValidationResult#valid()}.
     */
    public ValidationResult validate(PipelineConfig config) {
        List<ValidationError> errors = new ArrayList<>();
        if (config == null) {
            errors.add(new ValidationError(MISSING_FIELD, "PipelineConfig is null", null, null));
            return ValidationResult.of(errors);
        }
        List<BlockConfig> blocks = config.getPipeline() != null ? config.getPipeline() : List.of();

        // ── Level 1 ───────────────────────────────────────────────────────────
        Set<String> seenIds = new LinkedHashSet<>();
        Map<String, BlockConfig> blockMap = new LinkedHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            BlockConfig b = blocks.get(i);
            String location = "pipeline[" + i + "]";

            if (b.getId() == null || b.getId().isBlank()) {
                errors.add(new ValidationError(MISSING_FIELD,
                    "Block is missing required field 'id'", location, null));
                continue; // can't index by id
            }
            String id = b.getId();

            if (b.getBlock() == null || b.getBlock().isBlank()) {
                errors.add(new ValidationError(MISSING_FIELD,
                    "Block '" + id + "' is missing required field 'block' (block type)",
                    location + ".block", id));
            } else if (!blockRegistry.contains(b.getBlock())) {
                errors.add(new ValidationError(UNKNOWN_BLOCK_TYPE,
                    "Block '" + id + "' uses unknown block type '" + b.getBlock()
                        + "'. Registered types: " + sortedJoin(blockRegistry.blockTypes()),
                    location + ".block", id));
            }

            if (!seenIds.add(id)) {
                errors.add(new ValidationError(DUPLICATE_BLOCK_ID,
                    "Duplicate block id '" + id + "'", location + ".id", id));
                continue; // keep first occurrence in the map
            }
            blockMap.put(id, b);
        }

        // ── Level 2 ───────────────────────────────────────────────────────────
        // depends_on existence
        for (int i = 0; i < blocks.size(); i++) {
            BlockConfig b = blocks.get(i);
            if (b.getId() == null) continue;
            if (b.getDependsOn() == null) continue;
            for (int j = 0; j < b.getDependsOn().size(); j++) {
                String dep = b.getDependsOn().get(j);
                if (dep == null || dep.isBlank()) continue;
                if (!blockMap.containsKey(dep)) {
                    errors.add(new ValidationError(DEPENDS_ON_UNKNOWN,
                        "Block '" + b.getId() + "' depends on unknown block '" + dep + "'",
                        "pipeline[" + i + "].depends_on[" + j + "]", b.getId()));
                }
            }
        }

        // DAG cycle detection
        boolean hasCycle = detectCycles(blockMap, errors);

        // entry_points[].from_block existence
        if (config.getEntryPoints() != null) {
            for (int i = 0; i < config.getEntryPoints().size(); i++) {
                EntryPointConfig ep = config.getEntryPoints().get(i);
                if (ep == null) continue;
                String from = ep.getFromBlock();
                if (from != null && !from.isBlank() && !blockMap.containsKey(from)) {
                    String epId = ep.getId() != null ? ep.getId() : "<unnamed>";
                    errors.add(new ValidationError(ENTRY_POINT_UNKNOWN_BLOCK,
                        "Entry point '" + epId + "' has from_block '" + from
                            + "' which is not a defined block",
                        "entry_points[" + i + "].from_block", null));
                }
            }
        }

        // verify.subject + verify.on_fail.target + on_failure.target existence
        for (int i = 0; i < blocks.size(); i++) {
            BlockConfig b = blocks.get(i);
            if (b.getId() == null) continue;

            VerifyConfig v = b.getVerify();
            if (v != null) {
                String subject = v.getSubject();
                if (subject != null && !subject.isBlank() && !blockMap.containsKey(subject)) {
                    errors.add(new ValidationError(VERIFY_SUBJECT_UNKNOWN,
                        "Block '" + b.getId() + "' verify.subject '" + subject + "' is not a defined block",
                        "pipeline[" + i + "].verify.subject", b.getId()));
                }
                OnFailConfig onFail = v.getOnFail();
                if (onFail != null && "loopback".equals(onFail.getAction())) {
                    String target = onFail.getTarget();
                    if (target != null && !target.isBlank() && !blockMap.containsKey(target)) {
                        errors.add(new ValidationError(VERIFY_TARGET_UNKNOWN,
                            "Block '" + b.getId() + "' verify.on_fail.target '" + target
                                + "' is not a defined block",
                            "pipeline[" + i + "].verify.on_fail.target", b.getId()));
                    }
                    validateEscalation(onFail.getEscalation(), b.getId(),
                        "pipeline[" + i + "].verify.on_fail.escalation", errors);
                }
                validateLlmCheckPrompt(v, b.getId(), i, errors);
            }

            OnFailureConfig onFailure = b.getOnFailure();
            if (onFailure != null && "loopback".equals(onFailure.getAction())) {
                String target = onFailure.getTarget();
                if (target != null && !target.isBlank() && !blockMap.containsKey(target)) {
                    errors.add(new ValidationError(ON_FAILURE_TARGET_UNKNOWN,
                        "Block '" + b.getId() + "' on_failure.target '" + target
                            + "' is not a defined block",
                        "pipeline[" + i + "].on_failure.target", b.getId()));
                }
                validateEscalation(onFailure.getEscalation(), b.getId(),
                    "pipeline[" + i + "].on_failure.escalation", errors);
            }

            // PR-1: per-block scalar sanity (tier/temperature/maxTokens/retry/timeout/skills)
            // and verify-check rule/field — all things that previously only failed at runtime.
            validateAgentConfig(b, i, errors);
            validateRetry(b.getRetry(), b.getId(), i, errors);
            validateTimeout(b.getTimeoutSeconds(), b.getId(), i, errors);
            validateSkills(b, i, errors);
            if (v != null) {
                validateVerifyChecks(v, blockMap, b.getId(), i, errors);
            }
        }

        // entry_points[].inject — blockId must exist, source must be recognised
        if (config.getEntryPoints() != null) {
            for (int i = 0; i < config.getEntryPoints().size(); i++) {
                EntryPointConfig ep = config.getEntryPoints().get(i);
                if (ep == null || ep.getInject() == null) continue;
                String epId = ep.getId() != null ? ep.getId() : "<unnamed>";
                for (int j = 0; j < ep.getInject().size(); j++) {
                    EntryPointInjection inj = ep.getInject().get(j);
                    if (inj == null) continue;
                    String injLoc = "entry_points[" + i + "].inject[" + j + "]";
                    String injBlock = inj.getBlockId();
                    if (injBlock != null && !injBlock.isBlank() && !blockMap.containsKey(injBlock)) {
                        errors.add(ValidationError.warn(ENTRY_INJECT_BLOCK_UNKNOWN,
                            "Entry point '" + epId + "' injects into block '" + injBlock
                                + "' which is not a defined block",
                            injLoc + ".block_id", null));
                    }
                    String src = inj.getSource();
                    if (src != null && !src.isBlank() && !VALID_INJECT_SOURCES.contains(src)) {
                        errors.add(ValidationError.warn(ENTRY_INJECT_SOURCE_UNKNOWN,
                            "Entry point '" + epId + "' inject source '" + src
                                + "' is unknown — it will resolve to empty at runtime. Known: "
                                + sortedJoin(VALID_INJECT_SOURCES),
                            injLoc + ".source", null));
                    }
                }
            }
        }

        // ── Level 3 ───────────────────────────────────────────────────────────
        // Skipped if a cycle exists — topology has no meaning then.
        if (!hasCycle) {
            Map<String, Integer> order = topologicalOrder(blockMap);
            for (int i = 0; i < blocks.size(); i++) {
                BlockConfig b = blocks.get(i);
                if (b.getId() == null) continue;
                if (!b.isEnabled()) continue;          // disabled blocks: skip level 3 (but their refs *to* them are checked from enabled blocks)
                String referrerId = b.getId();
                String location = "pipeline[" + i + "]";

                // 1. config map (recursive): ${X.Y} interpolations — block consumes these
                //    BEFORE running, so refs must be strictly earlier in the topology.
                collectDollarBraceRefs(b.getConfig(), refs ->
                    validateRefs(refs, referrerId, location + ".config", DOLLAR_BRACE_REF,
                        blockMap, order, errors, false));

                // 2. agent.systemPrompt: ${X.Y} — same as config: pre-execution.
                if (b.getAgent() != null && b.getAgent().getSystemPrompt() != null) {
                    validateRefs(extractRefs(b.getAgent().getSystemPrompt(), DOLLAR_BRACE_REF),
                        referrerId, location + ".agent.systemPrompt", DOLLAR_BRACE_REF,
                        blockMap, order, errors, false);
                }

                // 3. condition: $.X.Y — evaluated BEFORE the block runs.
                if (b.getCondition() != null && !b.getCondition().isBlank()) {
                    validateRefs(extractRefs(b.getCondition(), DOLLAR_DOT_REF),
                        referrerId, location + ".condition", DOLLAR_DOT_REF,
                        blockMap, order, errors, false);
                    checkExprSyntax(b.getCondition(), referrerId, location + ".condition", errors);
                }

                // 4. verify.on_fail.inject_context: $.X.Y — evaluated AFTER the block has
                //    produced output (loopback fires post-execution), so self-reference is
                //    legitimate (reads the verify block's own issues/feedback).
                if (b.getVerify() != null && b.getVerify().getOnFail() != null
                        && b.getVerify().getOnFail().getInjectContext() != null) {
                    Map<String, String> ic = b.getVerify().getOnFail().getInjectContext();
                    for (Map.Entry<String, String> e : ic.entrySet()) {
                        if (e.getValue() == null) continue;
                        validateRefs(extractRefs(e.getValue(), DOLLAR_DOT_REF),
                            referrerId,
                            location + ".verify.on_fail.inject_context." + e.getKey(),
                            DOLLAR_DOT_REF, blockMap, order, errors, true);
                    }
                }

                // 5. on_failure.inject_context: $.X.Y — same post-execution semantics as
                //    verify.on_fail (CI block produced its status, loopback fires after).
                if (b.getOnFailure() != null && b.getOnFailure().getInjectContext() != null) {
                    Map<String, String> ic = b.getOnFailure().getInjectContext();
                    for (Map.Entry<String, String> e : ic.entrySet()) {
                        if (e.getValue() == null) continue;
                        validateRefs(extractRefs(e.getValue(), DOLLAR_DOT_REF),
                            referrerId,
                            location + ".on_failure.inject_context." + e.getKey(),
                            DOLLAR_DOT_REF, blockMap, order, errors, true);
                    }
                }

                // 6. required_gates[].expr: $.X.Y — evaluated BEFORE the block runs.
                if (b.getRequiredGates() != null) {
                    for (int g = 0; g < b.getRequiredGates().size(); g++) {
                        GateConfig gate = b.getRequiredGates().get(g);
                        if (gate == null || gate.getExpr() == null) continue;
                        String gateLoc = location + ".required_gates[" + g + "].expr";
                        validateRefs(extractRefs(gate.getExpr(), DOLLAR_DOT_REF),
                            referrerId, gateLoc, DOLLAR_DOT_REF, blockMap, order, errors, false);
                        checkExprSyntax(gate.getExpr(), referrerId, gateLoc, errors);
                    }
                }
            }
        }

        // ── Level 4 — phase ordering ─────────────────────────────────────────
        if (config.isPhaseCheck()) {
            validatePhases(blocks, blockMap, errors);
        }

        return ValidationResult.of(errors);
    }

    /**
     * Level 4 — phase ordering. Each block has an effective phase resolved from
     * (1) the per-instance YAML override {@code block.phase}, falling back to
     * (2) the block type's default {@link Phase} declared in {@link com.workflow.blocks.BlockMetadata}.
     *
     * <p>Rules:
     * <ul>
     *   <li>Unparseable {@code phase} string → {@link #INVALID_PHASE} (ERROR).</li>
     *   <li>For every {@code depends_on} edge u→v with both phases concrete (not ANY):
     *       {@code phase(v) >= phase(u)}, else {@link #PHASE_MONOTONICITY} (ERROR).</li>
     *   <li>For every loopback ({@code verify.on_fail.target}, {@code on_failure.target}):
     *       {@code phase(target) < phase(self)} when both concrete, else
     *       {@link #PHASE_LOOPBACK_FORWARD} (ERROR).</li>
     *   <li>Block whose effective phase is {@link Phase#ANY} without explicit override:
     *       {@link #PHASE_OVERRIDE_MISSING} (WARN — operator should pin the role).</li>
     * </ul>
     *
     * <p>{@link Phase#ANY} blocks are transparent in the monotonicity check —
     * any edge involving an ANY block on either side is skipped.
     */
    private void validatePhases(List<BlockConfig> blocks, Map<String, BlockConfig> blockMap,
                                List<ValidationError> errors) {
        Map<String, Phase> effective = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            BlockConfig b = blocks.get(i);
            if (b.getId() == null) continue;
            String location = "pipeline[" + i + "]";

            String override = b.getPhase();
            Phase phase;
            if (override != null && !override.isBlank()) {
                phase = parsePhase(override);
                if (phase == null) {
                    errors.add(new ValidationError(INVALID_PHASE,
                        "Block '" + b.getId() + "' has unknown phase '" + override
                            + "'. Valid: intake, analyze, implement, verify, publish, release, any",
                        location + ".phase", b.getId(), Severity.ERROR));
                    phase = defaultPhase(b);
                }
            } else {
                phase = defaultPhase(b);
            }
            effective.put(b.getId(), phase);

            if (phase == Phase.ANY && (override == null || override.isBlank())) {
                errors.add(new ValidationError(PHASE_OVERRIDE_MISSING,
                    "Block '" + b.getId() + "' uses block type '" + b.getBlock()
                        + "' which is polymorphic (phase=ANY). Set 'phase: <intake|analyze|implement|verify|publish|release>' "
                        + "to pin its role and enable phase ordering checks for this block.",
                    location + ".phase", b.getId(), Severity.WARN));
            }
        }

        for (int i = 0; i < blocks.size(); i++) {
            BlockConfig b = blocks.get(i);
            if (b.getId() == null) continue;
            Phase mine = effective.get(b.getId());
            if (mine == null) continue;
            String location = "pipeline[" + i + "]";

            if (b.getDependsOn() != null) {
                for (String dep : b.getDependsOn()) {
                    if (dep == null || !blockMap.containsKey(dep)) continue;
                    Phase parent = effective.get(dep);
                    if (parent == null) continue;
                    if (Phase.violatesMonotonic(parent, mine)) {
                        errors.add(new ValidationError(PHASE_MONOTONICITY,
                            "Block '" + b.getId() + "' (phase=" + mine + ") depends on '" + dep
                                + "' (phase=" + parent + ") — successor phase must be >= predecessor.",
                            location + ".depends_on", b.getId(), Severity.ERROR));
                    }
                }
            }

            VerifyConfig v = b.getVerify();
            if (v != null && v.getOnFail() != null && "loopback".equals(v.getOnFail().getAction())) {
                checkLoopbackPhase(b.getId(), mine, v.getOnFail().getTarget(), effective,
                    location + ".verify.on_fail.target", errors);
            }
            OnFailureConfig of = b.getOnFailure();
            if (of != null && "loopback".equals(of.getAction())) {
                checkLoopbackPhase(b.getId(), mine, of.getTarget(), effective,
                    location + ".on_failure.target", errors);
            }
        }
    }

    private void checkLoopbackPhase(String blockId, Phase mine, String target,
                                    Map<String, Phase> effective, String location,
                                    List<ValidationError> errors) {
        if (target == null || target.isBlank()) return;
        Phase tgt = effective.get(target);
        if (tgt == null) return;
        if (mine == Phase.ANY || tgt == Phase.ANY) return;
        if (tgt.order() >= mine.order()) {
            errors.add(new ValidationError(PHASE_LOOPBACK_FORWARD,
                "Block '" + blockId + "' (phase=" + mine + ") loops back to '" + target
                    + "' (phase=" + tgt + ") — loopback target must be in an earlier phase.",
                location, blockId, Severity.ERROR));
        }
    }

    private Phase parsePhase(String s) {
        if (s == null) return null;
        try {
            return Phase.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Phase defaultPhase(BlockConfig b) {
        if (b.getBlock() == null) return Phase.ANY;
        Block bean = blockRegistry.get(b.getBlock());
        if (bean == null) return Phase.forBlockType(b.getBlock());
        return bean.getMetadata().phase();
    }

    /**
     * Warns when {@code verify.llm_check.prompt} is too short to actually guide the model
     * or doesn't reference any evaluation criteria. Catches stub prompts like
     * "Оцени качество 0-10." that pass schema validation but produce useless reviews.
     *
     * <p>Always emits {@link Severity#WARN}, never {@link Severity#ERROR} — pipelines with
     * weak prompts must still load (backwards compat for existing configs).
     */
    private void validateLlmCheckPrompt(VerifyConfig v, String blockId, int blockIdx,
                                        List<ValidationError> errors) {
        LLMCheckConfig llm = v.getLlmCheck();
        if (llm == null || !llm.isEnabled()) return;

        // PR-1: minScore must sit on the 0..10 scale the prompts ask the model to use.
        // Out-of-range thresholds silently make the gate always-pass or never-pass.
        double minScore = llm.getMinScore();
        if (minScore < 0 || minScore > LLM_CHECK_MAX_SCORE) {
            errors.add(new ValidationError(LLM_CHECK_SCORE_RANGE,
                "Block '" + blockId + "' verify.llm_check.minScore=" + minScore
                    + " is outside the 0.." + (int) LLM_CHECK_MAX_SCORE + " scale — "
                    + "the gate would " + (minScore < 0 ? "always pass" : "never pass") + ".",
                "pipeline[" + blockIdx + "].verify.llm_check.minScore", blockId, Severity.ERROR));
        }

        String prompt = llm.getPrompt();
        if (prompt == null) return; // missing prompt is handled elsewhere via runtime defaults
        String trimmed = prompt.trim();
        String location = "pipeline[" + blockIdx + "].verify.llm_check.prompt";
        if (trimmed.length() < LLM_CHECK_PROMPT_MIN_LENGTH) {
            errors.add(new ValidationError(WEAK_LLM_CHECK_PROMPT,
                "Block '" + blockId + "' verify.llm_check.prompt is too short ("
                    + trimmed.length() + " chars, минимум " + LLM_CHECK_PROMPT_MIN_LENGTH
                    + "). Stub prompts produce useless reviews — укажи роль, критерии "
                    + "(security/performance/тесты/DoD), и шкалу оценки.",
                location, blockId, Severity.WARN));
            return;
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        boolean hasMarker = LLM_CHECK_CRITERIA_MARKERS.stream().anyMatch(lower::contains);
        if (!hasMarker) {
            errors.add(new ValidationError(WEAK_LLM_CHECK_PROMPT,
                "Block '" + blockId + "' verify.llm_check.prompt не упоминает критериев оценки "
                    + "(security / performance / DoD / acceptance / тесты / логика). "
                    + "Модель будет выставлять оценки в вакууме — добавь явные критерии.",
                location, blockId, Severity.WARN));
        }
    }

    /**
     * Validates {@code on_fail.escalation} / {@code on_failure.escalation} arrays.
     * Polymorphic deserialization already rejects unknown tier values; here we surface
     * semantic warnings that Jackson can't catch (empty ladder, unknown provider names).
     */
    private void validateEscalation(EscalationConfig escalation, String blockId, String location,
                                     List<ValidationError> errors) {
        if (escalation == null || escalation.policy() != EscalationConfig.Policy.EXPLICIT) return;
        List<EscalationStep> steps = escalation.steps();

        if (steps.isEmpty()) {
            errors.add(ValidationError.info(ESCALATION_EMPTY_LADDER,
                "Block '" + blockId + "' has an empty explicit escalation ladder — this behaves "
                    + "identically to `escalation: none`. Did you mean to opt out, or to populate the list?",
                location, blockId));
            return;
        }

        for (int s = 0; s < steps.size(); s++) {
            EscalationStep step = steps.get(s);
            String stepLoc = location + "[" + s + "]";
            if (step instanceof EscalationStep.Cloud cloud) {
                if (cloud.provider() != null && !cloud.provider().isBlank()) {
                    try {
                        com.workflow.llm.LlmProvider.valueOf(cloud.provider().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        errors.add(ValidationError.warn(ESCALATION_UNKNOWN_PROVIDER,
                            "Block '" + blockId + "' escalation step " + s + " has unknown provider '"
                                + cloud.provider() + "'. Known: OPENROUTER, OLLAMA, VLLM, CLAUDE_CODE_CLI, AITUNNEL, ALLTOKENS.",
                            stepLoc + ".provider", blockId));
                    }
                }
            }
        }
    }

    /**
     * PR-1 — agent.tier / temperature / maxTokens sanity. All previously failed only at
     * runtime: an unknown tier passes through {@link com.workflow.llm.ModelPresetResolver#resolve}
     * as a raw model id and crashes with a provider API error; temperature/maxTokens out of
     * range get rejected by the provider.
     *
     * <p>Tier is a WARN (operator may legitimately pin an exotic model via {@code agent.model});
     * temperature/maxTokens are ERROR (mathematically invalid).
     */
    private void validateAgentConfig(BlockConfig b, int i, List<ValidationError> errors) {
        AgentConfig agent = b.getAgent();
        if (agent == null) return;
        String location = "pipeline[" + i + "].agent";

        // tier: only checkable when the resolver is wired (production) — skip in legacy tests.
        // Skip when an explicit model id is set, or the tier already looks like a full id.
        String tier = agent.getTier();
        if (presetResolver != null && tier != null && !tier.isBlank()
                && (agent.getModel() == null || agent.getModel().isBlank())
                && !tier.contains("/")) {
            if (!presetResolver.knownTierKeys().contains(tier.toLowerCase(java.util.Locale.ROOT))) {
                errors.add(ValidationError.warn(UNKNOWN_TIER,
                    "Block '" + b.getId() + "' agent.tier '" + tier + "' is not a known preset — "
                        + "it will be passed through as a raw model id and likely fail at the provider. "
                        + "Known tiers: " + sortedJoin(presetResolver.knownTierKeys())
                        + ". Pin a concrete model via agent.model if intentional.",
                    location + ".tier", b.getId()));
            }
        }

        Double temp = agent.getTemperature();
        if (temp != null && (temp < 0.0 || temp > MAX_TEMPERATURE)) {
            errors.add(new ValidationError(TEMPERATURE_OUT_OF_RANGE,
                "Block '" + b.getId() + "' agent.temperature=" + temp + " is out of range [0.0, "
                    + MAX_TEMPERATURE + "].",
                location + ".temperature", b.getId(), Severity.ERROR));
        }

        Integer maxTokens = agent.getMaxTokens();
        if (maxTokens != null && maxTokens <= 0) {
            errors.add(new ValidationError(MAX_TOKENS_INVALID,
                "Block '" + b.getId() + "' agent.maxTokens=" + maxTokens + " must be > 0.",
                location + ".maxTokens", b.getId(), Severity.ERROR));
        }
    }

    /**
     * PR-1 — retry policy sanity. Defaults (3 / 1000 / 30000) are valid, so this only fires
     * on YAML overrides. All ERROR: each value makes retry behave nonsensically at runtime.
     */
    private void validateRetry(RetryConfig retry, String blockId, int i, List<ValidationError> errors) {
        if (retry == null) return;
        String location = "pipeline[" + i + "].retry";
        if (retry.getMaxAttempts() <= 0) {
            errors.add(new ValidationError(RETRY_INVALID,
                "Block '" + blockId + "' retry.max_attempts=" + retry.getMaxAttempts() + " must be >= 1.",
                location + ".max_attempts", blockId, Severity.ERROR));
        }
        if (retry.getBackoffMs() < 0) {
            errors.add(new ValidationError(RETRY_INVALID,
                "Block '" + blockId + "' retry.backoff_ms=" + retry.getBackoffMs() + " must be >= 0.",
                location + ".backoff_ms", blockId, Severity.ERROR));
        }
        if (retry.getBackoffMs() > retry.getMaxBackoffMs()) {
            errors.add(new ValidationError(RETRY_INVALID,
                "Block '" + blockId + "' retry.backoff_ms=" + retry.getBackoffMs()
                    + " exceeds max_backoff_ms=" + retry.getMaxBackoffMs()
                    + " — the cap would never be reached.",
                location + ".backoff_ms", blockId, Severity.ERROR));
        }
    }

    /**
     * PR-1 — timeout sanity. Only the numeric {@code <= 0} case is checked here:
     * {@code on_timeout.action} is already validated by Jackson ({@code TimeoutConfig.Action.fromValue}
     * throws on unknown values during deserialization), so duplicating it would be dead code.
     */
    private void validateTimeout(Integer timeoutSeconds, String blockId, int i, List<ValidationError> errors) {
        if (timeoutSeconds != null && timeoutSeconds <= 0) {
            errors.add(new ValidationError(TIMEOUT_INVALID,
                "Block '" + blockId + "' timeout_seconds=" + timeoutSeconds + " must be > 0.",
                "pipeline[" + i + "].timeout_seconds", blockId, Severity.ERROR));
        }
    }

    /**
     * PR-1 — skills must be registered. WARN only (a typo'd skill is silently skipped at
     * runtime by {@link com.workflow.skills.SkillRegistry#resolve}, so the block runs without
     * the tool the operator expected). No-op when the registry isn't wired (legacy tests).
     */
    private void validateSkills(BlockConfig b, int i, List<ValidationError> errors) {
        if (skillRegistry == null || b.getSkills() == null) return;
        Set<String> known = skillRegistry.getAll().keySet();
        List<String> skills = b.getSkills();
        for (int s = 0; s < skills.size(); s++) {
            String name = skills.get(s);
            if (name == null || name.isBlank()) continue;
            if (!known.contains(name)) {
                errors.add(ValidationError.warn(SKILL_UNKNOWN,
                    "Block '" + b.getId() + "' references unknown skill '" + name
                        + "' — it will be silently skipped at runtime. Registered skills: "
                        + sortedJoin(known),
                    "pipeline[" + i + "].skills[" + s + "]", b.getId()));
            }
        }
    }

    /**
     * PR-1 — verify.checks rule names and fields. Rule WARN (runtime falls to an
     * "Unknown rule" message, never matching); field WARN reuses {@link #checkOutputField}
     * against the subject block's declared outputs.
     */
    private void validateVerifyChecks(VerifyConfig v, Map<String, BlockConfig> blockMap,
                                      String blockId, int i, List<ValidationError> errors) {
        if (v.getChecks() == null) return;
        // Field checks resolve against the subject block (defaults to this block if unset —
        // matches VerifyBlock, which verifies its own input when subject is absent).
        BlockConfig subjectBlock = v.getSubject() != null ? blockMap.get(v.getSubject()) : null;
        for (int c = 0; c < v.getChecks().size(); c++) {
            FieldCheckConfig check = v.getChecks().get(c);
            if (check == null) continue;
            String checkLoc = "pipeline[" + i + "].verify.checks[" + c + "]";
            String rule = check.getRule();
            if (rule != null && !rule.isBlank() && !KNOWN_VERIFY_RULES.contains(rule)) {
                errors.add(ValidationError.warn(VERIFY_CHECK_UNKNOWN_RULE,
                    "Block '" + blockId + "' verify.checks[" + c + "].rule '" + rule
                        + "' is not a known rule. Known: " + sortedJoin(KNOWN_VERIFY_RULES),
                    checkLoc + ".rule", blockId));
            }
            if (subjectBlock != null && check.getField() != null && !check.getField().isBlank()) {
                // Reuse the declared-outputs check; tail is the field name (no $. prefix here).
                checkOutputField(subjectBlock, v.getSubject(), check.getField(),
                    check.getField(), checkLoc + ".field", blockId, DOLLAR_DOT_REF, errors);
            }
        }
    }

    /**
     * PR-1 — lightweight lexical sanity for {@code condition} / {@code required_gates[].expr}.
     * The runtime evaluator ({@code PipelineRunner.evaluateClause}) silently treats an
     * unparseable expression as {@code true} (the block runs / the gate opens) and only logs a
     * warning — so a typo'd operator quietly disables the guard. This catches the obvious cases
     * at config time. WARN only: the runtime grammar is the source of truth.
     *
     * <p>Checks: balanced brackets, no malformed operator runs ({@code >>>}, {@code ===}), and
     * every {@code $.}-bearing clause (split on {@code &&} / {@code ||}) carries a recognized
     * comparison operator ({@code == != > < >= <=}).
     */
    private void checkExprSyntax(String expr, String referrerId, String location,
                                 List<ValidationError> errors) {
        if (expr == null || expr.isBlank()) return;
        if (!bracketsBalanced(expr)) {
            errors.add(ValidationError.warn(GATE_EXPR_SYNTAX,
                "Expression '" + expr + "' has unbalanced brackets.", location, referrerId));
            return;
        }
        if (Pattern.compile("[=<>!]{3,}").matcher(expr).find()) {
            errors.add(ValidationError.warn(GATE_EXPR_SYNTAX,
                "Expression '" + expr + "' contains a malformed operator (valid: == != > < >= <=).",
                location, referrerId));
            return;
        }
        Pattern hasOp = Pattern.compile("(==|!=|>=|<=|>|<)");
        for (String clause : expr.split("\\|\\||&&")) {
            String c = clause.trim();
            if (c.isEmpty() || !c.contains("$.")) continue; // don't second-guess non-ref clauses
            if (!hasOp.matcher(c).find()) {
                errors.add(ValidationError.warn(GATE_EXPR_SYNTAX,
                    "Clause '" + c + "' references a block field but has no comparison operator "
                        + "(== != > < >= <=).", location, referrerId));
            }
        }
    }

    /** True if {@code ()}, {@code []} and {@code {}} are balanced and properly nested. */
    private boolean bracketsBalanced(String s) {
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '(', '[', '{' -> stack.push(ch);
                case ')' -> { if (stack.isEmpty() || stack.pop() != '(') return false; }
                case ']' -> { if (stack.isEmpty() || stack.pop() != '[') return false; }
                case '}' -> { if (stack.isEmpty() || stack.pop() != '{') return false; }
                default -> { /* ignore */ }
            }
        }
        return stack.isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * DFS-based cycle detection. Records one {@link #DAG_CYCLE} error per discovered cycle
     * (using the entry block ID as the location).
     *
     * @return {@code true} iff at least one cycle was found
     */
    private boolean detectCycles(Map<String, BlockConfig> blockMap, List<ValidationError> errors) {
        Set<String> visited = new HashSet<>();
        Set<String> reportedCycles = new HashSet<>();
        boolean foundAny = false;
        for (String id : blockMap.keySet()) {
            if (visited.contains(id)) continue;
            Set<String> stack = new LinkedHashSet<>();
            String cycleStart = dfsForCycle(id, blockMap, visited, stack);
            if (cycleStart != null) {
                foundAny = true;
                // Emit one error per detected cycle, deduplicated by sorted member set.
                List<String> members = new ArrayList<>();
                boolean started = false;
                for (String s : stack) {
                    if (s.equals(cycleStart)) started = true;
                    if (started) members.add(s);
                }
                if (members.isEmpty()) members.add(cycleStart);
                List<String> key = new ArrayList<>(members);
                Collections.sort(key);
                String dedupKey = String.join("|", key);
                if (reportedCycles.add(dedupKey)) {
                    errors.add(new ValidationError(DAG_CYCLE,
                        "Cycle detected in depends_on graph: "
                            + String.join(" -> ", members) + " -> " + cycleStart,
                        "pipeline", cycleStart));
                }
            }
        }
        return foundAny;
    }

    /** @return the block ID where a back-edge was found, or {@code null} if no cycle. */
    private String dfsForCycle(String id, Map<String, BlockConfig> blockMap,
                               Set<String> visited, Set<String> stack) {
        if (stack.contains(id)) return id;
        if (visited.contains(id)) return null;
        stack.add(id);
        BlockConfig b = blockMap.get(id);
        if (b != null && b.getDependsOn() != null) {
            for (String dep : b.getDependsOn()) {
                if (dep == null || !blockMap.containsKey(dep)) continue;
                String found = dfsForCycle(dep, blockMap, visited, stack);
                if (found != null) return found;
            }
        }
        stack.remove(id);
        visited.add(id);
        return null;
    }

    /**
     * Builds a topological order index. Cycles are not expected (caller has already gated
     * level 3 on the absence of cycles); if one slips through, the offending blocks simply
     * receive their natural-order index, which is still safe (level 3 just won't fire
     * the FORWARD_REF on those).
     */
    private Map<String, Integer> topologicalOrder(Map<String, BlockConfig> blockMap) {
        List<String> sorted = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String id : blockMap.keySet()) {
            if (!visited.contains(id)) {
                topoVisit(id, blockMap, visited, inStack, sorted);
            }
        }
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            order.put(sorted.get(i), i);
        }
        return order;
    }

    private void topoVisit(String id, Map<String, BlockConfig> blockMap,
                           Set<String> visited, Set<String> inStack, List<String> out) {
        if (inStack.contains(id)) return; // cycle guard — caller already gated
        if (visited.contains(id)) return;
        inStack.add(id);
        BlockConfig b = blockMap.get(id);
        if (b != null && b.getDependsOn() != null) {
            for (String dep : b.getDependsOn()) {
                if (dep != null && blockMap.containsKey(dep)) {
                    topoVisit(dep, blockMap, visited, inStack, out);
                }
            }
        }
        inStack.remove(id);
        visited.add(id);
        out.add(id);
    }

    /**
     * Recursively walks {@code config} (which may be a {@code Map}, {@code List}, or scalar)
     * collecting every {@code ${...}} reference found in string leaves, then hands them to
     * {@code sink}.
     */
    private void collectDollarBraceRefs(Object node, RefsConsumer sink) {
        Set<String> refs = new LinkedHashSet<>();
        walkScalars(node, s -> refs.addAll(extractRefs(s, DOLLAR_BRACE_REF)));
        if (!refs.isEmpty()) {
            sink.accept(refs);
        }
    }

    private void walkScalars(Object node, java.util.function.Consumer<String> stringSink) {
        if (node == null) return;
        if (node instanceof String s) {
            stringSink.accept(s);
        } else if (node instanceof Map<?, ?> map) {
            for (Object v : map.values()) walkScalars(v, stringSink);
        } else if (node instanceof Iterable<?> it) {
            for (Object v : it) walkScalars(v, stringSink);
        }
        // numbers/booleans: nothing to scan
    }

    /** Extracts every match's first capture group from {@code text}. */
    private Set<String> extractRefs(String text, Pattern pattern) {
        if (text == null || text.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String inner = m.group(1);
            if (inner == null || inner.isBlank()) continue;
            out.add(inner.trim());
        }
        return out;
    }

    /**
     * For each captured ref, derive the referenced block ID (first segment before the dot)
     * and emit appropriate errors.
     *
     * <p>Rules:
     * <ul>
     *   <li>{@code input.X} — skipped (runtime concern).</li>
     *   <li>Unknown block — {@link #REF_UNKNOWN_BLOCK}.</li>
     *   <li>Disabled block — {@link #REF_DISABLED_BLOCK} (an enabled block can't depend on a
     *       disabled one — output never materializes).</li>
     *   <li>Self-reference or block ordered after — {@link #FORWARD_REF}, unless
     *       {@code allowSelfRef} is true (used for {@code verify.on_fail.inject_context}
     *       and {@code on_failure.inject_context}, which fire after the block has produced
     *       output).</li>
     * </ul>
     */
    private void validateRefs(Set<String> refs, String referrerId, String location,
                              Pattern usedPattern,
                              Map<String, BlockConfig> blockMap, Map<String, Integer> order,
                              List<ValidationError> errors, boolean allowSelfRef) {
        if (refs == null || refs.isEmpty()) return;
        Integer referrerIdx = order.get(referrerId);
        for (String ref : refs) {
            String[] parts = ref.split("\\.", 2);
            String head = parts[0].trim();
            String tail = parts.length > 1 ? parts[1].trim() : "";
            if (head.isEmpty()) continue;
            if ("input".equals(head)) continue; // ${input.X} — runtime only

            BlockConfig referenced = blockMap.get(head);
            if (referenced == null) {
                errors.add(new ValidationError(REF_UNKNOWN_BLOCK,
                    "Reference '" + formatRef(ref, usedPattern) + "' points at unknown block '" + head + "'",
                    location, referrerId));
                continue;
            }
            if (!referenced.isEnabled()) {
                errors.add(new ValidationError(REF_DISABLED_BLOCK,
                    "Reference '" + formatRef(ref, usedPattern) + "' points at disabled block '" + head
                        + "' — its output is never produced",
                    location, referrerId));
                continue;
            }
            if (head.equals(referrerId)) {
                if (allowSelfRef) {
                    // Self-ref is legitimate (post-execution loopback inject_context). Still
                    // surface field-level typos via REF_UNKNOWN_FIELD.
                    checkOutputField(referenced, head, tail, ref, location, referrerId,
                        usedPattern, errors);
                    continue;
                }
                errors.add(new ValidationError(FORWARD_REF,
                    "Reference '" + formatRef(ref, usedPattern)
                        + "' is a self-reference — block '" + referrerId + "' cannot read its own output",
                    location, referrerId));
                continue;
            }
            Integer refIdx = order.get(head);
            if (referrerIdx != null && refIdx != null && refIdx >= referrerIdx) {
                errors.add(new ValidationError(FORWARD_REF,
                    "Reference '" + formatRef(ref, usedPattern) + "' points at block '" + head
                        + "' which is not topologically before '" + referrerId + "'",
                    location, referrerId));
                continue;
            }
            // PR-1: WARN when the tail's first segment isn't in the referenced block's
            // declared outputs. Backwards-compat: skip silently when outputs aren't
            // declared (block hasn't migrated to the new metadata yet).
            checkOutputField(referenced, head, tail, ref, location, referrerId,
                usedPattern, errors);
        }
    }

    /**
     * Emits {@link #REF_UNKNOWN_FIELD} (severity {@link Severity#ERROR}) when {@code tail}
     * starts with a field name not present in the referenced block's declared
     * {@code outputs}. Silently skips when:
     * <ul>
     *   <li>{@code tail} is empty (bare {@code $.block} or {@code ${block}}) — there's no
     *       field to check;</li>
     *   <li>the referenced block hasn't declared any outputs (legacy block without
     *       updated metadata) — would produce false positives;</li>
     *   <li>the block bean lookup fails — defensive against test stubs / unknown types
     *       (those are surfaced separately as {@link #UNKNOWN_BLOCK_TYPE}).</li>
     * </ul>
     *
     * <p>Only the first dot-segment of {@code tail} is checked. Nested paths
     * ({@code $.X.field.subfield}) validate {@code field} but not {@code subfield} —
     * declared outputs don't carry nested schemas (acceptable PR-1 trade-off).
     */
    private void checkOutputField(BlockConfig referenced, String head, String tail,
                                  String ref, String location, String referrerId,
                                  Pattern usedPattern, List<ValidationError> errors) {
        if (tail == null || tail.isEmpty()) return;
        String blockType = referenced.getBlock();
        if (blockType == null || blockType.isBlank()) return;
        Block bean = blockRegistry.get(blockType);
        if (bean == null) return;
        List<com.workflow.blocks.FieldSchema> outputs = bean.getMetadata().outputs();
        if (outputs == null || outputs.isEmpty()) return;
        String firstSegment = tail.split("\\.", 2)[0].trim();
        if (firstSegment.isEmpty()) return;
        boolean known = outputs.stream().anyMatch(f -> firstSegment.equals(f.name()));
        if (!known) {
            String knownNames = outputs.stream()
                .map(com.workflow.blocks.FieldSchema::name)
                .collect(java.util.stream.Collectors.joining(", "));
            errors.add(new ValidationError(REF_UNKNOWN_FIELD,
                "Reference '" + formatRef(ref, usedPattern) + "' points at field '"
                    + firstSegment + "' which is not declared in block '" + head
                    + "' (" + blockType + ") outputs. Known outputs: " + knownNames,
                location, referrerId, Severity.ERROR));
        }
    }

    /** Renders a captured ref back into the YAML form for friendlier error messages. */
    private String formatRef(String inner, Pattern usedPattern) {
        if (usedPattern == DOLLAR_BRACE_REF) return "${" + inner + "}";
        return "$." + inner;
    }

    private static String sortedJoin(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        return String.join(", ", list);
    }

    @FunctionalInterface
    private interface RefsConsumer {
        void accept(Set<String> refs);
    }
}
