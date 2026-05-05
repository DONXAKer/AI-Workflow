package com.workflow.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.api.RunWebSocketHandler;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.core.expr.StringInterpolator;
import com.workflow.llm.LlmClient;
import com.workflow.llm.tooluse.StopReason;
import com.workflow.llm.tooluse.ToolDefinition;
import com.workflow.llm.tooluse.ToolUseRequest;
import com.workflow.llm.tooluse.ToolUseResponse;
import com.workflow.project.Project;
import com.workflow.project.ProjectContext;
import com.workflow.project.ProjectRepository;
import com.workflow.tools.DefaultToolExecutor;
import com.workflow.tools.ProjectTreeSummary;
import com.workflow.tools.Tool;
import com.workflow.tools.ToolCallAuditRepository;
import com.workflow.tools.ToolContext;
import com.workflow.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Orchestrator block: a read-only supervisor agent that runs before and after
 * implementation blocks.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@code mode: plan} — explores the codebase and outputs a structured plan
 *       ({@code goal}, {@code files_to_touch}, {@code approach}, {@code definition_of_done})
 *   <li>{@code mode: review} — reads code changes and checks them against the plan's
 *       {@code definition_of_done}, outputting {@code passed}, {@code issues}, {@code action}
 * </ul>
 *
 * <p>YAML shape:
 * <pre>
 * - id: plan_impl
 *   block: orchestrator
 *   agent:
 *     model: anthropic/claude-sonnet-4-5
 *     temperature: 0.1
 *   depends_on: [task_md]
 *   config:
 *     mode: plan
 *     context_blocks: [task_md]
 *     working_dir: /projects/WarCard
 *     max_iterations: 10
 *     system_prompt_extra: "Java Spring Boot. Packages: api/, core/"
 *
 * - id: review_impl
 *   block: orchestrator
 *   agent:
 *     model: anthropic/claude-sonnet-4-5
 *   depends_on: [impl_server]
 *   config:
 *     mode: review
 *     plan_block: plan_impl
 *     working_dir: /projects/WarCard
 *     max_iterations: 8
 *   verify:
 *     on_fail:
 *       action: loopback
 *       target: impl_server
 *       max_iterations: 2
 *       inject_context:
 *         retry_instruction: "$.review_impl.retry_instruction"
 *         issues: "$.review_impl.issues"
 * </pre>
 */
