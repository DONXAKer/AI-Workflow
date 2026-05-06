package com.workflow.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.api.RunWebSocketHandler;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.llm.LlmClient;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Smart-tier verifier that walks the {@code acceptance_checklist} from an analysis
 * block item-by-item, using read-only native tools (Read/Grep/Glob/Bash) to find
 * evidence in the repo. Produces a structural {@code verification_results} list
 * plus the legacy {@code passed/issues/iteration/recommendation} fields so existing
 * loopback infra keeps working.
 *
 * <p>YAML shape:
 * <pre>
 * - id: verify_impl
 *   block: agent_verify
 *   depends_on: [analysis, codegen]
 *   agent:
 *     tier: smart
 *   config:
 *     subject: analysis           # block id whose acceptance_checklist drives verification
 *     working_dir: "/abs/path"    # optional — falls back to Project.workingDir
 *     allowed_tools: [Read, Grep, Glob, Bash]   # write/edit deliberately omitted
 *     bash_allowlist:
 *       - "Bash(gradle test*)"
 *       - "Bash(npm test*)"
 *     pass_threshold: critical_and_important   # critical_only | critical_and_important | all
 *     max_iterations: 12
 *     budget_usd_cap: 1.5
 *   on_fail:
 *     action: loopback
 *     target: codegen
 *     max_iterations: 2
 *     inject_context:
 *       failed_items: "$.verify_impl.failed_items"
 *       passed_items: "$.verify_impl.passed_items"
 * </pre>
 */
