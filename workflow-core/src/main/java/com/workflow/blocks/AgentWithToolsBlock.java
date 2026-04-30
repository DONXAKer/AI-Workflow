package com.workflow.blocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.api.RunWebSocketHandler;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.core.expr.StringInterpolator;
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

/**
 * Agentic block: hands the LLM a set of native tools and runs {@link LlmClient#completeWithTools}
 * until the model signals {@code end_turn} or a cap trips.
 *
 * <p>YAML shape:
 * <pre>
 * - id: impl
 *   block: agent_with_tools
 *   agent:
 *     model: fast
 *     systemPrompt: "You are implementing a feature..."
 *     maxTokens: 4096
 *     temperature: 0.2
 *   config:
 *     working_dir: "/abs/path/to/project"   # required
 *     user_message: "Implement: {requirement}"
 *     allowed_tools: [Read, Write, Edit, Glob, Grep, Bash]
 *     bash_allowlist:
 *       - Bash(git *)
 *       - Bash(gradle *)
 *     max_iterations: 40
 *     budget_usd_cap: 5.0
 * </pre>
 *
 * <p>Template substitution in {@code user_message}: {@code {key}} is replaced by the
 * stringified top-level input value. Rich expression interpolation ({@code ${block.field}})
 * lands in M3.
 */