@Component
public class OrchestratorBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorBlock.class);

    private static final int DEFAULT_MAX_ITER_PLAN   = 10;
    private static final int DEFAULT_MAX_ITER_REVIEW = 8;
    private static final double DEFAULT_BUDGET_USD   = 3.0;

    @Value("${workflow.orchestrator.default-model:smart}")
    private String defaultModel;

    private static final List<String> PLAN_TOOLS   = List.of("Read", "Grep", "Glob");
    private static final List<String> PLAN_BASH    = List.of("Bash(git log*)", "Bash(git status)", "Bash(git show*)");
    private static final List<String> REVIEW_TOOLS = List.of("Read", "Grep", "Glob", "Bash");
    private static final List<String> REVIEW_BASH  = List.of("Bash(git diff*)", "Bash(git log*)", "Bash(git status)", "Bash(git show*)");

    @Autowired private LlmClient llmClient;
    @Autowired private ToolRegistry toolRegistry;
    @Autowired private ObjectMapper objectMapper;
    @Autowired(required = false) private ToolCallAuditRepository auditRepository;
    @Autowired(required = false) private ProjectRepository projectRepository;
    @Autowired(required = false) private StringInterpolator stringInterpolator;
    @Autowired(required = false) private RunWebSocketHandler wsHandler;

    @Override public String getName() { return "orchestrator"; }

    @Override public String getDescription() {
        return "Агент-супервайзер: в режиме plan формирует структурированный план реализации; в режиме review сверяет результат с definition_of_done из плана.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
            "Orchestrator",
            "agent",
            List.of(
                FieldSchema.enumField("mode", "Режим", List.of("plan", "review"),
                    "plan", "plan — построить план; review — проверить результат относительно definition_of_done."),
                FieldSchema.stringArray("context_blocks", "Контекстные блоки",
                    "ID блоков, чьи выводы передаются в plan-режиме (обычно task_md)."),
                FieldSchema.blockRef("plan_block", "Plan-блок",
                    "ID orchestrator-блока с режимом plan; используется в review-режиме."),
                FieldSchema.string("working_dir", "Рабочая директория",
                    "Абсолютный путь; если пусто — workingDir проекта."),
                FieldSchema.number("max_iterations", "Max iterations", DEFAULT_MAX_ITER_PLAN,
                    "Максимум раундов агента-супервайзера."),
                FieldSchema.number("budget_usd_cap", "Бюджет USD", DEFAULT_BUDGET_USD,
                    "Лимит стоимости вызовов LLM."),
                FieldSchema.multilineString("system_prompt_extra", "Доп. системный промпт",
                    "Дополнительный контекст проекта (стек, конвенции). Добавляется к встроенному.")
            ),
            false,
            Map.of()
        );
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig blockConfig, PipelineRun run) throws Exception {
        Map<String, Object> cfg = blockConfig.getConfig() != null ? blockConfig.getConfig() : Map.of();
        String mode = asString(cfg, "mode", "plan");

        Path workingDir = resolveWorkingDir(cfg);

        String projectExtra = resolveProjectExtra();
        String blockExtra   = asString(cfg, "system_prompt_extra", "");
        String combinedExtra = Stream.of(projectExtra, blockExtra)
            .filter(s -> !s.isBlank())
            .collect(Collectors.joining("\n\n"));

        AgentConfig agent = blockConfig.getAgent() != null ? blockConfig.getAgent() : new AgentConfig();
        String agentSystemPrompt = agent.getSystemPrompt() != null ? agent.getSystemPrompt().strip() : "";

        if ("review".equals(mode)) {
            return runReview(cfg, input, blockConfig, run, workingDir, combinedExtra, agentSystemPrompt);
        }
        return runPlan(cfg, input, blockConfig, run, workingDir, combinedExtra, agentSystemPrompt);
    }

    // ── Plan mode ──────────────────────────────────────────────────────────────

    private Map<String, Object> runPlan(Map<String, Object> cfg, Map<String, Object> input,
            BlockConfig blockConfig, PipelineRun run, Path workingDir, String extra, String agentSystemPrompt) throws Exception {

        StringBuilder userMsg = new StringBuilder();
        boolean anyContext = false;

        // Inject context blocks. Resolve via {@link #resolveContextBlock} so this works
        // even when the referenced block isn't in {@code depends_on} (PipelineRunner only
        // injects deps into {@code input}; context_blocks may legitimately reference any
        // earlier block in the run, e.g. task_md when plan_impl depends_on=[create_branch]).
        for (String ctxId : asStringList(cfg, "context_blocks")) {
            Map<String, Object> bMap = resolveContextBlock(input, run, ctxId);
            if (bMap != null) {
                anyContext = true;
                userMsg.append("## Context from block: ").append(ctxId).append('\n');
                bMap.forEach((k, v) -> {
                    String s = String.valueOf(v);
                    if (s.contains("\n")) {
                        // Multiline values (e.g. task_md.body, task_md.to_be) — render as a
                        // sub-section so the structure stays readable instead of one huge
                        // colon-separated line that the model glosses over.
                        userMsg.append("\n### ").append(k).append('\n').append(s).append('\n');
                    } else {
                        userMsg.append(k).append(": ").append(s).append('\n');
                    }
                });
                userMsg.append('\n');
            }
        }

        if (anyContext) {
            String tree = ProjectTreeSummary.summarise(workingDir);
            if (!tree.isEmpty()) {
                userMsg.append("## Codebase layout (working_dir: ").append(workingDir).append(")\n")
                    .append("```\n").append(tree).append("```\n\n")
                    .append("Use this layout to pick real file paths for `files_to_touch`. Read specific ")
                    .append("files with `Read` only when their content matters — do not re-Glob this tree.\n\n");
            }
            userMsg.append("---\n")
                .append("Plan the implementation of the task described above. Base the plan on the ")
                .append("content provided — do NOT search the filesystem for the task itself. Use ")
                .append("Read/Grep/Glob only to find existing source files the plan will modify.\n");
        } else {
            userMsg.append("No task context was provided. Return goal=\"missing task context\" ")
                .append("and stop — do not invent a task.\n");
        }

        return runLoop(blockConfig, run, workingDir, userMsg.toString(),
            buildPlanSystemPrompt(extra, agentSystemPrompt), PLAN_TOOLS, PLAN_BASH,
            asInt(cfg, "max_iterations", DEFAULT_MAX_ITER_PLAN),
            asDouble(cfg, "budget_usd_cap", DEFAULT_BUDGET_USD), "plan");
    }

    // ── Review mode ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> runReview(Map<String, Object> cfg, Map<String, Object> input,
            BlockConfig blockConfig, PipelineRun run, Path workingDir, String extra, String agentSystemPrompt) throws Exception {

        String planBlockId = asString(cfg, "plan_block", "");
        Map<String, Object> planOut = planBlockId.isBlank() ? Map.of()
            : firstNonNull(resolveContextBlock(input, run, planBlockId), Map.of());

        // Single source of truth — acceptance_checklist from analysis (with fallback chain).
        // See loadAcceptanceChecklist for the cascade.
        List<Map<String, Object>> checklist = loadAcceptanceChecklist(input, run, planOut);
        boolean haveChecklist = !checklist.isEmpty();

        StringBuilder userMsg = new StringBuilder("Review the implementation against the acceptance checklist.\n\n");

        if (haveChecklist) {
            userMsg.append("## Acceptance checklist (ЕДИНСТВЕННЫЙ источник истины — оцениваешь только эти пункты)\n");
            userMsg.append("| id | priority | text | source |\n");
            userMsg.append("|---|---|---|---|\n");
            for (Map<String, Object> item : checklist) {
                userMsg.append("| ").append(item.getOrDefault("id", "?"))
                    .append(" | ").append(item.getOrDefault("priority", "important"))
                    .append(" | ").append(String.valueOf(item.getOrDefault("text", ""))
                        .replace("|", "\\|").replace("\n", " "))
                    .append(" | ").append(item.getOrDefault("source", "derived"))
                    .append(" |\n");
            }
            userMsg.append('\n');
        }

        if (!planOut.isEmpty()) {
            Object goal = planOut.get("goal");
            Object dod  = planOut.get("definition_of_done");
            if (goal != null) userMsg.append("## Plan goal\n").append(goal).append("\n\n");
            if (!haveChecklist && dod != null) {
                // Without an acceptance_checklist, expose plan's DoD so the reviewer still has
                // a target to verify. With a checklist, DoD is redundant and clutters the prompt.
                userMsg.append("## Definition of Done\n").append(dod).append("\n\n");
            }
        }

        // Inject context blocks (e.g. build_test results, run_tests output)
        for (String ctxId : asStringList(cfg, "context_blocks")) {
            Map<String, Object> bMap = resolveContextBlock(input, run, ctxId);
            if (bMap != null) {
                userMsg.append("## Context from block: ").append(ctxId).append('\n');
                bMap.forEach((k, v) -> userMsg.append(k).append(": ").append(v).append('\n'));
                userMsg.append('\n');
            }
        }

        // Append loopback feedback from previous review attempt
        boolean isLoopbackIteration = input.get("_loopback") instanceof Map<?, ?> lb && !lb.isEmpty();
        if (isLoopbackIteration) {
            Map<?, ?> lb = (Map<?, ?>) input.get("_loopback");
            Object prevIssues = lb.get("issues");
            if (prevIssues != null && !prevIssues.toString().isBlank()) {
                userMsg.append("## Issues from previous review attempt\n")
                    .append(prevIssues).append("\n\n");
            }

            // PR2: pull previous review's checklist_status from run.outputs (overwritten on
            // next save, but still present at this point in the loop). Lets reviewer focus
            // only on previously-failed items + diff for regressions.
            List<Map<String, Object>> prevStatus = loadPreviousChecklistStatus(blockConfig.getId(), run);
            if (!prevStatus.isEmpty()) {
                userMsg.append("## Previous review checklist_status (focus on passed=false; trust passed=true unless diff contradicts)\n");
                userMsg.append("| id | passed | fix |\n");
                userMsg.append("|---|---|---|\n");
                for (Map<String, Object> ps : prevStatus) {
                    userMsg.append("| ").append(ps.getOrDefault("id", "?"))
                        .append(" | ").append(ps.getOrDefault("passed", "?"))
                        .append(" | ").append(String.valueOf(ps.getOrDefault("fix", ""))
                            .replace("|", "\\|").replace("\n", " "))
                        .append(" |\n");
                }
                userMsg.append('\n');
            }

            // PR2: list files modified by latest codegen invocation (from ToolCallAudit).
            // Reviewer can focus diff inspection on these files instead of re-Glob'ing.
            String targetBlockId = resolveLoopbackTargetId(blockConfig);
            if (targetBlockId != null && !targetBlockId.isBlank()) {
                List<String> changedFiles = findFilesChangedByLastInvocation(run.getId(), targetBlockId);
                if (!changedFiles.isEmpty()) {
                    userMsg.append("## Files changed by last `").append(targetBlockId).append("` iteration\n");
                    for (String f : changedFiles) userMsg.append("- ").append(f).append('\n');
                    userMsg.append('\n');
                }
            }
        }

        // Pre-inject codebase layout so the reviewer doesn't burn iterations re-Glob'ing.
        String tree = ProjectTreeSummary.summarise(workingDir);
        if (!tree.isEmpty()) {
            userMsg.append("## Codebase layout (working_dir: ").append(workingDir).append(")\n")
                .append("```\n").append(tree).append("```\n\n");
        }

        Map<String, Object> result = runLoop(blockConfig, run, workingDir, userMsg.toString(),
            buildReviewSystemPrompt(extra, agentSystemPrompt, haveChecklist), REVIEW_TOOLS, REVIEW_BASH,
            asInt(cfg, "max_iterations", DEFAULT_MAX_ITER_REVIEW),
            asDouble(cfg, "budget_usd_cap", DEFAULT_BUDGET_USD), "review");

        // Code-side decisioning: if reviewer returned checklist_status, derive passed/issues/
        // retry_instruction/action deterministically from per-item priorities + regressions.
        // This prevents opus from "freely re-deciding" on each iteration and bounds the loop.
        if (haveChecklist && result.get("checklist_status") instanceof List<?> cs) {
            Map<String, String> priorityById = checklist.stream()
                .collect(Collectors.toMap(
                    i -> String.valueOf(i.get("id")),
                    i -> String.valueOf(i.getOrDefault("priority", "important")),
                    (a, b) -> a));
            List<Map<String, Object>> regressions = result.get("regressions") instanceof List<?> r
                ? (List<Map<String, Object>>) (List<?>) r : List.of();
            String reviewerAction = String.valueOf(result.getOrDefault("action", "retry"));
            ReviewVerdict v = computeReviewVerdict(
                (List<Map<String, Object>>) (List<?>) cs, regressions, priorityById, reviewerAction);
            result.put("passed", v.passed());
            result.put("action", v.action());
            result.put("issues", v.issues());
            result.put("retry_instruction", v.retryInstruction());
        }

        return result;
    }

    /**
     * Cascade for the acceptance checklist:
     *   1. {@code analysis.acceptance_checklist} — preferred, structured per-item with priority+source
     *   2. plan_block's {@code acceptance_checklist} — if plan transcribed it
     *   3. Auto-derived from plan's {@code definition_of_done} (each non-empty line → synthetic
     *      item id={@code dod-N}, priority={@code important})
     *   4. Empty list — caller falls back to legacy freeform-issues review
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadAcceptanceChecklist(
            Map<String, Object> input, PipelineRun run, Map<String, Object> planOut) {
        // 1+2. Scan inputs (depends_on chain) and run.outputs for any block with checklist
        if (input != null) {
            for (Object v : input.values()) {
                if (v instanceof Map<?, ?> m) {
                    Object cl = m.get("acceptance_checklist");
                    if (cl instanceof List<?> l && !l.isEmpty()) {
                        return castChecklist(l);
                    }
                }
            }
        }
        if (run != null && run.getOutputs() != null) {
            for (com.workflow.core.BlockOutput bo : run.getOutputs()) {
                if (bo == null || bo.getOutputJson() == null) continue;
                try {
                    Map<String, Object> data = objectMapper.readValue(bo.getOutputJson(),
                        new TypeReference<Map<String, Object>>() {});
                    Object cl = data.get("acceptance_checklist");
                    if (cl instanceof List<?> l && !l.isEmpty()) {
                        return castChecklist(l);
                    }
                } catch (Exception ignore) { /* not JSON or not what we want */ }
            }
        }
        // 3. Auto-derive from plan's definition_of_done — split by newlines, synth ids
        if (planOut != null) {
            Object dod = planOut.get("definition_of_done");
            if (dod instanceof String s && !s.isBlank()) {
                List<Map<String, Object>> synth = new ArrayList<>();
                int n = 0;
                for (String line : s.split("\\r?\\n")) {
                    String t = line.strip().replaceFirst("^[-*\\d.\\s]+", "").strip();
                    if (t.isBlank()) continue;
                    n++;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", "dod-" + n);
                    item.put("text", t);
                    item.put("priority", "important");
                    item.put("source", "derived");
                    synth.add(item);
                }
                if (!synth.isEmpty()) return synth;
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castChecklist(List<?> l) {
        List<Map<String, Object>> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    /**
     * Reads the previous review attempt's {@code checklist_status} from the current
     * review block's own output in {@code run.outputs}. The runner overwrites this
     * output once review iteration N completes, but at the start of iteration N
     * the iteration N-1 output is still present.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadPreviousChecklistStatus(String reviewBlockId, PipelineRun run) {
        if (run == null || run.getOutputs() == null) return List.of();
        for (com.workflow.core.BlockOutput bo : run.getOutputs()) {
            if (bo == null || !reviewBlockId.equals(bo.getBlockId())) continue;
            try {
                Map<String, Object> data = objectMapper.readValue(bo.getOutputJson(),
                    new TypeReference<Map<String, Object>>() {});
                Object cs = data.get("checklist_status");
                if (cs instanceof List<?> l && !l.isEmpty()) return castChecklist(l);
            } catch (Exception ignore) { /* stale or wrong shape */ }
        }
        return List.of();
    }

    /** Pulls the loopback target block id from {@code verify.on_fail.target}. */
    private String resolveLoopbackTargetId(BlockConfig blockConfig) {
        if (blockConfig == null) return null;
        var verify = blockConfig.getVerify();
        if (verify == null) return null;
        var onFail = verify.getOnFail();
        if (onFail == null) return null;
        return onFail.getTarget();
    }

    /**
     * Returns unique file paths touched by Write/Edit tool calls in the most recent
     * invocation of {@code targetBlockId} (codegen). "Most recent invocation" =
     * audit records since the latest {@code iteration=1} marker.
     */
    private List<String> findFilesChangedByLastInvocation(UUID runId, String targetBlockId) {
        if (auditRepository == null || runId == null || targetBlockId == null) return List.of();
        List<com.workflow.tools.ToolCallAudit> records;
        try {
            records = auditRepository.findByRunIdAndBlockIdOrderByTimestampAsc(runId, targetBlockId);
        } catch (Exception e) {
            return List.of();
        }
        if (records == null || records.isEmpty()) return List.of();
        // Find start index of latest invocation: walk backward, the first iteration=1 marker is it.
        int start = 0;
        for (int i = records.size() - 1; i >= 0; i--) {
            Integer it = records.get(i).getIteration();
            if (it != null && it == 1) { start = i; break; }
        }
        java.util.LinkedHashSet<String> files = new java.util.LinkedHashSet<>();
        for (int i = start; i < records.size(); i++) {
            var r = records.get(i);
            String tool = r.getToolName();
            if (!"Write".equals(tool) && !"Edit".equals(tool)) continue;
            String path = extractFilePathFromToolInput(r.getInputJson());
            if (path != null && !path.isBlank()) files.add(path);
        }
        return new ArrayList<>(files);
    }

    /** Pulls {@code file_path} (Claude Code convention) from a tool-call inputJson. */
    private String extractFilePathFromToolInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) return null;
        try {
            Map<String, Object> parsed = objectMapper.readValue(inputJson,
                new TypeReference<Map<String, Object>>() {});
            for (String key : List.of("file_path", "path", "filename")) {
                Object v = parsed.get(key);
                if (v instanceof String s && !s.isBlank()) return s;
            }
        } catch (Exception ignore) {}
        return null;
    }

    /** Pure-function output of {@link #computeReviewVerdict}. */
    public record ReviewVerdict(boolean passed, String action, String issues, String retryInstruction) {}

    /**
     * Pure function: turns per-item {@code checklist_status} + {@code regressions} +
     * priority map into the legacy {@code passed/action/issues/retry_instruction} fields
     * that {@link com.workflow.core.PipelineRunner} consumes.
     *
     * <p>Decision rule (Q4 of design):
     * <ul>
     *   <li>nice_to_have failures — ignored</li>
     *   <li>any critical failure OR any regression → action=retry, passed=false</li>
     *   <li>important failures → action=retry, passed=false</li>
     *   <li>nothing failing → passed=true, action=continue</li>
     *   <li>reviewerAction="escalate" preserved if not passed (architectural escalation)</li>
     * </ul>
     */
    public static ReviewVerdict computeReviewVerdict(
            List<Map<String, Object>> checklistStatus,
            List<Map<String, Object>> regressions,
            Map<String, String> priorityById,
            String reviewerAction) {

        List<String> blockingMessages = new ArrayList<>();
        List<String> retryFixes = new ArrayList<>();
        boolean hasBlockingFail = false;

        if (checklistStatus != null) {
            for (Map<String, Object> item : checklistStatus) {
                if (item == null) continue;
                Object passedObj = item.get("passed");
                boolean itemPassed = passedObj instanceof Boolean b ? b : false;
                if (itemPassed) continue;

                String id = String.valueOf(item.getOrDefault("id", "?"));
                String priority = priorityById.getOrDefault(id, "important");
                if ("nice_to_have".equals(priority)) continue; // policy: nice_to_have never blocks

                String fix = String.valueOf(item.getOrDefault("fix", "")).trim();
                String evidence = String.valueOf(item.getOrDefault("evidence", "")).trim();
                hasBlockingFail = true;
                blockingMessages.add(String.format("- [%s] %s: %s%s",
                    priority, id, evidence,
                    fix.isEmpty() ? "" : " | fix: " + fix));
                if (!fix.isEmpty()) retryFixes.add("- " + id + ": " + fix);
            }
        }

        if (regressions != null) {
            for (Map<String, Object> reg : regressions) {
                if (reg == null) continue;
                String desc = String.valueOf(reg.getOrDefault("description", "regression"));
                String evidence = String.valueOf(reg.getOrDefault("evidence", "")).trim();
                hasBlockingFail = true;
                blockingMessages.add("- [REGRESSION] " + desc
                    + (evidence.isEmpty() ? "" : " (" + evidence + ")"));
                retryFixes.add("- REGRESSION: " + desc);
            }
        }

        boolean passed = !hasBlockingFail;
        String action;
        if (passed) action = "continue";
        else if ("escalate".equalsIgnoreCase(reviewerAction)) action = "escalate";
        else action = "retry";

        return new ReviewVerdict(passed, action,
            String.join("\n", blockingMessages),
            String.join("\n", retryFixes));
    }

    /**
     * Resolves a {@code context_blocks} reference. Prefers the in-memory {@code input}
     * map (current run's deps); falls back to {@link PipelineRun#getOutputs()} so that
     * context_blocks can reference any earlier block in the run, not only those in
     * {@code depends_on}. Returns {@code null} when the block hasn't run yet or its
     * output isn't a JSON object.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveContextBlock(Map<String, Object> input, PipelineRun run, String blockId) {
        Object fromInput = input != null ? input.get(blockId) : null;
        if (fromInput instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (run == null) return null;
        return run.getOutputs().stream()
            .filter(o -> blockId.equals(o.getBlockId()))
            .findFirst()
            .map(o -> {
                try {
                    return objectMapper.readValue(o.getOutputJson(),
                        new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    log.warn("orchestrator: failed to deserialise context block '{}' output: {}",
                        blockId, e.getMessage());
                    return (Map<String, Object>) null;
                }
            })
            .orElse(null);
    }

    private static <T> T firstNonNull(T a, T b) { return a != null ? a : b; }

    // ── Agent loop ─────────────────────────────────────────────────────────────

    private Map<String, Object> runLoop(BlockConfig blockConfig, PipelineRun run,
            Path workingDir, String userMessage, String systemPrompt,
            List<String> toolNames, List<String> bashAllowlist,
            int maxIter, double budget, String mode) throws Exception {

        List<Tool> tools = toolRegistry.resolve(toolNames);
        List<ToolDefinition> defs = tools.stream()
            .map(t -> new ToolDefinition(t.name(), t.description(), t.inputSchema(objectMapper)))
            .toList();

        ToolContext toolCtx = new ToolContext(workingDir, bashAllowlist);

        AgentConfig agent = blockConfig.getAgent() != null ? blockConfig.getAgent() : new AgentConfig();
        String model = agent.getModel() != null ? agent.getModel() : resolveProjectModel(defaultModel);

        final String blockId = blockConfig.getId();
        final UUID   runId   = run.getId();

        ToolUseRequest request = ToolUseRequest.builder()
            .model(model)
            .systemPrompt(systemPrompt)
            .userMessage(userMessage)
            .tools(defs)
            .maxTokens(agent.getMaxTokensOrDefault())
            .temperature(agent.getTemperatureOrDefault())
            .maxIterations(maxIter)
            .budgetUsdCap(budget)
            .progressCallback(wsHandler != null ? detail ->
                wsHandler.sendBlockProgress(runId, blockId, detail) : null)
            .build();

        DefaultToolExecutor executor = new DefaultToolExecutor(
            toolRegistry, toolCtx, objectMapper, auditRepository);

        log.info("orchestrator[{}] mode={} model={} workingDir={}", blockId, mode, model, workingDir);
        ToolUseResponse response = llmClient.completeWithTools(request, executor);

        Map<String, Object> parsed = extractJson(response.finalText());

        // Rescue triggers when:
        // 1. extractJson produced a raw_text marker (found text but no valid JSON)
        // 2. extractJson returned empty map but finalText is non-blank (model returned markdown, not JSON)
        String finalText = response.finalText();
        boolean needsRescue = parsed.containsKey("raw_text")
            || (parsed.isEmpty() && finalText != null && !finalText.isBlank());
        if (needsRescue) {
            log.info("orchestrator[{}]: no valid JSON in response (stopReason={}, emptyParsed={}, textLen={}), attempting rescue",
                blockId, response.stopReason(), parsed.isEmpty(), finalText != null ? finalText.length() : 0);
            parsed = rescueJson(finalText, mode, model);
        }

        Map<String, Object> out = new LinkedHashMap<>(parsed);
        out.put("mode", mode);
        out.put("iterations_used", response.iterationsUsed());
        out.put("total_cost_usd", response.totalCostUsd());

        // Fail hard if JSON parsing failed completely — a broken/missing review is not a soft failure
        if (out.containsKey("raw_text")) {
            throw new IllegalStateException(
                "orchestrator[" + blockConfig.getId() + "] mode=" + mode
                + ": failed to extract valid JSON from LLM response after rescue attempt");
        }

        // Detect "loop ran out of iterations / tokens before producing real JSON" — the
        // model never wrote a final plan/review. Distinguish from a genuine escalation,
        // which would carry actual {issues, retry_instruction} or {goal, approach} text.
        // Without this guard, defaults below would fill action=escalate / passed=false
        // and the pipeline would crash with a misleading "Orchestrator escalated:".
        boolean hasRealOutput = "review".equals(mode)
            ? !asString(out, "issues", "").isBlank()
                || !asString(out, "carry_forward", "").isBlank()
                || (out.get("checklist_status") instanceof List<?> cs && !cs.isEmpty())
            : !asString(out, "goal", "").isBlank() || !asString(out, "approach", "").isBlank();
        if (!hasRealOutput) {
            throw new IllegalStateException(
                "orchestrator[" + blockConfig.getId() + "] mode=" + mode
                + ": LLM did not produce any plan/review content"
                + " (stopReason=" + response.stopReason()
                + ", iterations=" + response.iterationsUsed()
                + "). Increase max_iterations or maxTokens, or pick a stronger model.");
        }

        // Ensure optional fields exist so pipeline interpolation never throws
        if ("review".equals(mode)) {
            out.putIfAbsent("passed", Boolean.FALSE);
            out.putIfAbsent("issues", "");
            out.putIfAbsent("action", "retry");
            out.putIfAbsent("retry_instruction", "");
            out.putIfAbsent("carry_forward", "");
            out.putIfAbsent("checklist_status", List.of());
            out.putIfAbsent("regressions", List.of());
        } else {
            out.putIfAbsent("goal", "");
            out.putIfAbsent("files_to_touch", "");
            out.putIfAbsent("approach", "");
            out.putIfAbsent("definition_of_done", "");
            out.putIfAbsent("tools_to_use", "Read, Edit");
            out.putIfAbsent("requirements_coverage", List.of());
        }
        return out;
    }

    // ── System prompts ─────────────────────────────────────────────────────────

    private static String buildPlanSystemPrompt(String extra, String agentSystemPrompt) {
        StringBuilder sb = new StringBuilder();
        if (!agentSystemPrompt.isBlank()) {
            sb.append(agentSystemPrompt).append("\n\n");
        }
        sb.append("""
You are a technical architect. Plan the implementation of the task described in the user message.

CRITICAL — task source of truth:
- The user message contains one or more sections starting with `## Context from block: <block_id>`.
  Those sections ARE the task spec (typically `task_md` carries `title`, `body`, `to_be`,
  `acceptance`, etc. — read them carefully and base the plan on that exact content).
- DO NOT search the filesystem for a task description, README, or other tickets — the task
  is already given. Globbing for `task.md`, `**/*.md`, or similar to "find the task" is wrong
  and produces hallucinated plans about unrelated tickets.
- Use Read/Grep/Glob ONLY to discover EXISTING files in the codebase that the plan will need
  to modify (e.g. find the package layout, locate the class to change). Do NOT modify files.
- If the user message has no `## Context from block:` section, ask the user (return goal=
  "missing task context") instead of inventing a task.

