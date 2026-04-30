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

    @Value("${workflow.orchestrator.default-model:anthropic/claude-sonnet-4-6}")
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

        StringBuilder userMsg = new StringBuilder("Review the implementation and verify it meets the definition of done.\n\n");

        if (!planOut.isEmpty()) {
            Object goal = planOut.get("goal");
            Object dod  = planOut.get("definition_of_done");
            if (goal != null) userMsg.append("## Goal\n").append(goal).append("\n\n");
            if (dod  != null) userMsg.append("## Definition of Done\n").append(dod).append("\n\n");
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
        if (input.get("_loopback") instanceof Map<?, ?> lb && !lb.isEmpty()) {
            Object prevIssues = lb.get("issues");
            if (prevIssues != null && !prevIssues.toString().isBlank()) {
                userMsg.append("## Issues from previous review attempt\n")
                    .append(prevIssues).append("\n\n");
            }
        }

        // Pre-inject codebase layout so the reviewer doesn't burn iterations re-Glob'ing.
        String tree = ProjectTreeSummary.summarise(workingDir);
        if (!tree.isEmpty()) {
            userMsg.append("## Codebase layout (working_dir: ").append(workingDir).append(")\n")
                .append("```\n").append(tree).append("```\n\n");
        }

        return runLoop(blockConfig, run, workingDir, userMsg.toString(),
            buildReviewSystemPrompt(extra, agentSystemPrompt), REVIEW_TOOLS, REVIEW_BASH,
            asInt(cfg, "max_iterations", DEFAULT_MAX_ITER_REVIEW),
            asDouble(cfg, "budget_usd_cap", DEFAULT_BUDGET_USD), "review");
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
            ? !asString(out, "issues", "").isBlank() || !asString(out, "carry_forward", "").isBlank()
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
            out.putIfAbsent("action", "escalate");
            out.putIfAbsent("retry_instruction", "");
            out.putIfAbsent("carry_forward", "");
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

    private static String buildReviewSystemPrompt(String extra, String agentSystemPrompt) {
        StringBuilder sb = new StringBuilder();
        if (!agentSystemPrompt.isBlank()) {
            sb.append(agentSystemPrompt).append("\n\n");
        }
        sb.append("You are a code reviewer. Verify that the implementation matches the definition of done.\n\n");
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
        sb.append("""

MANDATORY CHECKS — verify ALL of the following regardless of what the definition_of_done says:
1. TESTS: At least one test covers the new or changed logic. Check test files with Read/Grep.
   If build/test context was provided above, verify the test run result is green (no failures).
2. API DOCS: If any REST endpoints were added or changed, OpenAPI/Swagger annotations are present and accurate.
3. DOCS: If external behavior or architecture changed, README or CLAUDE.md reflects the change.
4. BUILD: No compile errors. If build context was provided above, verify the build succeeded.

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
- "passed": true only if ALL mandatory checks AND all definition-of-done items are met
- "action": "continue" (passed), "retry" (fixable code issues), or "escalate" (architectural/blocking problem)
- Set retry_instruction to a specific, actionable fix description when action=retry
- Use "escalate" when the problem cannot be fixed by the implementor alone (wrong architecture, missing requirements)
- Your FINAL message must be ONLY the ```json block. No text before or after it.
""");
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