@Component
public class AgentVerifyBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(AgentVerifyBlock.class);

    private static final String SYSTEM_PROMPT = """
        You are a Staff QA Engineer running a methodical, evidence-based acceptance review. \
        For every item in the acceptance checklist you are given, you must locate concrete \
        evidence in the repository proving that the item is satisfied — or prove it is NOT.

        ## Core Rules
        1. NEVER guess. Always find evidence by reading files, grepping, listing, or running tests.
        2. PASS requires concrete evidence: a file path + content quote, a passing test name, a \
           grep hit pattern, or a successful Bash command output. Vague "looks fine" is FAIL.
        3. FAIL must include the specific evidence-of-absence: which file you expected, what was \
           missing, what test failed and why.
        4. Evidence must be quotable — name the exact file:line or command output you observed.
        5. You are READ-ONLY. Do not modify, write, or delete anything. Even if you spot a bug, \
           your job is to report — not to fix.
        6. Use Bash sparingly: only when a structural check (file exists, contains pattern) is \
           insufficient — e.g. running tests to confirm they pass.

        ## Output Contract
        When done, respond with ONE JSON object on the LAST line of your response. No preamble \
        text on that line. The JSON object schema:
        {
          "verification_results": [
            {"item_id": "<id from checklist>", "status": "PASS|FAIL", "evidence": "<concrete proof>"}
          ],
          "recommendation": "<short note for downstream codegen if any FAILs>"
        }

        Every checklist item MUST appear exactly once in verification_results — missing item ids \
        will be treated as FAIL with synthetic evidence "verifier did not check this item".
        """;

    private static final int DEFAULT_MAX_ITERATIONS = 12;
    private static final double DEFAULT_BUDGET_USD_CAP = 1.5;
    private static final String DEFAULT_PASS_THRESHOLD = "critical_and_important";
    private static final List<String> DEFAULT_TOOLS = List.of("Read", "Grep", "Glob", "Bash");
    private static final Set<String> WRITE_TOOLS = Set.of("Write", "Edit");

    @Autowired private LlmClient llmClient;
    @Autowired private ToolRegistry toolRegistry;
    @Autowired private ObjectMapper objectMapper;
    @Autowired(required = false) private ToolCallAuditRepository auditRepository;
    @Autowired(required = false) private ProjectRepository projectRepository;
    @Autowired(required = false) private RunWebSocketHandler wsHandler;

    @Override public String getName() { return "agent_verify"; }

    @Override public String getDescription() {
        return "Smart-tier верификатор: проходит acceptance_checklist пункт-за-пунктом, "
            + "ищет evidence в репозитории через Read/Grep/Bash, выдаёт PASS/FAIL/evidence по каждому пункту.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
            "Agent Verify",
            "verify",
            Phase.VERIFY,
            List.of(
                FieldSchema.string("subject", "Subject block",
                    "ID блока чей acceptance_checklist проверяется (обычно analysis)."),
                FieldSchema.string("working_dir", "Рабочая директория",
                    "Абсолютный путь; пусто → workingDir проекта."),
                FieldSchema.toolList("allowed_tools", "Разрешённые инструменты",
                    "Подмножество read-only tools. Write/Edit отбрасываются автоматически."),
                FieldSchema.stringArray("bash_allowlist", "Bash allowlist",
                    "Шаблоны Bash(gradle test*), Bash(npm test*) и т.п."),
                FieldSchema.string("pass_threshold", "Пороговая severity",
                    "critical_only | critical_and_important (default) | all."),
                FieldSchema.number("max_iterations", "Max iterations", DEFAULT_MAX_ITERATIONS,
                    "Максимум раундов агента."),
                FieldSchema.number("budget_usd_cap", "Бюджет USD", DEFAULT_BUDGET_USD_CAP,
                    "Лимит стоимости.")
            ),
            true,
            Map.of()
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig blockConfig, PipelineRun run) throws Exception {
        Map<String, Object> cfg = blockConfig.getConfig() != null ? blockConfig.getConfig() : Map.of();

        String subjectId = asRequired(cfg, "subject");
        Object subjectOutputObj = input.get(subjectId);
        if (!(subjectOutputObj instanceof Map<?, ?> subjectMap)) {
            throw new IllegalStateException(
                "agent_verify: subject block '" + subjectId + "' has no output in input map. "
                    + "Add depends_on: [" + subjectId + "] to wire it.");
        }

        Object checklistRaw = subjectMap.get("acceptance_checklist");
        List<Map<String, Object>> checklist = coerceChecklist(checklistRaw);
        if (checklist.isEmpty()) {
            log.warn("agent_verify '{}': subject '{}' has empty acceptance_checklist — passing trivially",
                blockConfig.getId(), subjectId);
            return buildResult(true, List.of(), List.of(), List.of(), 0, "");
        }

        Path workingDir = resolveWorkingDir(cfg);
        if (!workingDir.toFile().isDirectory()) {
            throw new IllegalArgumentException(
                "agent_verify: working_dir is not a directory: " + workingDir);
        }

        List<String> requested = asStringList(cfg, "allowed_tools");
        if (requested.isEmpty()) requested = DEFAULT_TOOLS;
        // Verify is read-only: drop write/edit even if YAML asked for them.
        List<String> safeTools = requested.stream().filter(t -> !WRITE_TOOLS.contains(t)).toList();
        if (safeTools.isEmpty()) {
            throw new IllegalArgumentException(
                "agent_verify: allowed_tools must contain at least one read-only tool");
        }
        List<Tool> tools = toolRegistry.resolve(safeTools);
        List<ToolDefinition> toolDefs = tools.stream()
            .map(t -> new ToolDefinition(t.name(), t.description(), t.inputSchema(objectMapper)))
            .toList();

        List<String> bashAllowlist = asStringList(cfg, "bash_allowlist");
        ToolContext toolCtx = new ToolContext(workingDir, bashAllowlist, run.getId(), blockConfig.getId());

        AgentConfig agent = blockConfig.getAgent() != null ? blockConfig.getAgent() : new AgentConfig();
        String model = agent.getEffectiveModel() != null ? agent.getEffectiveModel() : "smart";
        int maxIterations = asInt(cfg, "max_iterations", DEFAULT_MAX_ITERATIONS);
        double budgetUsdCap = asDouble(cfg, "budget_usd_cap", DEFAULT_BUDGET_USD_CAP);

        String userMessage = buildUserMessage(checklist, workingDir, input);

        final String blockId = blockConfig.getId();
        final java.util.UUID runId = run.getId();
        ToolUseRequest request = ToolUseRequest.builder()
            .model(model)
            .systemPrompt(SYSTEM_PROMPT)
            .userMessage(userMessage)
            .tools(toolDefs)
            .maxTokens(agent.getMaxTokensOrDefault())
            .temperature(0.2)  // verifier should be deterministic
            .maxIterations(maxIterations)
            .budgetUsdCap(budgetUsdCap)
            .progressCallback(wsHandler != null ? detail ->
                wsHandler.sendBlockProgress(runId, blockId, detail) : null)
            .build();

        DefaultToolExecutor executor = new DefaultToolExecutor(
            toolRegistry, toolCtx, objectMapper, auditRepository);

        log.info("agent_verify[{}]: model={} items={} workingDir={}",
            blockId, model, checklist.size(), workingDir);
        ToolUseResponse response = llmClient.completeWithTools(request, executor);

        Map<String, String> reportedStatus = new HashMap<>();
        Map<String, String> reportedEvidence = new HashMap<>();
        String recommendation = "";
        try {
            Map<String, Object> parsed = parseFinalJson(response.finalText());
            Object resultsRaw = parsed.get("verification_results");
            if (resultsRaw instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Object idObj = m.get("item_id");
                    if (!(idObj instanceof String id) || id.isBlank()) continue;
                    Object status = m.get("status");
                    Object evidence = m.get("evidence");
                    reportedStatus.put(id, status != null ? status.toString() : "FAIL");
                    reportedEvidence.put(id, evidence != null ? evidence.toString() : "");
                }
            }
            Object rec = parsed.get("recommendation");
            if (rec instanceof String s) recommendation = s;
        } catch (Exception e) {
            log.warn("agent_verify[{}]: failed to parse final JSON ({}). Raw text:\n{}",
                blockId, e.getMessage(), response.finalText());
        }

        // Build verification_results in checklist order, synthesizing FAIL for items
        // the verifier silently dropped.
        List<Map<String, Object>> verificationResults = new ArrayList<>();
        List<Map<String, Object>> failedItems = new ArrayList<>();
        List<Map<String, Object>> passedItems = new ArrayList<>();
        for (Map<String, Object> item : checklist) {
            String id = String.valueOf(item.get("id"));
            String priority = stringOrDefault(item.get("priority"), "important");
            String text = String.valueOf(item.getOrDefault("text", ""));
            String status = reportedStatus.getOrDefault(id, "FAIL");
            String evidence = reportedEvidence.getOrDefault(id,
                "verifier did not check this item");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("item_id", id);
            entry.put("text", text);
            entry.put("priority", priority);
            entry.put("status", status);
            entry.put("evidence", evidence);
            verificationResults.add(entry);
            if ("PASS".equalsIgnoreCase(status)) passedItems.add(entry);
            else failedItems.add(entry);
        }

        List<Map<String, Object>> regressionFlags = computeRegressionFlags(input, verificationResults);

        String passThreshold = stringOrDefault(cfg.get("pass_threshold"), DEFAULT_PASS_THRESHOLD);
        boolean passed = evaluatePassThreshold(verificationResults, passThreshold);

        // Iteration count for loopback infrastructure.
        int iteration = 0;
        if (blockConfig.getOnFailure() != null && blockConfig.getOnFailure().getTarget() != null) {
            String loopKey = "loopback:" + blockId + ":" + blockConfig.getOnFailure().getTarget();
            iteration = run.getLoopIterations().getOrDefault(loopKey, 0);
        }

        log.info("agent_verify[{}]: passed={} threshold={} pass={}/{} fail={}/{} regressions={}",
            blockId, passed, passThreshold, passedItems.size(), checklist.size(),
            failedItems.size(), checklist.size(), regressionFlags.size());

        Map<String, Object> result = buildResult(passed, verificationResults, failedItems, passedItems,
            iteration, recommendation);
        result.put("regression_flags", regressionFlags);
        result.put("subject_block", subjectId);
        result.put("pass_threshold", passThreshold);
        result.put("stop_reason", response.stopReason().name());
        result.put("iterations_used", response.iterationsUsed());
        result.put("total_cost_usd", response.totalCostUsd());
        return result;
    }

    private Map<String, Object> buildResult(boolean passed,
                                            List<Map<String, Object>> verificationResults,
                                            List<Map<String, Object>> failedItems,
                                            List<Map<String, Object>> passedItems,
                                            int iteration,
                                            String recommendation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", passed);
        result.put("verification_results", verificationResults);
        result.put("failed_items", failedItems);
        result.put("passed_items", passedItems);
        // Legacy fields for loopback infrastructure compatibility.
        List<String> issues = new ArrayList<>();
        for (Map<String, Object> f : failedItems) {
            issues.add(String.format("[%s] %s — %s",
                f.get("item_id"), f.get("text"), f.get("evidence")));
        }
        result.put("issues", issues);
        result.put("iteration", iteration);
        result.put("recommendation", recommendation);
        return result;
    }

    private boolean evaluatePassThreshold(List<Map<String, Object>> results, String threshold) {
        for (Map<String, Object> r : results) {
            if (!"FAIL".equalsIgnoreCase(String.valueOf(r.get("status")))) continue;
            String priority = String.valueOf(r.get("priority"));
            switch (threshold) {
                case "critical_only":
                    if ("critical".equalsIgnoreCase(priority)) return false;
                    break;
                case "all":
                    return false;
                case "critical_and_important":
                default:
                    if ("critical".equalsIgnoreCase(priority)
                        || "important".equalsIgnoreCase(priority)) return false;
            }
        }
        return true;
    }

    /**
     * If the loopback inject_context provides {@code _previous_verification_results}
     * (or the upstream block's prior output is in input), flag items that flipped
     * PASS → FAIL between iterations.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> computeRegressionFlags(Map<String, Object> input,
                                                              List<Map<String, Object>> current) {
        Object prevRaw = input.get("_previous_verification_results");
        if (!(prevRaw instanceof List<?> prevList)) return List.of();
        Map<String, String> prevStatus = new HashMap<>();
        for (Object o : prevList) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object id = m.get("item_id");
            Object status = m.get("status");
            if (id != null && status != null) {
                prevStatus.put(id.toString(), status.toString());
            }
        }
        List<Map<String, Object>> regressions = new ArrayList<>();
        for (Map<String, Object> r : current) {
            String id = String.valueOf(r.get("item_id"));
            String now = String.valueOf(r.get("status"));
            String before = prevStatus.get(id);
            if ("PASS".equalsIgnoreCase(before) && "FAIL".equalsIgnoreCase(now)) {
                Map<String, Object> reg = new LinkedHashMap<>(r);
                reg.put("regression", true);
                regressions.add(reg);
            }
        }
        return regressions;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> coerceChecklist(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    private String buildUserMessage(List<Map<String, Object>> checklist, Path workingDir,
                                     Map<String, Object> input) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Acceptance verification\n\n");
        sb.append("Working directory: `").append(workingDir).append("`\n\n");
        sb.append("## Checklist\n\n```json\n");
        sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(checklist));
        sb.append("\n```\n\n");

        Object loopback = input.get("_loopback");
        if (loopback instanceof Map<?, ?> lb) {
            Object iter = lb.get("iteration");
            sb.append("## Loopback context\n\n");
            sb.append("This is verification iteration **").append(iter != null ? iter : "?")
                .append("** for this run. The implementer received feedback from a prior FAIL ")
                .append("and tried again. Re-check every item from scratch — do not assume ")
                .append("previously-PASSed items are still PASS, the implementer may have ")
                .append("regressed working code.\n\n");
        }

        sb.append("Walk every item in the checklist. For each one, locate concrete evidence ")
            .append("in the repo above using Read/Grep/Glob/Bash. Report PASS or FAIL with the ")
            .append("specific evidence (file:line, command output, test name).\n\n")
            .append("Finish your response with the JSON object specified in the system prompt ")
            .append("on the LAST line.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFinalJson(String text) throws Exception {
        if (text == null) throw new IllegalStateException("empty agent response");
        String trimmed = text.strip();
        // Try last-line JSON first (per system prompt contract).
        int lastBrace = trimmed.lastIndexOf('{');
        int lastClose = trimmed.lastIndexOf('}');
        if (lastBrace >= 0 && lastClose > lastBrace) {
            String slice = trimmed.substring(lastBrace, lastClose + 1);
            try {
                return objectMapper.readValue(slice, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignore) { /* fall through */ }
        }
        // Fallback: whole text.
        return objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {});
    }

    private Path resolveWorkingDir(Map<String, Object> cfg) {
        Object inline = cfg.get("working_dir");
        if (inline != null && !inline.toString().isBlank()) {
            return Paths.get(inline.toString()).toAbsolutePath();
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
            "agent_verify: working_dir not set in block config and current project has no workingDir");
    }

    private static String asRequired(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("agent_verify: config." + key + " is required");
        }
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) if (o != null) out.add(o.toString());
            return out;
        }
        throw new IllegalArgumentException(
            "agent_verify: config." + key + " must be a list");
    }

    private static int asInt(Map<String, Object> cfg, String key, int defaultValue) {
        Object v = cfg.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) { }
        }
        return defaultValue;
    }

    private static double asDouble(Map<String, Object> cfg, String key, double defaultValue) {
        Object v = cfg.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignore) { }
        }
        return defaultValue;
    }

    private static String stringOrDefault(Object v, String def) {
        if (v == null) return def;
        String s = v.toString();
        return s.isBlank() ? def : s;
    }
}