EFFICIENCY:
- The user message includes a `## Codebase layout` section listing real paths in the working
  dir. Use those paths directly for `files_to_touch` — DO NOT Glob the tree again.
- Each tool call burns one iteration. Iteration budget is shown in the user message — pace
  yourself: spend 2-4 iterations exploring (Read of key files), then produce the final JSON.
- Bash CWD does NOT persist between calls. Every Bash starts at the working_dir. Chain via
  `&&` or use absolute paths — `cd subdir && ./gradlew build`, NOT `cd subdir` then later
  `./gradlew build`.
""");
        if (!extra.isBlank()) {
            sb.append("\n## Project context\n").append(extra).append("\n");
        }
        sb.append("""

MANDATORY COVERAGE — before writing the JSON:
1. Re-read the `## Context from block: task_md` section (or whichever task spec block was injected).
2. Enumerate EVERY discrete requirement in it. For a typical task spec written in numbered
   steps, headings, or bullets — every step / heading / bullet is one requirement.
3. For EACH requirement, decide which file(s) it touches and how.
4. Items in `requirements_coverage` MUST cover all of them. An empty or short list is a
   bug — re-read the spec and try again. Do not produce a plan that addresses only a
   subset of the task.

When you have enough information, respond with a JSON object inside a ```json block:
```json
{
  "goal": "one-sentence description of what needs to be implemented (derived from the task spec, not invented)",
  "requirements_coverage": [
    {"requirement": "verbatim or tightly-paraphrased line from the task spec",
     "approach": "how this specific requirement is addressed",
     "files": "newline-separated real paths"}
    // one entry per requirement found in the spec — be exhaustive
  ],
  "files_to_touch": "newline-separated UNION of all paths in requirements_coverage[].files (deduplicated). Must reference real paths discovered via Glob/Grep or new files inside the existing package layout — no placeholders like 'Draft-related implementation files'.",
  "approach": "step-by-step technical approach summarising the work across all requirements",
  "definition_of_done": "newline-separated verifiable completion checklist (one item per requirement plus the mandatory categories below)",
  "tools_to_use": "comma-separated tool names the implementor should use"
}
```

REQUIRED: definition_of_done MUST always include ALL of the following categories:
1. At least one test covering the new or changed logic exists and passes
2. All public API contracts (OpenAPI/Swagger annotations, REST endpoints) are updated to match the implementation
3. README or CLAUDE.md updated if the external behavior or architecture changed
4. No compiler errors or build failures

Respond with the JSON only. No text after the closing ```.
""");
        return sb.toString();
    }

    private static String buildReviewSystemPrompt(String extra, String agentSystemPrompt, boolean haveChecklist) {
        StringBuilder sb = new StringBuilder();
        if (!agentSystemPrompt.isBlank()) {
            sb.append(agentSystemPrompt).append("\n\n");
        }
        sb.append("You are a code reviewer. Verify that the implementation satisfies the acceptance checklist.\n\n");
        sb.append("Use Read, Grep, Glob, and git diff (Bash) to examine the actual changes. Do NOT modify files.\n");
        sb.append("""

