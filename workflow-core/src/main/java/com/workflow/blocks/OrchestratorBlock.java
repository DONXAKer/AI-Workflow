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
import com.workflow.tools.Tool;
import com.workflow.tools.ToolCallAuditRepository;
import com.workflow.tools.ToolContext;
import com.workflow.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final String DEFAULT_MODEL        = "anthropic/claude-sonnet-4-5";

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
        return "Supervisor agent: plan mode produces a structured implementation plan; "
            + "review mode verifies the result against the plan's definition_of_done.";
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

        if ("review".equals(mode)) {
            return runReview(cfg, input, blockConfig, run, workingDir, combinedExtra);
        }
        return runPlan(cfg, input, blockConfig, run, workingDir, combinedExtra);
    }

    // ── Plan mode ──────────────────────────────────────────────────────────────

    private Map<String, Object> runPlan(Map<String, Object> cfg, Map<String, Object> input,
            BlockConfig blockConfig, PipelineRun run, Path workingDir, String extra) throws Exception {

        StringBuilder userMsg = new StringBuilder("Analyze the task and produce an implementation plan.\n\n");

        // Inject context blocks
        for (String ctxId : asStringList(cfg, "context_blocks")) {
            Object blockOut = input.get(ctxId);
            if (blockOut instanceof Map<?, ?> bMap) {
                userMsg.append("## Context from block: ").append(ctxId).append('\n');
                bMap.forEach((k, v) -> userMsg.append(k).append(": ").append(v).append('\n'));
                userMsg.append('\n');
            }
        }

        return runLoop(blockConfig, run, workingDir, userMsg.toString(),
            buildPlanSystemPrompt(extra), PLAN_TOOLS, PLAN_BASH,
            asInt(cfg, "max_iterations", DEFAULT_MAX_ITER_PLAN),
            asDouble(cfg, "budget_usd_cap", DEFAULT_BUDGET_USD), "plan");
    }

    // ── Review mode ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> runReview(Map<String, Object> cfg, Map<String, Object> input,
            BlockConfig blockConfig, PipelineRun run, Path workingDir, String extra) throws Exception {

        String planBlockId = asString(cfg, "plan_block", "");
        Map<String, Object> planOut = planBlockId.isBlank() ? Map.of()
            : input.get(planBlockId) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

        StringBuilder userMsg = new StringBuilder("Review the implementation and verify it meets the definition of done.\n\n");

        if (!planOut.isEmpty()) {
            Object goal = planOut.get("goal");
            Object dod  = planOut.get("definition_of_done");
            if (goal != null) userMsg.append("## Goal\n").append(goal).append("\n\n");
            if (dod  != null) userMsg.append("## Definition of Done\n").append(dod).append("\n\n");
        }

        // Append loopback feedback from previous review attempt
        if (input.get("_loopback") instanceof Map<?, ?> lb && !lb.isEmpty()) {
            Object prevIssues = lb.get("issues");
            if (prevIssues != null && !prevIssues.toString().isBlank()) {
                userMsg.append("## Issues from previous review attempt\n")
                    .append(prevIssues).append("\n\n");
            }
        }

        return runLoop(blockConfig, run, workingDir, userMsg.toString(),
            buildReviewSystemPrompt(extra), REVIEW_TOOLS, REVIEW_BASH,
            asInt(cfg, "max_iterations", DEFAULT_MAX_ITER_REVIEW),
            asDouble(cfg, "budget_usd_cap", DEFAULT_BUDGET_USD), "review");
    }

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
        String model = agent.getModel() != null ? agent.getModel() : resolveProjectModel();

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

        // Rescue: model hit maxIterations mid-tool-call, finalText is preamble not JSON
        if (parsed.containsKey("raw_text") && response.stopReason() == StopReason.MAX_ITERATIONS) {
            log.info("orchestrator[{}]: no JSON after maxIterations, attempting rescue completion", blockId);
            parsed = rescueJson(response.finalText(), mode, model);
        }

        Map<String, Object> out = new LinkedHashMap<>(parsed);
        out.put("mode", mode);
        out.put("iterations_used", response.iterationsUsed());
        out.put("total_cost_usd", response.totalCostUsd());

        // Ensure required fields exist so pipeline interpolation never throws
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
        }
        return out;
    }

    // ── System prompts ─────────────────────────────────────────────────────────

    private static String buildPlanSystemPrompt(String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a technical architect. Explore the codebase and produce a detailed implementation plan.\n\n");
        sb.append("Use Read, Grep, and Glob to explore. Do NOT write or modify any files.\n");
        if (!extra.isBlank()) {
            sb.append("\n## Project context\n").append(extra).append("\n");
        }
        sb.append("""

When you have enough information, respond with a JSON object inside a ```json block:
```json
{
  "goal": "one-sentence description of what needs to be implemented",
  "files_to_touch": "newline-separated list of files to modify or create",
  "approach": "step-by-step technical approach",
  "definition_of_done": "newline-separated verifiable completion checklist",
  "tools_to_use": "comma-separated tool names the implementor should use"
}
```
Respond with the JSON only. No text after the closing ```.
""");
        return sb.toString();
    }

    private static String buildReviewSystemPrompt(String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a code reviewer. Verify that the implementation matches the definition of done.\n\n");
        sb.append("Use Read, Grep, Glob, and git diff (Bash) to examine the actual changes. Do NOT modify files.\n");
        sb.append("CRITICAL: After gathering evidence, you MUST end your response with ONLY a JSON block.\n");
        sb.append("Do NOT write any text after the closing ``` of the JSON block.\n");
        sb.append("Do NOT start your final response with 'Let me', 'I found', or any explanation text.\n");
        if (!extra.isBlank()) {
            sb.append("\n## Project context\n").append(extra).append("\n");
        }
        sb.append("""

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
- "passed": true if all definition-of-done items are met; false otherwise
- "action": "continue" (passed), "retry" (fixable issues), or "escalate" (blocking problem)
- Set retry_instruction to a specific fix description when action=retry
- Your FINAL message must be ONLY the ```json block. No text before or after it.
""");
        return sb.toString();
    }

    // ── Rescue completion ─────────────────────────────────────────────────────

    private Map<String, Object> rescueJson(String rawText, String mode, String model) {
        String schema = "review".equals(mode)
            ? "{\"passed\": <boolean>, \"issues\": \"<string>\", \"action\": \"<continue|retry|escalate>\","
                + " \"retry_instruction\": \"<string>\", \"carry_forward\": \"<string>\"}"
            : "{\"goal\": \"<string>\", \"files_to_touch\": \"<string>\", \"approach\": \"<string>\","
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
        String json = text;

        // Try ```json ... ``` block first
        int fenceStart = text.indexOf("```json");
        if (fenceStart >= 0) {
            int lineStart = text.indexOf('\n', fenceStart);
            if (lineStart >= 0) {
                int fenceEnd = text.indexOf("```", lineStart + 1);
                if (fenceEnd > lineStart) {
                    json = text.substring(lineStart + 1, fenceEnd).trim();
                }
            }
        } else {
            // Fall back to first { ... } span
            int start = text.indexOf('{');
            int end   = text.lastIndexOf('}');
            if (start >= 0 && end > start) json = text.substring(start, end + 1).trim();
        }

        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("orchestrator: could not parse JSON from response ({}); returning raw_text", e.getMessage());
            return Map.of("raw_text", text);
        }
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

    private String resolveProjectModel() {
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
        return DEFAULT_MODEL;
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
