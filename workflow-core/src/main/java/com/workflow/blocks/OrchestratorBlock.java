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
    @Autowired(required = false) private com.workflow.knowledge.KnowledgeBase knowledgeBase;
    @Autowired(required = false) private com.workflow.mcp.McpToolLoader mcpToolLoader;

    @Override public String getName() { return "orchestrator"; }

    @Override
    public boolean isCacheable(BlockConfig config) {
        if (config == null || config.getConfig() == null) return false;
        // Only mode=plan is cacheable. Review verdict depends on the impl attempt's diff —
        // never reuse a review across runs.
        return "plan".equals(String.valueOf(config.getConfig().get("mode")));
    }

    @Override public String getDescription() {
        return "Агент-супервайзер: в режиме plan формирует структурированный план реализации; в режиме review сверяет результат с definition_of_done из плана.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
            "Orchestrator",
            "agent",
            Phase.ANY,
            List.of(
                // ── Essentials ────────────────────────────────────────────────────
                FieldSchema.enumField("mode", "Режим", List.of("plan", "review"),
                    "plan", "plan — построить план; review — проверить результат относительно definition_of_done.")
                    .withLevel("essential"),
                FieldSchema.stringArray("context_blocks", "Контекстные блоки",
                    "ID блоков, чьи выводы передаются в plan-режиме (обычно task_md).")
                    .withLevel("essential"),
                FieldSchema.blockRef("plan_block", "Plan-блок",
                    "ID orchestrator-блока с режимом plan; используется в review-режиме.")
                    .withLevel("essential"),
                // ── Advanced ──────────────────────────────────────────────────────
                FieldSchema.string("working_dir", "Рабочая директория",
                    "Абсолютный путь; если пусто — workingDir проекта."),
                FieldSchema.number("max_iterations", "Max iterations", DEFAULT_MAX_ITER_PLAN,
                    "Максимум раундов агента-супервайзера."),
                FieldSchema.number("budget_usd_cap", "Бюджет USD", DEFAULT_BUDGET_USD,
                    "Лимит стоимости вызовов LLM."),
                FieldSchema.multilineString("system_prompt_extra", "Доп. системный промпт",
                    "Дополнительный контекст проекта (стек, конвенции). Добавляется к встроенному."),
                FieldSchema.bool("auto_inject_rag", "Авто-инъекция top-3 RAG-чанков",
                    false,
                    "На старте plan-mode эмбеддит task_md.to_be (или auto_search_query) "
                        + "и префиксит top-3 семантически релевантных кусков кода в user_message. "
                        + "Снимает галлюцинации package paths когда планер не знает реальной структуры. "
                        + "Требует проиндексированный проект (Settings → Reindex)."),
                FieldSchema.bool("prose_fallback", "Принимать prose как результат", false,
                    "Если модель вернула free-form текст вместо JSON — синтезировать "
                        + "структурированный output (plan: prose→approach; review: passed=true). "
                        + "Полезно для 8B локальных моделей, которые срываются на сложных JSON-схемах. "
                        + "В review-mode рискованно: пайплайн пойдёт дальше, реальные баги ловят build/tests/verify_acceptance."),
                FieldSchema.stringArray("tools", "Tools (override)",
                    "Переопределяет дефолтный список tools для режима. "
                        + "Plan default: Read, Grep, Glob. Review default: Read, Grep, Glob, Bash. "
                        + "Можно сузить (например убрать Bash) или расширить MCP-инструментами. "
                        + "Пустой = дефолт."),
                FieldSchema.stringArray("bash_allowlist", "Bash allowlist (override)",
                    "Переопределяет дефолтный allowlist для Bash в стиле Claude Code (`Bash(git diff*)`). "
                        + "Plan default: git log/status/show. Review default: git diff/log/status/show. "
                        + "Пустой = дефолт; явный пустой список с tools=[..., Bash] полностью запретит Bash."),
                FieldSchema.stringArray("mcp_servers", "MCP-серверы",
                    "Имена зарегистрированных MCP-серверов проекта. Каждый remote-tool становится "
                        + "доступен агенту как `mcp_<server>__<tool>` поверх native tools. "
                        + "Полезно для review-mode, который должен проверять, например, UE5 Blueprints "
                        + "через mcp_unreal_mcp__find_actors_by_name (бинарные .uasset через Read не разобрать).")
            ),
            false,
            Map.of(),
            // Outputs UNION: plan-mode and review-mode produce different keys, both
            // declared here. False-positives on $.review_block.goal are acceptable
            // for PR-1; PR-3 (creation wizard) may stratify by mode.
            List.of(
                // Plan-mode outputs
                FieldSchema.output("goal", "Goal", "string",
                    "[plan] Краткая цель плана (одно предложение)."),
                FieldSchema.output("files_to_touch", "Files to touch", "string",
                    "[plan] Newline-separated список файлов для модификации."),
                FieldSchema.output("approach", "Approach", "string",
                    "[plan] Пошаговый технический подход."),
                FieldSchema.output("definition_of_done", "Definition of done", "string",
                    "[plan] Критерии завершённости (newline-separated)."),
                FieldSchema.output("tools_to_use", "Tools to use", "string",
                    "[plan] Comma-separated имена tools для имплементора."),
                FieldSchema.output("requirements_coverage", "Requirements coverage", "string_array",
                    "[plan] Mapping requirement → approach + files."),
                // Common
                FieldSchema.output("mode", "Mode", "string",
                    "Режим в котором отработал блок (plan | review)."),
                FieldSchema.output("iterations_used", "Iterations used", "number",
                    "Количество фактически использованных раундов."),
                FieldSchema.output("total_cost_usd", "Total cost USD", "number",
                    "Суммарная стоимость в USD."),
                // Review-mode outputs
                FieldSchema.output("passed", "Passed", "boolean",
                    "[review] Финальный вердикт ревью (computed by runner)."),
                FieldSchema.output("issues", "Issues", "string",
                    "[review] Список найденных проблем (multiline string)."),
                FieldSchema.output("action", "Action", "string",
                    "[review] continue | retry | escalate."),
                FieldSchema.output("retry_instruction", "Retry instruction", "string",
                    "[review] Инструкция для retry на impl-блоке."),
                FieldSchema.output("carry_forward", "Carry forward", "string",
                    "[review] Краткое summary что было сделано."),
                FieldSchema.output("checklist_status", "Checklist status", "string_array",
                    "[review] Per-item статус по acceptance_checklist."),
                FieldSchema.output("regressions", "Regressions", "string_array",
                    "[review] Регрессии (build/test breakage)."),
                FieldSchema.output("degraded", "Degraded", "boolean",
                    "true если сработал prose_fallback (модель не выдала JSON, output синтезирован из текста)."),
                FieldSchema.output("degraded_reason", "Degraded reason", "string",
                    "Краткая причина срабатывания prose_fallback.")
            ),
            70
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
        String rawSystemPrompt = agent.getSystemPrompt() != null ? agent.getSystemPrompt().strip() : "";
        String agentSystemPrompt = stringInterpolator != null && !rawSystemPrompt.isEmpty()
            ? stringInterpolator.interpolate(rawSystemPrompt, run, input, workingDir, agent.getPromptContextAllow())
            : rawSystemPrompt;

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
            // Inject the target project's CLAUDE.md so the planner knows the actual
            // tech stack (UE version, build tool, conventions) instead of guessing.
            // Was the root cause of "Unreal 4 editor" hallucination on a UE5.7 project
            // — the planner never saw the project's stack and defaulted to its training prior.
            String claudeMd = com.workflow.project.ProjectClaudeMd.readForPrompt(workingDir);
            if (!claudeMd.isEmpty()) {
                userMsg.insert(0, claudeMd + "\n---\n\n");
            }
            // Auto-inject top-K RAG hits when the project is indexed AND the block opts in.
            // Without this, plan_impl hallucinates package paths (`com.warcard.api/` vs
            // real `ru.gritsay.warcardserver/...`) because it has only CLAUDE.md + tree
            // summary to go on. RAG gives it actual source chunks for the task.
            if (asBool(cfg, "auto_inject_rag", false) && knowledgeBase != null) {
                String ragSection = buildRagSection(cfg, input, run);
                if (!ragSection.isEmpty()) {
                    userMsg.insert(0, ragSection + "\n---\n\n");
                }
            }
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

        if (!planOut.isEmpty() && !haveChecklist) {
            // When acceptance_checklist is present it is the single source of truth.
            // Injecting goal/DoD alongside it causes the reviewer to drift from the
            // checklist and evaluate things outside it. Only expose plan context in
            // legacy freeform mode (no structured checklist).
            Object goal = planOut.get("goal");
            Object dod  = planOut.get("definition_of_done");
            if (goal != null) userMsg.append("## Plan goal\n").append(goal).append("\n\n");
            if (dod != null) userMsg.append("## Definition of Done\n").append(dod).append("\n\n");
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

        // Pre-inject git diff snapshot so reviewer sees changes upfront instead of burning
        // 10+ iterations on Read/Bash to discover what was modified.
        String diffSnapshot = collectGitDiffSnapshot(workingDir.toString());
        if (!diffSnapshot.isEmpty()) {
            userMsg.append(diffSnapshot);
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
    /**
     * Collects a compact "what changed on this branch" snapshot — git log + diff --stat + full diff
     * — and returns it as a Markdown block ready for injection into the reviewer's user message.
     * Without this, glm-4.6/sonnet typically burn 10-20 tool-use iterations discovering the diff
     * via Read/Bash before they even start evaluating. Returns empty string on any failure
     * (non-git dir, git not on PATH, no upstream base found).
     */
    private String collectGitDiffSnapshot(String workingDir) {
        if (workingDir == null || workingDir.isBlank()) return "";
        if (execGit(workingDir, 5, "rev-parse", "--is-inside-work-tree").trim().isEmpty()) return "";

        String base = findGitBase(workingDir);
        StringBuilder sb = new StringBuilder();

        if (base != null) {
            String commitLog = execGit(workingDir, 5, "log", base + "..HEAD", "--oneline").trim();
            String diffStat  = execGit(workingDir, 10, "diff", base + "..HEAD", "--stat").trim();
            String diff      = execGit(workingDir, 15, "diff", base + "..HEAD");
            if (!commitLog.isEmpty() || !diffStat.isEmpty() || !diff.isBlank()) {
                sb.append("## Branch changes vs `").append(base).append("`\n\n");
                if (!commitLog.isEmpty()) sb.append("### Commits\n```\n").append(commitLog).append("\n```\n\n");
                if (!diffStat.isEmpty())  sb.append("### Files changed\n```\n").append(diffStat).append("\n```\n\n");
                if (!diff.isBlank()) {
                    if (diff.length() > 30_000) {
                        diff = diff.substring(0, 30_000) + "\n... [truncated " + (diff.length() - 30_000) + " bytes — use `git diff` tool for full content]";
                    }
                    sb.append("### Full diff\n```diff\n").append(diff).append("\n```\n\n");
                }
            }
        }

        // Uncommitted working-tree changes (codegen may not have committed yet).
        String unstaged = execGit(workingDir, 10, "diff").trim();
        if (!unstaged.isEmpty()) {
            if (unstaged.length() > 15_000) unstaged = unstaged.substring(0, 15_000) + "\n... [truncated]";
            sb.append("### Uncommitted (working tree)\n```diff\n").append(unstaged).append("\n```\n\n");
        }
        String staged = execGit(workingDir, 10, "diff", "--cached").trim();
        if (!staged.isEmpty()) {
            if (staged.length() > 15_000) staged = staged.substring(0, 15_000) + "\n... [truncated]";
            sb.append("### Staged (index)\n```diff\n").append(staged).append("\n```\n\n");
        }

        return sb.toString();
    }

    /** Returns the first existing ref among origin/main, main, origin/master, master, or null. */
    private String findGitBase(String workingDir) {
        for (String candidate : new String[]{"origin/main", "main", "origin/master", "master"}) {
            if (!execGit(workingDir, 5, "rev-parse", "--verify", "--quiet", candidate).trim().isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    /** Runs git with given args in workingDir, returns stdout (errors suppressed). */
    private String execGit(String workingDir, int timeoutSec, String... args) {
        try {
            List<String> cmd = new ArrayList<>(args.length + 1);
            cmd.add("git");
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new java.io.File(workingDir));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "";
            }
            return new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("git {} failed: {}", String.join(" ", args), e.getMessage());
            return "";
        }
    }

    private List<String> findFilesChangedByLastInvocation(UUID runId, String targetBlockId) {
        return com.workflow.tools.AuditUtils.findFilesChangedByLastInvocation(
            auditRepository, objectMapper, runId, targetBlockId);
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

    // ── Finalize tool definitions ──────────────────────────────────────────────
    // The "finalize" tool is a synthetic tool with no backing executor. The model calls
    // it to emit the structured verdict; the provider's tool-use loop short-circuits on
    // the matching name (see OpenAICompatibleProviderClient + OllamaProviderClient) and
    // returns the call's `arguments` JSON as finalText. This bypasses the entire
    // text-JSON parse-and-rescue cascade. Schemas mirror the same shape we used to ask
    // the model to emit in fenced ```json blocks.

    private ToolDefinition finalizeReviewToolDef() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        com.fasterxml.jackson.databind.node.ObjectNode props = schema.putObject("properties");

        com.fasterxml.jackson.databind.node.ObjectNode cs = props.putObject("checklist_status");
        cs.put("type", "array");
        cs.put("description",
            "Per-item verdict for EVERY id in the acceptance_checklist (one entry per id, no extras).");
        com.fasterxml.jackson.databind.node.ObjectNode csItem = cs.putObject("items");
        csItem.put("type", "object");
        com.fasterxml.jackson.databind.node.ObjectNode csProps = csItem.putObject("properties");
        csProps.putObject("id").put("type", "string")
            .put("description", "Id from the acceptance_checklist table — must match exactly, do not invent.");
        csProps.putObject("passed").put("type", "boolean");
        csProps.putObject("evidence").put("type", "string")
            .put("description", "Concrete file:line / grep hit / test output that proves the verdict.");
        csProps.putObject("fix").put("type", "string")
            .put("description", "If passed=false: one-sentence concrete fix instruction. Else empty string.");
        csItem.putArray("required").add("id").add("passed").add("evidence").add("fix");

        com.fasterxml.jackson.databind.node.ObjectNode reg = props.putObject("regressions");
        reg.put("type", "array");
        reg.put("description",
            "Functional regressions only (build/compile/test failures or previously-working features now broken). "
                + "Empty array if build/tests are green. NEVER include style or 'could be better' notes.");
        com.fasterxml.jackson.databind.node.ObjectNode regItem = reg.putObject("items");
        regItem.put("type", "object");
        com.fasterxml.jackson.databind.node.ObjectNode regProps = regItem.putObject("properties");
        regProps.putObject("description").put("type", "string");
        regProps.putObject("evidence").put("type", "string")
            .put("description", "git diff snippet or test failure output.");
        regItem.putArray("required").add("description").add("evidence");

        props.putObject("carry_forward").put("type", "string")
            .put("description", "Brief summary of what was accomplished (1-2 sentences).");
        com.fasterxml.jackson.databind.node.ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.putArray("enum").add("retry").add("escalate");
        action.put("description",
            "Always 'retry' by default. Use 'escalate' ONLY for architectural conflicts the implementor "
                + "literally cannot resolve without spec changes (e.g. requirements contradict the plan).");

        schema.putArray("required").add("checklist_status").add("regressions").add("carry_forward").add("action");

        return new ToolDefinition("finalize_review",
            "Submit the final review verdict. Call this tool ONCE when you have gathered enough evidence. "
                + "The arguments ARE the final answer — do not also write JSON in the text response. "
                + "Every id from the acceptance_checklist must appear exactly once in checklist_status.",
            schema);
    }

    private ToolDefinition finalizePlanToolDef() {
        com.fasterxml.jackson.databind.node.ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        com.fasterxml.jackson.databind.node.ObjectNode props = schema.putObject("properties");

        props.putObject("goal").put("type", "string")
            .put("description", "One-sentence description of what needs to be implemented (derived from the task spec).");

        com.fasterxml.jackson.databind.node.ObjectNode rc = props.putObject("requirements_coverage");
        rc.put("type", "array");
        rc.put("description",
            "One entry per discrete requirement found in the task spec (numbered step / heading / bullet). "
                + "Be exhaustive — empty/short lists are bugs.");
        com.fasterxml.jackson.databind.node.ObjectNode rcItem = rc.putObject("items");
        rcItem.put("type", "object");
        com.fasterxml.jackson.databind.node.ObjectNode rcProps = rcItem.putObject("properties");
        rcProps.putObject("requirement").put("type", "string")
            .put("description", "Verbatim or tightly-paraphrased line from the task spec.");
        rcProps.putObject("approach").put("type", "string");
        rcProps.putObject("files").put("type", "string")
            .put("description", "Newline-separated real paths.");
        rcItem.putArray("required").add("requirement").add("approach").add("files");

        props.putObject("files_to_touch").put("type", "string")
            .put("description", "Newline-separated UNION of all paths in requirements_coverage[].files (deduplicated). "
                + "Real paths from Glob/Grep or new files inside existing package layout — no placeholders.");
        props.putObject("approach").put("type", "string")
            .put("description", "Step-by-step technical approach summarising the work across all requirements.");
        props.putObject("definition_of_done").put("type", "string")
            .put("description", "Newline-separated verifiable completion checklist. MUST include: "
                + "(1) tests covering new/changed logic exist and pass; (2) public API contracts updated; "
                + "(3) README/CLAUDE.md updated if external behavior changed; (4) no compiler errors.");
        props.putObject("tools_to_use").put("type", "string")
            .put("description", "Comma-separated tool names the implementor should use.");

        schema.putArray("required").add("goal").add("files_to_touch").add("approach").add("definition_of_done");

        return new ToolDefinition("finalize_plan",
            "Submit the final implementation plan. Call this tool ONCE when you have gathered enough information. "
                + "The arguments ARE the final answer — do not also write JSON in the text response.",
            schema);
    }

    // ── Agent loop ─────────────────────────────────────────────────────────────

    private Map<String, Object> runLoop(BlockConfig blockConfig, PipelineRun run,
            Path workingDir, String userMessage, String systemPrompt,
            List<String> toolNames, List<String> bashAllowlist,
            int maxIter, double budget, String mode) throws Exception {

        // Per-block tool overrides. cfg.tools / cfg.bash_allowlist replace the mode-defaults
        // entirely (additive merge would mask the operator's intent — empty list means "no Bash").
        // cfg.mcp_servers pulls remote MCP tools (same as agent_with_tools); names are
        // mcp_<server>__<tool>. Unknown native names throw via toolRegistry.resolve so YAML
        // typos are caught at run start, not buried in iteration logs.
        Map<String, Object> cfg = blockConfig.getConfig() != null ? blockConfig.getConfig() : Map.of();
        List<String> resolvedToolNames = cfg.containsKey("tools") ? asStringList(cfg, "tools") : toolNames;
        List<String> resolvedBashAllowlist = cfg.containsKey("bash_allowlist")
            ? asStringList(cfg, "bash_allowlist") : bashAllowlist;
        List<String> mcpServerNames = asStringList(cfg, "mcp_servers");

        List<Tool> nativeTools = toolRegistry.resolve(resolvedToolNames);
        List<Tool> mcpTools = (mcpToolLoader != null && !mcpServerNames.isEmpty())
            ? mcpToolLoader.loadFor(mcpServerNames) : List.of();

        List<ToolDefinition> defs = new ArrayList<>(nativeTools.size() + mcpTools.size() + 1);
        for (Tool t : nativeTools) {
            defs.add(new ToolDefinition(t.name(), t.description(), t.inputSchema(objectMapper)));
        }
        for (Tool t : mcpTools) {
            defs.add(new ToolDefinition(t.name(), t.description(), t.inputSchema(objectMapper)));
        }
        // Append the finalize tool — its arguments ARE the model's structured verdict.
        // Provider's tool-use loop short-circuits on this name (no execution), so we don't
        // register a backing Tool. With forceFinalizeAfter set below, tool_choice gets pinned
        // to this tool past mid-loop — guarantees structured output even when a model
        // (e.g. glm-4.6 with tool_choice=auto) keeps preferring exploratory tools forever.
        String finalizeName = "review".equals(mode) ? "finalize_review" : "finalize_plan";
        defs.add("review".equals(mode) ? finalizeReviewToolDef() : finalizePlanToolDef());

        ToolContext toolCtx = new ToolContext(workingDir, resolvedBashAllowlist);

        AgentConfig agent = blockConfig.getAgent() != null ? blockConfig.getAgent() : new AgentConfig();
        // Project.orchestratorModel wins over tier-resolved agent.model (AgentProfileResolver
        // copies tier="smart" into model="smart", which would otherwise short-circuit the
        // project-level override). Pass agent.model as fallback if project has none.
        String model = resolveProjectModel(agent.getModel() != null ? agent.getModel() : defaultModel);

        final String blockId = blockConfig.getId();
        final UUID   runId   = run.getId();

        // qwen3.6 MoE emits Markdown plans by default and ignores the JSON schema in
        // the system prompt — even when "Respond with the JSON only" is screamed at it.
        // Forcing response_format=json_object via Ollama's OpenAI-compat endpoint pins
        // its output to a JSON object. We accept the tradeoff (model skips tool
        // exploration) because for qwen3.6 it doesn't tool-explore anyway, and the
        // alternative is degraded prose_fallback every time.
        boolean forceJson = model != null && model.toLowerCase().startsWith(com.workflow.llm.Models.FAMILY_QWEN36);

        // Force the finalize tool past mid-loop. maxIter/2 leaves room for exploration first
        // but doesn't let the model burn the entire budget tool-looping. Floor of 2 so 3-4
        // iter budgets still benefit (force kicks in by iter 2, model is allowed 1 free turn).
        int forceFinalizeAfter = Math.max(2, maxIter / 2);

        ToolUseRequest request = ToolUseRequest.builder()
            .model(model)
            .systemPrompt(systemPrompt)
            .userMessage(userMessage)
            .tools(defs)
            .maxTokens(agent.getMaxTokensOrDefault())
            .temperature(agent.getTemperatureOrDefault())
            .maxIterations(maxIter)
            .budgetUsdCap(budget)
            .workingDir(workingDir)
            .completionSignal(agent.getCompletionSignal())
            .responseFormat(forceJson ? "json" : null)
            // NOTE: response_format=json_object causes Ollama models to skip tool_calls
            // entirely (they output a single JSON immediately without exploring the codebase).
            // For other models we leave it unconstrained — qwen3.6 is the one model
            // that *only* produces usable JSON when forced.
            .finalizeToolName(finalizeName)
            .forceFinalizeAfter(forceFinalizeAfter)
            .progressCallback(wsHandler != null ? detail ->
                wsHandler.sendBlockProgress(runId, blockId, detail) : null)
            .build();

        DefaultToolExecutor executor = new DefaultToolExecutor(
            toolRegistry, toolCtx, objectMapper, auditRepository, mcpTools);

        log.info("orchestrator[{}] mode={} model={} workingDir={}", blockId, mode, model, workingDir);
        // Free VRAM from any other Ollama model still cached before we start —
        // qwen3.6:35b-a3b (28 GB) + cline_roocode:8b (5 GB) thrash 8 GB GPUs otherwise.
        llmClient.unloadOllamaModelsExcept(resolveOllamaTag(model));
        // Previously we force-routed OLLAMA orchestrator calls through OpenRouter because
        // qwen2.5:7b lapsed into prose. With Ollama's `format: "json"` grammar constraint set
        // above and pre-loaded git diff in the review user-message, we keep the project's
        // chosen provider intact — no more split-brain billing.
        ToolUseResponse response = llmClient.completeWithTools(request, executor);

        Map<String, Object> parsed = extractJson(response.finalText());

        // Rescue triggers when:
        // 1. extractJson produced a raw_text marker (found text but no valid JSON)
        // 2. extractJson returned empty map but finalText is non-blank (model returned markdown, not JSON)
        String finalText = response.finalText();
        boolean needsRescue = parsed.containsKey("raw_text")
            || (parsed.isEmpty() && finalText != null && !finalText.isBlank())
            || isSuspiciouslyTruncated(parsed, mode);
        if (needsRescue) {
            log.info("orchestrator[{}]: no valid JSON in response (stopReason={}, emptyParsed={}, textLen={}), attempting recovery",
                blockId, response.stopReason(), parsed.isEmpty(), finalText != null ? finalText.length() : 0);
            // Recovery cascade (cheap → expensive):
            // 1. Markdown extractor — many local models (qwen3.6, hermes3) emit
            //    structured Markdown plans regardless of JSON instructions.
            // 2. Continuation-call — JSON started but cut off mid-output.
            // 3. rescueJson — full regenerate with the schema.
            Map<String, Object> recovered = extractFromMarkdown(finalText, mode);
            if (recovered != null && !isSuspiciouslyTruncated(recovered, mode)) {
                log.info("orchestrator[{}]: recovered via Markdown extraction (no JSON in response)", blockId);
                parsed = recovered;
            } else {
                Map<String, Object> continued = tryContinuation(finalText, request, model, mode);
                if (continued != null && !continued.containsKey("raw_text") && !isSuspiciouslyTruncated(continued, mode)) {
                    log.info("orchestrator[{}]: JSON recovered via continuation-call", blockId);
                    parsed = continued;
                } else {
                    parsed = rescueJson(finalText, mode, model);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>(parsed);
        out.put("mode", mode);
        out.put("iterations_used", response.iterationsUsed());
        out.put("total_cost_usd", response.totalCostUsd());

        // Opt-in lenient mode: when the model returns prose instead of JSON, salvage it
        // into the expected schema and continue rather than crashing the run. Useful
        // for 8B local models that fail the orchestrator JSON protocol but produce
        // semantically reasonable plans / reviews. Plan-side: relatively safe (prose
        // becomes `approach`, impl_server gets a text instruction). Review-side: risky
        // (we have to assume passed=true since prose lacks structured verdict) —
        // downstream build/tests/verify_acceptance must catch real regressions.
        Map<String, Object> blockCfg = blockConfig.getConfig() != null ? blockConfig.getConfig() : Map.of();
        boolean proseFallback = asBool(blockCfg, "prose_fallback", false);

        // Fail hard if JSON parsing failed completely — a broken/missing review is not a soft failure
        if (out.containsKey("raw_text")) {
            String rawText = asString(out, "raw_text", "");
            if (proseFallback && !rawText.isBlank()) {
                log.warn("orchestrator[{}] mode={}: prose_fallback engaged — synthesising structured output from {} chars of free-form text",
                    blockConfig.getId(), mode, rawText.length());
                out = synthesiseFromProse(rawText, mode, response);
            } else {
                throw new IllegalStateException(
                    "orchestrator[" + blockConfig.getId() + "] mode=" + mode
                    + ": failed to extract valid JSON from LLM response after rescue attempt");
            }
        } else {
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
                if (proseFallback && response.finalText() != null && !response.finalText().isBlank()) {
                    log.warn("orchestrator[{}] mode={}: prose_fallback engaged — no structured output, salvaging {} chars of final_text",
                        blockConfig.getId(), mode, response.finalText().length());
                    out = synthesiseFromProse(response.finalText(), mode, response);
                } else {
                    throw new IllegalStateException(
                        "orchestrator[" + blockConfig.getId() + "] mode=" + mode
                        + ": LLM did not produce any plan/review content"
                        + " (stopReason=" + response.stopReason()
                        + ", iterations=" + response.iterationsUsed()
                        + "). Increase max_iterations or maxTokens, or pick a stronger model.");
                }
            }
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

When you have enough information, you MUST call the `finalize_plan` tool with the structured plan
as its arguments. The tool's arguments ARE the final answer — do NOT also emit JSON in the text
response. (Legacy fallback only: if you cannot call the tool, you may emit a fenced ```json block
matching the same schema; the tool path is preferred and required by the loop.)

Schema for `finalize_plan`:
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
- passed=true ТОЛЬКО если ты лично через Read/Grep/Bash/git diff нашёл материальное доказательство:
  конкретный файл:строка, grep-хит, вывод теста. "По конструкции", "intent", "должно работать",
  "EnumRedirects настроен", "TMap инициализирован" — это НЕ evidence, это passed=false.
- Если предыдущая итерация уже учла твоё замечание разумно (даже не идеально) — passed=true.
- UNVERIFIABLE ITEMS: если пункт требует инструментов/контекста, недоступных в pipeline
  (UE Editor, полный UE build, post-pipeline шаги вроде архивации task файла, внешние GUI) —
  ставь passed=true с evidence="Not verifiable in pipeline context: <причина>".
  Никогда не ставь passed=false для того, что физически невозможно проверить.

## Анти-паттерны retry (НЕ повод откатывать на codegen)

- Стиль, нейминг переменных, отсутствие Javadoc/docstring/комментариев, неполная документация —
  НЕ повод выставлять passed=false. Это всегда nice-to-have.
- "Улучшилось бы если...", "хорошо бы ещё добавить X", "можно красивее" — НЕ поводы.
- Бар прохождения: код решает задачу, не ломает существующее, не несёт security/perf-регрессий.
  Этого ДОСТАТОЧНО — даже если реализация не идеальна.
- Если предыдущая итерация уже учла твоё замечание разумно (даже не идеально) — passed=true,
  НЕ ищи новое замечание на ту же тему.
- Каждый passed=false должен указывать на КОНКРЕТНУЮ ФУНКЦИОНАЛЬНУЮ проблему с измеримым fix,
  не "хорошо бы ещё...".
- Если сомневаешься passed=true или passed=false — выбирай passed=true.

FINAL VERDICT — call the `finalize_review` tool. Its arguments ARE the final answer:
the structured per-id verdict goes directly into tool_call.arguments. Do NOT also emit
JSON in the text response. (Legacy fallback only: if for some reason you cannot call the
tool, emit the same JSON shape inside a fenced ```json block; the tool path is preferred
and is forced past mid-loop.)

Schema mirrored by the tool:
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

CONCRETE EXAMPLE — for an acceptance checklist with ids `dod-1`, `dod-2`, `dod-3`,
your FINAL message must look exactly like this (one `checklist_status` entry per checklist id):
```json
{
  "checklist_status": [
    {"id": "dod-1", "passed": true,
     "evidence": "Source/WarCard/UI/UnitTooltipWidget.cpp:24-58 implements OnHoverChanged + ShowTooltip; git diff shows new file added in this branch",
     "fix": ""},
    {"id": "dod-2", "passed": false,
     "evidence": "No tests added: Tests/UI/ has no new files in git diff and grep for UnitTooltipWidget in Tests/ returned 0 results",
     "fix": "Add at least one test in Tests/UI/UnitTooltipWidgetTest.cpp covering both visible and hidden tooltip states"},
    {"id": "dod-3", "passed": true,
     "evidence": "StrategyPlayerController.cpp:142 hooks HandleHoverUnit -> UnitTooltipWidget; verified via grep and Read of the changed file",
     "fix": ""}
  ],
  "regressions": [],
  "carry_forward": "UnitTooltipWidget added with hover wiring in StrategyPlayerController; tests still missing.",
  "action": "retry"
}
```
Notice in the example: every checklist id appears exactly once in `checklist_status`,
evidence cites concrete `file:line` or `grep` results, fix is a one-sentence actionable
instruction for the implementor. Copy this shape exactly, substituting your real ids/evidence.
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

    // ── Markdown salvage ──────────────────────────────────────────────────────

    /**
     * Extracts plan/review fields from a Markdown response. Some local models
     * (qwen3.6:35b-a3b, hermes3:8b) refuse to emit JSON regardless of system-prompt
     * pressure or {@code response_format: json_object} — they consistently produce
     * well-structured Markdown plans instead. This parser maps Markdown sections
     * onto the same fields the orchestrator schema expects, so the run keeps moving
     * without the prose_fallback degraded path.
     *
     * <p>Returns null when the input doesn't look like a Markdown plan (no recognisable
     * headings, or too few fields extracted to be useful). Callers fall through to the
     * next salvage layer.
     */
    private Map<String, Object> extractFromMarkdown(String text, String mode) {
        if (text == null || text.isBlank()) return null;
        // Strip leading/trailing code-fence wrappers some models add
        String t = text.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            int last = t.lastIndexOf("```");
            if (last > 0) t = t.substring(0, last);
        }

        // Pattern: heading line "# X" or "## X" or "### X", optionally with trailing ":"
        java.util.regex.Pattern headingRe = java.util.regex.Pattern.compile(
            "(?m)^#{1,6}\\s+(.+?)\\s*:?\\s*$");
        // Split into (heading, body) pairs
        java.util.List<int[]> headings = new java.util.ArrayList<>();
        java.util.regex.Matcher m = headingRe.matcher(t);
        while (m.find()) headings.add(new int[]{m.start(), m.end()});
        if (headings.size() < 2) return null;  // not Markdown-structured

        // Extract section bodies keyed by lowercased heading title
        java.util.Map<String, String> sections = new java.util.LinkedHashMap<>();
        for (int i = 0; i < headings.size(); i++) {
            int hStart = headings.get(i)[0];
            int hEnd   = headings.get(i)[1];
            int bodyEnd = i + 1 < headings.size() ? headings.get(i + 1)[0] : t.length();
            String title = t.substring(t.indexOf(' ', hStart) + 1, hEnd).replaceAll(":\\s*$", "").trim();
            String body  = t.substring(hEnd, bodyEnd).trim();
            sections.put(title.toLowerCase(java.util.Locale.ROOT), body);
        }

        if ("review".equals(mode)) {
            return buildReviewFromMarkdown(sections, t);
        }
        return buildPlanFromMarkdown(sections, t);
    }

    /**
     * Returns null when too few plan fields could be extracted — caller falls through
     * to continuation/rescue rather than committing a near-empty result.
     */
    private static Map<String, Object> buildPlanFromMarkdown(java.util.Map<String, String> sections, String full) {
        String goal = findSection(sections, "goal", "цель", "objective");
        String approach = findSection(sections, "approach", "подход", "implementation plan",
            "implementation", "план реализации", "phase 1", "phase 2");
        String filesToTouch = findSection(sections, "files to touch", "files", "файлы",
            "files modified", "files to modify");
        String definitionOfDone = findSection(sections, "definition of done", "acceptance criteria",
            "acceptance", "definition-of-done", "критерии завершения", "тесты", "tests",
            "verification");
        String tools = findSection(sections, "tools to use", "tools", "инструменты");

        // If section "approach" wasn't found, dump everything-after-first-heading as approach
        if (approach.isBlank() && !sections.isEmpty()) {
            int firstHeadingPos = full.indexOf('\n');
            approach = firstHeadingPos > 0 ? full.substring(firstHeadingPos).trim() : full;
        }
        // Extract file paths from any table / list throughout the doc — handles "| File | Action |" tables.
        if (filesToTouch.isBlank()) filesToTouch = extractFilePathsFromMarkdown(full);

        // Confidence threshold: at least goal OR a meaningful approach + something else
        boolean enough = !goal.isBlank() && (!approach.isBlank() || !filesToTouch.isBlank());
        if (!enough) return null;

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("goal", goal.isBlank() ? "(extracted from markdown — title missing)" : goal);
        out.put("approach", approach);
        out.put("files_to_touch", filesToTouch);
        out.put("definition_of_done", definitionOfDone);
        out.put("tools_to_use", tools.isBlank() ? "Read, Edit, Grep, Glob, Bash" : tools);
        out.put("requirements_coverage", List.of());  // markdown rarely carries this structurally
        return out;
    }

    private static Map<String, Object> buildReviewFromMarkdown(java.util.Map<String, String> sections, String full) {
        String issues = findSection(sections, "issues", "problems", "проблемы", "regressions");
        String carry = findSection(sections, "carry forward", "summary", "what was done",
            "what was accomplished", "результат");
        String retryInstruction = findSection(sections, "retry instruction", "fix",
            "what to fix", "что исправить");
        String actionRaw = findSection(sections, "action", "decision", "verdict", "действие");
        String action = "continue";
        String lower = actionRaw.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("retry")) action = "retry";
        else if (lower.contains("escalate")) action = "escalate";

        // Passed/Failed heuristic: explicit "passed: true/false" in any section, or
        // presence of issues/retry implies passed=false.
        boolean passed;
        String passedRaw = findSection(sections, "passed", "verdict", "status");
        if (passedRaw.toLowerCase().contains("true")) passed = true;
        else if (passedRaw.toLowerCase().contains("false")) passed = false;
        else passed = issues.isBlank() && retryInstruction.isBlank();

        boolean enough = !issues.isBlank() || !carry.isBlank() || !passedRaw.isBlank();
        if (!enough) return null;

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("passed", passed);
        out.put("issues", issues);
        out.put("action", action);
        out.put("retry_instruction", retryInstruction);
        out.put("carry_forward", carry.isBlank() ? full.substring(0, Math.min(500, full.length())) : carry);
        out.put("checklist_status", List.of());
        out.put("regressions", List.of());
        return out;
    }

    /** Returns the body of the first section whose title matches any of the candidates. */
    private static String findSection(java.util.Map<String, String> sections, String... candidates) {
        for (String cand : candidates) {
            String body = sections.get(cand.toLowerCase());
            if (body != null && !body.isBlank()) return body;
        }
        // Substring match (e.g. "## 2.1 C++ Core Logic" → "core")
        for (var e : sections.entrySet()) {
            for (String cand : candidates) {
                if (e.getKey().contains(cand.toLowerCase())) {
                    return e.getValue();
                }
            }
        }
        return "";
    }

    /**
     * Scans the full markdown for likely source file paths — `path/to/file.ext` patterns
     * appearing in tables or backticks. Used as a fallback when no explicit "Files" section.
     */
    private static String extractFilePathsFromMarkdown(String text) {
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        // Backtick-quoted paths or table cells with `path/...` shape
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "`([A-Za-z0-9_./-]+\\.[A-Za-z0-9]+)`").matcher(text);
        while (m.find()) paths.add(m.group(1));
        // Plain table cells with paths (no backticks)
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
            "(?m)^\\|\\s*([A-Za-z0-9_./-]+/[A-Za-z0-9_./-]+\\.[A-Za-z0-9]+)\\s*\\|").matcher(text);
        while (m2.find()) paths.add(m2.group(1));
        return String.join("\n", paths);
    }

    // ── Continuation completion ───────────────────────────────────────────────

    /**
     * Calls the LLM with the original system+user messages, the model's truncated
     * assistant turn, and a final user-tail asking it to <em>continue from where it
     * stopped</em>. Combines original + continuation and re-tries JSON extraction.
     *
     * <p>Cheaper and more reliable than {@link #rescueJson} (which regenerates the
     * whole structure): the model only needs to write the missing closing chars +
     * the few remaining payload items, not the entire plan from scratch. Bypasses
     * {@code OLLAMA_MAX_TOKENS_CAP} via {@code completeWithMessages(..., 4096, ...)}.
     */
    private Map<String, Object> tryContinuation(String rawText, ToolUseRequest request,
                                                String model, String mode) {
        if (rawText == null || rawText.isBlank()) return null;
        // Guard: only continue if model started a JSON object near the head — if rawText
        // is pure prose this path won't help, fall through to rescue.
        int firstBrace = rawText.indexOf('{');
        if (firstBrace < 0 || firstBrace > 200) return null;

        try {
            java.util.List<java.util.Map<String, String>> msgs = new java.util.ArrayList<>();
            if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                msgs.add(java.util.Map.of("role", "system", "content", request.systemPrompt()));
            }
            msgs.add(java.util.Map.of("role", "user", "content", request.userMessage()));
            msgs.add(java.util.Map.of("role", "assistant", "content", rawText));
            msgs.add(java.util.Map.of("role", "user", "content",
                "Your previous response was cut off mid-output. Continue from EXACTLY where you stopped — "
                    + "do NOT repeat anything already written, do NOT add markdown fences, do NOT prepend "
                    + "any explanation. Output only the continuation characters and ensure every open "
                    + "brace `{`, bracket `[`, and string `\"` is properly closed."));

            String continuation = llmClient.completeWithMessages(model, msgs, 4096, 0.0);
            if (continuation == null || continuation.isBlank()) return null;
            // Strip common preamble patterns models add despite being told not to.
            continuation = stripContinuationPreamble(continuation);

            String combined = rawText + continuation;
            log.info("orchestrator: continuation-call produced {} extra chars (was {} → now {})",
                continuation.length(), rawText.length(), combined.length());
            Map<String, Object> parsed = extractJson(combined);
            if (!parsed.containsKey("raw_text")) {
                return parsed;
            }
        } catch (Exception e) {
            log.warn("orchestrator: continuation-call failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Strips OpenRouter-style vendor prefix from a model name when the result looks
     * like an Ollama tag — only used for the VRAM-pinning hint. {@code claude-sonnet-4-6}
     * stays unchanged (CLI route, never goes to Ollama anyway).
     */
    private static String resolveOllamaTag(String model) {
        if (model == null) return null;
        // Ollama community models use `user/model:tag` — keep as-is.
        if (model.contains("/") && model.contains(":")) return model;
        return model;  // bare names like `qwen3.6:35b-a3b`
    }

    /** Trim "Sure, here's the continuation:" / "```json" / leading whitespace patterns. */
    private static String stripContinuationPreamble(String s) {
        String t = s;
        // Remove leading ```json or ``` lines if present
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
        }
        // Drop trailing ``` if present
        int fenceEnd = t.lastIndexOf("```");
        if (fenceEnd > 0 && fenceEnd > t.length() - 10) t = t.substring(0, fenceEnd);
        // Strip lines like "Continuation:" / "Here's the rest:" only if at the very start
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "^(continuation|here[''']s the rest|sure)[^\\n]{0,40}\\n",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
        if (m.find() && m.start() == 0) t = t.substring(m.end());
        return t;
    }

    /**
     * Returns true when the parsed JSON is structurally valid but its payload
     * looks too small to be a real plan/review — i.e. the model truncated and
     * {@code balanceJsonStructure} stitched up empty closers.
     *
     * <p>Plan-mode: goal is missing or just {@code "{"}, OR fewer than 3 entries
     * in requirements_coverage. Review-mode: passed/issues/carry_forward all
     * empty and checklist_status is empty.
     */
    @SuppressWarnings("unchecked")
    private static boolean isSuspiciouslyTruncated(Map<String, Object> parsed, String mode) {
        if (parsed == null || parsed.isEmpty()) return false;
        if ("review".equals(mode)) {
            String issues = asString(parsed, "issues", "");
            String carry = asString(parsed, "carry_forward", "");
            Object cs = parsed.get("checklist_status");
            int csSize = (cs instanceof List<?> l) ? l.size() : 0;
            return issues.isBlank() && carry.isBlank() && csSize == 0
                && !parsed.containsKey("passed");
        }
        // plan-mode
        String goal = asString(parsed, "goal", "");
        if (goal.trim().length() <= 2) return true;  // "{" or empty
        Object rc = parsed.get("requirements_coverage");
        int rcSize = (rc instanceof List<?> l) ? l.size() : 0;
        // If the model started a real plan but stopped after only 1-2 entries,
        // that's truncation. Threshold 3 is the minimum we accept as "complete enough".
        return rcSize > 0 && rcSize < 3;
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

        // Head + tail (6K each, 12K total) — last-3K-only failed for FEAT-AP-002 because
        // the JSON head with `{"goal": "..."` was lost and the rescue LLM couldn't
        // reconstruct from a tail-only view (returned literal `null`).
        String snippet;
        if (rawText == null) {
            snippet = "";
        } else if (rawText.length() <= 12000) {
            snippet = rawText;
        } else {
            snippet = rawText.substring(0, 6000)
                + "\n…[" + (rawText.length() - 12000) + " chars elided]…\n"
                + rawText.substring(rawText.length() - 6000);
        }

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
                        Map<String, Object> r = tryParseWithFixup(text.substring(lineStart + 1, fenceEnd).trim());
                        if (r != null) return r;
                    }
                }
            }
        }

        // Fall back to first { ... } span
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            Map<String, Object> r = tryParseWithFixup(text.substring(start, end + 1).trim());
            if (r != null) return r;
        }

        // Log the actual content so failures are diagnosable — without this, the only
        // signal is "len=5238" with no clue what the model actually returned. Cap at 4K
        // to keep logs readable; head + tail covers the common "model wrote prose then
        // trailed off" and "model started with explanation before JSON" patterns.
        String preview;
        if (text.length() <= 4000) {
            preview = text;
        } else {
            preview = text.substring(0, 2000) + "\n…[" + (text.length() - 4000) + " chars elided]…\n"
                + text.substring(text.length() - 2000);
        }
        log.warn("orchestrator: could not parse JSON from response (len={}); raw text:\n---\n{}\n---",
            text.length(), preview);
        return Map.of("raw_text", text);
    }

    /**
     * Strict parse first; on failure, runs {@link #fixCommonJsonErrors(String)} and retries.
     * Returns {@code null} only when both passes fail — callers fall through to the next
     * extraction strategy (fence vs braces) or to {@code raw_text}.
     */
    private Map<String, Object> tryParseWithFixup(String candidate) {
        try {
            return objectMapper.readValue(candidate, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) { /* try fixup */ }
        String fixed = fixCommonJsonErrors(candidate);
        if (fixed.equals(candidate)) return null;
        try {
            Map<String, Object> result = objectMapper.readValue(fixed, new TypeReference<Map<String, Object>>() {});
            log.info("orchestrator: JSON parsed after fixup pass (len={}, original strict-parse failed)",
                fixed.length());
            return result;
        } catch (Exception ignored) { return null; }
    }

    /**
     * Heuristic repair for the common LLM JSON typos we see in practice.
     * Conservative: only fires when strict parse already failed.
     *
     * <p>Handles:
     * <ul>
     *   <li><b>Missing colon after key</b> — e.g. {@code "approach "Объявить...} (glm-4.6 on
     *       FEAT-AP-002). The model swallowed the {@code ": "} between key and value,
     *       leaving a trailing space inside the key. Repaired to {@code "approach": "Объявить...}.</li>
     *   <li><b>Smart quotes</b> — {@code “ ” ‘ ’} → straight ASCII quotes.</li>
     *   <li><b>Trailing comma</b> before {@code }} or {@code ]} — {@code [1, 2,]} → {@code [1, 2]}.</li>
     * </ul>
     *
     * <p>Key-position anchoring (lookbehind on {@code \{ , \n}) keeps the regex from
     * misfiring inside string values that happen to contain a {@code "word "} fragment.
     */
    static String fixCommonJsonErrors(String json) {
        if (json == null) return null;
        String result = json;
        // 1. Smart quotes → straight
        result = result
            .replace('“', '"').replace('”', '"')
            .replace('‘', '\'').replace('’', '\'');
        // 2. Missing colon: `"key "value...` → `"key": "value...`
        //    Anchored at key-position (after `{`, `,`, or newline + ws) so we don't
        //    rewrite incidental `"word "` fragments inside JSON string values.
        //    Lookahead requires a value character (letter or digit) so we don't fire
        //    on cases like `"key " ,` (which is a different malformation).
        result = java.util.regex.Pattern.compile(
                "(?<=[\\{,\\n])(\\s*)\"([\\p{L}_][\\p{L}\\p{N}_]*) \"(?=[\\p{L}\\p{N}])",
                java.util.regex.Pattern.UNICODE_CHARACTER_CLASS)
            .matcher(result).replaceAll("$1\"$2\": \"");
        // 3. Trailing comma before `}` or `]`
        result = result.replaceAll(",(\\s*[}\\]])", "$1");
        // 4. Unclosed structures — common when small models hit a self-imposed
        //    stop-token mid-JSON (qwen3_cline_roocode on FEAT-AP-002 stopped after
        //    starting a `requirements_coverage` entry). Walk outside string literals,
        //    track brace/bracket depth, and append the required closers in reverse
        //    order. We also close any open string literal first by adding a closing
        //    quote on the last line if the brace scan finds an unterminated string.
        result = balanceJsonStructure(result);
        return result;
    }

    /**
     * Appends missing {@code "}/{@code ]}/{@code "} characters when the input
     * is a truncated-but-mostly-well-formed JSON object. Only fires when the
     * stack has unclosed structures at end-of-string; well-formed input is returned
     * unchanged. Skips string contents (including escaped quotes) when counting.
     */
    private static String balanceJsonStructure(String json) {
        if (json == null || json.isEmpty()) return json;
        java.util.ArrayDeque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escape) { escape = false; continue; }
                if (c == '\\') { escape = true; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') stack.push('}');
            else if (c == '[') stack.push(']');
            else if (c == '}' || c == ']') {
                if (!stack.isEmpty() && stack.peek() == c) stack.pop();
            }
        }
        if (!inString && stack.isEmpty()) return json;
        StringBuilder sb = new StringBuilder(json);
        if (inString) sb.append('"');
        // Drop any trailing comma that would otherwise sit right before our injected closer.
        while (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last == ',' || Character.isWhitespace(last)) sb.deleteCharAt(sb.length() - 1);
            else break;
        }
        while (!stack.isEmpty()) sb.append(stack.pop());
        return sb.toString();
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

    private static boolean asBool(Map<String, Object> cfg, String key, boolean def) {
        Object v = cfg.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase();
        return s.equals("true") || s.equals("yes") || s.equals("1");
    }

    /** Cap on RAG section size so it doesn't drown the planner's own task context. */
    private static final int ORCH_RAG_MAX_CHARS = 5_000;
    private static final int ORCH_RAG_TOP_K = 3;

    /**
     * Builds the `## Релевантный код` section to prepend to the planner's user_message.
     * Queries {@link com.workflow.knowledge.KnowledgeBase} with the task description
     * (or explicit {@code auto_search_query}). Returns "" when the index is empty,
     * the query is missing, or the knowledge layer is disabled.
     */
    @SuppressWarnings("unchecked")
    private String buildRagSection(Map<String, Object> cfg, Map<String, Object> input,
                                   com.workflow.core.PipelineRun run) {
        String slug = com.workflow.project.ProjectContext.get();
        if (slug == null || slug.isBlank()) {
            log.warn("orchestrator auto_inject_rag: skipped — ProjectContext slug empty");
            return "";
        }
        String query = pickPlannerRagQuery(cfg, input, run);
        if (query == null || query.isBlank()) {
            log.warn("orchestrator auto_inject_rag: skipped — search query empty (no auto_search_query, no task_md.to_be/title in input or run outputs)");
            return "";
        }
        try {
            log.info("orchestrator auto_inject_rag: querying slug={} query='{}...' top_k={}",
                slug, query.length() > 60 ? query.substring(0, 60) : query, ORCH_RAG_TOP_K);
            java.util.List<com.workflow.knowledge.KnowledgeHit> hits =
                knowledgeBase.search(slug, query, ORCH_RAG_TOP_K);
            if (hits.isEmpty()) {
                log.warn("orchestrator auto_inject_rag: skipped — 0 hits from knowledgeBase for slug={}", slug);
                return "";
            }
            log.info("orchestrator auto_inject_rag: got {} hits", hits.size());
            StringBuilder sb = new StringBuilder();
            sb.append("## Релевантный код (top-").append(hits.size())
              .append(" семантически по запросу: «")
              .append(query.length() > 100 ? query.substring(0, 100) + "…" : query)
              .append("»)\n\n");
            int total = 0;
            for (com.workflow.knowledge.KnowledgeHit h : hits) {
                String header = "### " + h.path() + ':' + h.startLine() + '-' + h.endLine() + "\n```\n";
                String tail = "\n```\n\n";
                int budget = ORCH_RAG_MAX_CHARS - total - header.length() - tail.length();
                if (budget <= 0) break;
                String content = h.content();
                if (content.length() > budget) content = content.substring(0, budget) + "\n…[truncated]";
                sb.append(header).append(content).append(tail);
                total += header.length() + content.length() + tail.length();
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("orchestrator auto_inject_rag: skipped — knowledgeBase.search threw: {}", e.toString());
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private String pickPlannerRagQuery(Map<String, Object> cfg, Map<String, Object> input,
                                       com.workflow.core.PipelineRun run) {
        Object q = cfg.get("auto_search_query");
        if (q != null && !q.toString().isBlank()) return q.toString();
        // Fallback chain: input map first (direct dep), then any task_md output in
        // the run (transitive). PipelineRunner.gatherInputs only pulls direct deps,
        // so a plan_impl that depends only on create_branch loses access to task_md
        // unless we look it up via the run.
        String fromMap = extractToBeOrTitle(input.get("task_md"));
        if (fromMap != null) return fromMap;
        if (run != null && run.getOutputs() != null) {
            for (com.workflow.core.BlockOutput out : run.getOutputs()) {
                if (!"task_md".equals(out.getBlockId())) continue;
                try {
                    Map<String, Object> data = objectMapper.readValue(
                        out.getOutputJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    String s = extractToBeOrTitle(data);
                    if (s != null) return s;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /** Pulls task_md.to_be (truncated to 1500) or task_md.title from a deserialized map. */
    private static String extractToBeOrTitle(Object taskMd) {
        if (!(taskMd instanceof Map<?, ?> m)) return null;
        Object toBe = m.get("to_be");
        if (toBe != null && !toBe.toString().isBlank()) {
            String s = toBe.toString();
            return s.length() > 1500 ? s.substring(0, 1500) : s;
        }
        Object title = m.get("title");
        if (title != null && !title.toString().isBlank()) return title.toString();
        return null;
    }

    /**
     * Builds a degraded structured output from free-form prose when {@code prose_fallback}
     * is enabled and the model returned no parseable JSON. The shape matches the per-mode
     * defaults the runner enforces downstream, so {@code ${plan_impl.approach}} etc. still
     * resolve. Marks the output with {@code degraded:true} so the UI can flag it.
     *
     * <p>Plan-mode: prose lands in {@code approach}, first non-empty line becomes {@code goal}.
     * Pipeline continues with a text-only plan (no preload, no requirements_coverage).
     *
     * <p>Review-mode: optimistic — sets {@code passed=true, action=continue} and dumps
     * prose into {@code carry_forward}. Real regressions are caught by downstream
     * build/tests/verify_acceptance blocks, not by the prose ramp.
     */
    private Map<String, Object> synthesiseFromProse(String prose, String mode, ToolUseResponse response) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("iterations_used", response.iterationsUsed());
        out.put("total_cost_usd", response.totalCostUsd());
        out.put("degraded", true);
        out.put("degraded_reason", "prose_fallback: model returned free-form text, not JSON");

        String text = prose == null ? "" : prose.trim();
        String firstLine = "";
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#") && !t.startsWith("```")) {
                firstLine = t.length() > 200 ? t.substring(0, 200) : t;
                break;
            }
        }

        if ("review".equals(mode)) {
            out.put("passed", Boolean.TRUE);            // optimistic — downstream will catch real issues
            out.put("issues", "");
            out.put("action", "continue");
            out.put("retry_instruction", "");
            out.put("carry_forward", text);
            out.put("checklist_status", List.of());
            out.put("regressions", List.of());
        } else {
            out.put("goal", firstLine.isEmpty() ? "(prose-fallback plan)" : firstLine);
            out.put("approach", text);
            out.put("files_to_touch", "");
            out.put("definition_of_done", "");
            out.put("tools_to_use", "Read, Edit, Grep, Glob, Bash");
            out.put("requirements_coverage", List.of());
        }
        return out;
    }

    private static double asDouble(Map<String, Object> cfg, String key, double def) {
        Object v = cfg.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString().trim());
    }
}