EFFICIENCY:
- The user message includes a `## Codebase layout` section. Use it; do not re-Glob.
- Start with `git diff` and `git status` to see WHAT actually changed — review the diff,
  don't re-read the whole codebase.
- Bash CWD does NOT persist between calls. Every Bash starts at working_dir. Chain via
  `&&` or use absolute paths.
- Aim to spend the first 1-2 iterations getting the diff, 2-3 iterations spot-checking
  specific files, then write the final JSON. Don't keep exploring — the iteration budget
  shown in the user message is a hard limit.
""");
        sb.append("CRITICAL: After gathering evidence, you MUST end your response with ONLY a JSON block.\n");
        sb.append("Do NOT write any text after the closing ``` of the JSON block.\n");
        sb.append("Do NOT start your final response with 'Let me', 'I found', or any explanation text.\n");
        if (!extra.isBlank()) {
            sb.append("\n## Project context\n").append(extra).append("\n");
        }
        if (haveChecklist) {
            sb.append("""

PRINCIPLES (важно — иначе уйдёшь в патологический loopback):
- Acceptance checklist в user-message — ЕДИНСТВЕННЫЙ источник истины. Оцениваешь ТОЛЬКО эти id.
- Каждый id из таблицы ОБЯЗАН иметь запись в checklist_status. Не пропускай и не плоди новые id.
- Не добавляй "улучшилось бы если...", "хорошо бы ещё...", замечания по стилю, нейминг,
  отсутствие docstring/комментариев. Это НЕ поводы для passed=false.