@Component
public class AgentWithToolsBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(AgentWithToolsBlock.class);

    private static final String FALLBACK_PROMPT_HEADER = """
        You are a Senior Software Engineer with deep expertise in reading, navigating, and modifying \
        production codebases. You use tools methodically to understand before you change.

        ## Core Task
        Implement the given task by exploring the codebase, planning your changes, and executing them \
        precisely. Verify your work before finishing.

        ## Best Practices
        1. Read before you write — always read a file before modifying it.
        2. Explore the codebase structure with Glob/Grep before assuming where things live.
        3. Make the smallest change that satisfies the task — do not refactor unrelated code.
        4. Follow existing naming conventions, package structure, and patterns in the codebase.
        5. Write tests alongside the implementation (check where existing tests live first).
        6. Use Bash to verify the build/tests pass before declaring the task done.
        7. If you encounter an unexpected state, investigate with tools — do not guess.""";

    private static final String FALLBACK_PROMPT_FOOTER = """
        ## Quality Bar
        A complete implementation:
        - Passes the build and existing tests
        - Includes at least one test for the new logic
        - Changes only files directly relevant to the task

        NEVER:
        - Write code for a file you have not read in this session
        - Introduce a new dependency without checking if it already exists in the project
        - Leave debug prints, TODOs, or commented-out code in the final output
        - Declare the task done without running a build or test command""";

    private static final int DEFAULT_MAX_ITERATIONS = 40;
    private static final double DEFAULT_BUDGET_USD_CAP = 5.0;

    @Autowired private LlmClient llmClient;
    @Autowired private ToolRegistry toolRegistry;
    @Autowired private ObjectMapper objectMapper;
    @Autowired(required = false) private ToolCallAuditRepository auditRepository;
    @Autowired(required = false) private ProjectRepository projectRepository;
    @Autowired(required = false) private StringInterpolator stringInterpolator;
    @Autowired(required = false) private RunWebSocketHandler wsHandler;

    @Override public String getName() { return "agent_with_tools"; }

    @Override public String getDescription() {
        return "Запускает агентный цикл LLM с набором нативных инструментов "
            + "(Read/Write/Edit/Glob/Grep/Bash), ограниченных рабочей директорией проекта.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
            "Agent (tool-use)",
            "agent",
            List.of(
                new FieldSchema("user_message", "User message", "string", true, null,
                    "Сообщение для модели. Поддерживает ${block.field} и {placeholder}.",
                    Map.of("multiline", true, "monospace", true)),
                FieldSchema.string("working_dir", "Рабочая директория",
                    "Абсолютный путь к рабочей директории; если пусто — workingDir проекта."),
                FieldSchema.toolList("allowed_tools", "Разрешённые инструменты",
                    "Подмножество [Read, Write, Edit, Glob, Grep, Bash]."),
                FieldSchema.stringArray("bash_allowlist", "Bash allowlist",
                    "Шаблоны вида Bash(git *), Bash(gradle *). Пустой — Bash отключён."),
                FieldSchema.number("max_iterations", "Max iterations", DEFAULT_MAX_ITERATIONS,
                    "Максимум раундов агента."),
                FieldSchema.number("budget_usd_cap", "Бюджет USD", DEFAULT_BUDGET_USD_CAP,
                    "Лимит стоимости вызовов LLM на блок.")
            ),
            true,   // hasCustomForm — UI uses dedicated AgentWithToolsForm
            Map.of()
        );
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig blockConfig, PipelineRun run) throws Exception {
        Map<String, Object> cfg = blockConfig.getConfig() != null ? blockConfig.getConfig() : Map.of();

        String workingDirStr = resolveWorkingDir(cfg);
        Path workingDir = Paths.get(workingDirStr).toAbsolutePath();
        if (!workingDir.toFile().isDirectory()) {
            throw new IllegalArgumentException(
                "agent_with_tools: working_dir is not an existing directory: " + workingDir);
        }

        String userTemplate = asRequiredString(cfg, "user_message");
        // Resolve ${block.field} / ${input.key} against prior-block outputs first,
        // then the legacy {key} form against the block's input map. Existing blocks
        // stay compatible; new pipelines can cross-reference freely.
        String expanded = stringInterpolator != null
            ? stringInterpolator.interpolate(userTemplate, run, input)
            : userTemplate;
        String userMessage = appendLoopbackFeedback(interpolate(expanded, input), input);

        List<String> allowedTools = asStringList(cfg, "allowed_tools");
        if (allowedTools.isEmpty()) {
            throw new IllegalArgumentException(
                "agent_with_tools: allowed_tools must list at least one tool");
        }
        List<Tool> tools = toolRegistry.resolve(allowedTools);
        List<ToolDefinition> toolDefs = tools.stream()
            .map(t -> new ToolDefinition(t.name(), t.description(), t.inputSchema(objectMapper)))
            .toList();

        List<String> bashAllowlist = asStringList(cfg, "bash_allowlist");
        ToolContext toolCtx = new ToolContext(workingDir, bashAllowlist, run.getId(), blockConfig.getId());

        AgentConfig agent = blockConfig.getAgent() != null ? blockConfig.getAgent() : new AgentConfig();
        String model = agent.getModel() != null ? agent.getModel() : "fast";
        String systemPrompt = AgentConfig.buildSystemPrompt(
            FALLBACK_PROMPT_HEADER, agent.getSystemPrompt(), FALLBACK_PROMPT_FOOTER);

        int maxIterations = asInt(cfg, "max_iterations", DEFAULT_MAX_ITERATIONS);
        double budgetUsdCap = asDouble(cfg, "budget_usd_cap", DEFAULT_BUDGET_USD_CAP);

        final String blockId = blockConfig.getId();
        final java.util.UUID runId = run.getId();
        ToolUseRequest request = ToolUseRequest.builder()
            .model(model)
            .systemPrompt(systemPrompt)
            .userMessage(userMessage)
            .tools(toolDefs)
            .maxTokens(agent.getMaxTokensOrDefault())
            .temperature(agent.getTemperatureOrDefault())
            .maxIterations(maxIterations)
            .budgetUsdCap(budgetUsdCap)
            .progressCallback(wsHandler != null ? detail ->
                wsHandler.sendBlockProgress(runId, blockId, detail) : null)
            .build();

        DefaultToolExecutor executor = new DefaultToolExecutor(
            toolRegistry, toolCtx, objectMapper, auditRepository);

        log.info("agent_with_tools[{}]: model={} tools={} workingDir={}",
            blockConfig.getId(), model, allowedTools, workingDir);
        ToolUseResponse response = llmClient.completeWithTools(request, executor);

        List<String> toolCallsMade = response.toolCallHistory().stream()
            .map(trace -> trace.call().toolName())
            .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("final_text", response.finalText());
        out.put("stop_reason", response.stopReason().name());
        out.put("iterations_used", response.iterationsUsed());
        out.put("total_input_tokens", response.totalInputTokens());
        out.put("total_output_tokens", response.totalOutputTokens());
        out.put("total_cost_usd", response.totalCostUsd());
        out.put("tool_calls_made", toolCallsMade);
        return out;
    }

    private static String asRequiredString(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("agent_with_tools: config." + key + " is required");
        }
        return v.toString();
    }

    /**
     * Resolves working_dir in priority order: block config → current project's
     * {@link Project#getWorkingDir()}. Fails if neither is set.
     */
    private String resolveWorkingDir(Map<String, Object> cfg) {
        Object inline = cfg.get("working_dir");
        if (inline != null && !inline.toString().isBlank()) {
            return inline.toString();
        }
        if (projectRepository != null) {
            String slug = ProjectContext.get();
            if (slug != null && !slug.isBlank()) {
                Project project = projectRepository.findBySlug(slug).orElse(null);
                if (project != null && project.getWorkingDir() != null
                    && !project.getWorkingDir().isBlank()) {
                    return project.getWorkingDir();
                }
            }
        }
        throw new IllegalArgumentException(
            "agent_with_tools: working_dir not set in block config and current project "
                + "has no workingDir — supply config.working_dir or set Project.workingDir");
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
            "agent_with_tools: config." + key + " must be a list, got " + v.getClass().getSimpleName());
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

    private static String interpolate(String template, Map<String, Object> input) {
        if (template == null) return "";
        Map<String, Object> flat = new HashMap<>(input);
        String out = template;
        for (Map.Entry<String, Object> e : flat.entrySet()) {
            String val = e.getValue() == null ? "" : e.getValue().toString();
            out = out.replace("{" + e.getKey() + "}", val);
        }
        return out;
    }

    /**
     * Auto-append loopback feedback when the block is re-entered from a verify failure.
     * PipelineRunner places the previous iteration's issues under the {@code _loopback}
     * input key (see {@code gatherInputs}). Surfacing it as a post-script on the user
     * message is safer than forcing every YAML author to remember the right
     * {@code ${input._loopback.issues}} syntax — fresh agent sessions still see the
     * diagnosis on retry.
     */
    @SuppressWarnings("unchecked")
    private static String appendLoopbackFeedback(String userMessage, Map<String, Object> input) {
        Object raw = input.get("_loopback");
        if (!(raw instanceof Map<?, ?> loopback)) return userMessage;
        if (loopback.isEmpty()) return userMessage;

        StringBuilder sb = new StringBuilder(userMessage);
        sb.append("\n\n---\n");
        Object iter = loopback.get("iteration");
        sb.append("Previous attempt").append(iter != null ? " (iteration " + iter + ")" : "")
            .append(" did not pass verification. Address the issues below before retrying:\n");
        Object issues = loopback.get("issues");
        if (issues instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) {
                sb.append("- ").append(item).append('\n');
            }
        } else {
            sb.append("(no structured issues recorded)\n");
        }
        for (Map.Entry<?, ?> e : ((Map<?, ?>) loopback).entrySet()) {
            String key = e.getKey().toString();
            if ("iteration".equals(key) || "issues".equals(key)) continue;
            sb.append(key).append(": ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }
}
