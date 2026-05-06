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
    public static final String INVALID_PHASE            = "INVALID_PHASE";
    public static final String PHASE_MONOTONICITY       = "PHASE_MONOTONICITY";
    public static final String PHASE_LOOPBACK_FORWARD   = "PHASE_LOOPBACK_FORWARD";
    public static final String PHASE_OVERRIDE_MISSING   = "PHASE_OVERRIDE_MISSING";

    /** Matches {@code ${...}} interpolations in YAML strings. */
    private static final Pattern DOLLAR_BRACE_REF = Pattern.compile("\\$\\{([^}]+)}");
    /** Matches {@code $.block_id} (and the longer {@code $.block_id.field...}) form. */
    private static final Pattern DOLLAR_DOT_REF = Pattern.compile("\\$\\.(\\w+)");

    private final BlockRegistry blockRegistry;

    @Autowired
    public PipelineConfigValidator(BlockRegistry blockRegistry) {
        this.blockRegistry = blockRegistry;
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
                }
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
                        validateRefs(extractRefs(gate.getExpr(), DOLLAR_DOT_REF),
                            referrerId,
                            location + ".required_gates[" + g + "].expr",
                            DOLLAR_DOT_REF, blockMap, order, errors, false);
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
                if (allowSelfRef) continue; // post-execution context (loopback inject_context)
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
            }
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