- evidence — конкретный путь файла + что искал/что нашёл. "Не нашёл реализации" допустимо
  если действительно отсутствует. Не нужно пересказывать содержимое файла — нужно подтверждение
  что требование пункта выполнено или нет.
- regressions — ТОЛЬКО функциональные поломки: build/compile errors, провалившиеся тесты,
  ранее работавший feature теперь сломан. НЕ субъективные "стало хуже" / "можно красивее".
  Если build_test context показывает успех — regressions=[].
- Если предыдущая итерация уже учла твоё замечание разумно (даже не идеально) — passed=true.

MANDATORY FINAL RESPONSE FORMAT — output ONLY this JSON block when done:
```json
{
  "checklist_status": [
    {"id": "<id из таблицы>", "passed": <true|false>,
     "evidence": "<какой файл/строка/тест подтверждает (или 'не найдено')>",
     "fix": "<если passed=false: что КОНКРЕТНО допилить, иначе пустая строка>"}
  ],
  "regressions": [
    {"description": "<что сломалось>", "evidence": "<git diff snippet или test failure>"}
  ],
  "carry_forward": "brief summary of what was accomplished",
  "action": "retry"
}
```
Rules:
- One entry in checklist_status per item from the acceptance checklist table — same exact ids.
- "regressions": [] if build/tests are green. Use only for objective functional breakage.
- "action": always "retry" by default. Use "escalate" ONLY for architectural conflicts that
  the implementor literally cannot resolve without spec changes (e.g. plan asks for X but
  requirement actually needs Y). Do NOT escalate just because items are not passed.
- DO NOT include "passed", "issues", or "retry_instruction" — those fields are computed
  deterministically by the runner from your checklist_status + regressions.
- Your FINAL message must be ONLY the ```json block. No text before or after it.
""");
        } else {
            sb.append("""

LEGACY MODE (no acceptance_checklist found upstream — falling back to freeform review).

Что проверять:
- Тесты на новую логику запускаются и покрывают её (если задача требует тестов)
- Security: broken access control, SQL injection в нативных запросах
- Производительность: N+1 в JPA, отсутствие индексов на FK
- Логические баги в реализованной фиче

Если предыдущая итерация уже учла замечание разумно — accept. Не ищи новое каждый раз.

MANDATORY FINAL RESPONSE FORMAT — output ONLY this JSON block when done:
```json
{
  "passed": true,
  "issues": "",
  "action": "continue",
  "retry_instruction": "",
  "carry_forward": "brief summary of what was accomplished"
}
```
Rules:
- "passed": true only if all definition-of-done items are met
- "action": "continue" (passed), "retry" (fixable code issues), or "escalate" (architectural/blocking)
- Set retry_instruction to a specific, actionable fix description when action=retry
- Your FINAL message must be ONLY the ```json block. No text before or after it.
""");
        }
        return sb.toString();
    }

    // ── Rescue completion ─────────────────────────────────────────────────────

    private Map<String, Object> rescueJson(String rawText, String mode, String model) {
        String schema = "review".equals(mode)
            ? "{\"passed\": <boolean>, \"issues\": \"<string>\", \"action\": \"<continue|retry|escalate>\","
                + " \"retry_instruction\": \"<string>\", \"carry_forward\": \"<string>\"}"
            : "{\"goal\": \"<string>\","
                + " \"requirements_coverage\": [{\"requirement\": \"<string>\", \"approach\": \"<string>\", \"files\": \"<string>\"}],"
                + " \"files_to_touch\": \"<string>\", \"approach\": \"<string>\","
                + " \"definition_of_done\": \"<string>\", \"tools_to_use\": \"<string>\"}";

        String snippet = rawText != null && rawText.length() > 3000
            ? rawText.substring(rawText.length() - 3000) : rawText;

        String userPrompt = "You were analyzing code. Your findings:\n\n" + snippet
            + "\n\nNow output ONLY valid JSON matching this schema (no markdown, no explanation):\n" + schema;

        try {
            String rescue = llmClient.complete(model,
                "Output only valid JSON. No preamble, no markdown fences, no explanation.",
                userPrompt, 1024, 0.0);
            Map<String, Object> result = extractJson(rescue);
            if (!result.containsKey("raw_text")) {
                log.info("orchestrator: rescue JSON extraction succeeded");
                return result;
            }
        } catch (Exception e) {
            log.warn("orchestrator: rescue JSON extraction failed: {}", e.getMessage());
        }
        return Map.of("raw_text", rawText != null ? rawText : "");
    }

    // ── JSON extraction ────────────────────────────────────────────────────────

    private Map<String, Object> extractJson(String text) {
        if (text == null || text.isBlank()) return Map.of();

        // Try fenced code blocks: ```json ... ``` or plain ``` ... ```
        for (String fence : new String[]{"```json", "```"}) {
            int fenceStart = text.indexOf(fence);
            if (fenceStart >= 0) {
                int lineStart = text.indexOf('\n', fenceStart);
                if (lineStart >= 0) {
                    int fenceEnd = text.indexOf("```", lineStart + 1);
                    if (fenceEnd > lineStart) {
                        String candidate = text.substring(lineStart + 1, fenceEnd).trim();
                        try {
                            return objectMapper.readValue(candidate, new TypeReference<Map<String, Object>>() {});
                        } catch (Exception ignored) { /* try next strategy */ }
                    }
                }
            }
        }

        // Fall back to first { ... } span
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String candidate = text.substring(start, end + 1).trim();
            try {
                return objectMapper.readValue(candidate, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) { /* fall through */ }
        }

        log.warn("orchestrator: could not parse JSON from response (len={}); returning raw_text", text.length());
        return Map.of("raw_text", text);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Path resolveWorkingDir(Map<String, Object> cfg) {
        Object inline = cfg.get("working_dir");
        if (inline != null && !inline.toString().isBlank()) {
            Path p = Paths.get(inline.toString()).toAbsolutePath();
            if (!p.toFile().isDirectory())
                throw new IllegalArgumentException("orchestrator: working_dir is not a directory: " + p);
            return p;
        }
        if (projectRepository != null) {
            String slug = ProjectContext.get();
            if (slug != null && !slug.isBlank()) {
                Project project = projectRepository.findBySlug(slug).orElse(null);
                if (project != null && project.getWorkingDir() != null
                        && !project.getWorkingDir().isBlank()) {
                    return Paths.get(project.getWorkingDir()).toAbsolutePath();
                }
            }
        }
        throw new IllegalArgumentException(
            "orchestrator: working_dir not set in block config and project has no workingDir");
    }

    private String resolveProjectModel(String fallback) {
        if (projectRepository != null) {
            String slug = ProjectContext.get();
            if (slug != null && !slug.isBlank()) {
                Project project = projectRepository.findBySlug(slug).orElse(null);
                if (project != null && project.getOrchestratorModel() != null
                        && !project.getOrchestratorModel().isBlank()) {
                    return project.getOrchestratorModel();
                }
            }
        }
        return fallback;
    }

    private String resolveProjectExtra() {
        if (projectRepository == null) return "";
        String slug = ProjectContext.get();
        if (slug == null || slug.isBlank()) return "";
        Project project = projectRepository.findBySlug(slug).orElse(null);
        if (project == null) return "";
        String extra = project.getOrchestratorSystemPromptExtra();
        return extra != null ? extra : "";
    }

    private static String asString(Map<String, Object> cfg, String key, String def) {
        Object v = cfg.get(key);
        return (v != null && !v.toString().isBlank()) ? v.toString() : def;
    }

    private static List<String> asStringList(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (!(v instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) if (o != null) out.add(o.toString());
        return out;
    }

    private static int asInt(Map<String, Object> cfg, String key, int def) {
        Object v = cfg.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString().trim());
    }

    private static double asDouble(Map<String, Object> cfg, String key, double def) {
        Object v = cfg.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString().trim());
    }
}
