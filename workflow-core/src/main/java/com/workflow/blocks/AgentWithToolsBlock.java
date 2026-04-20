package com.workflow.blocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.llm.LlmClient;
import com.workflow.llm.tooluse.ToolDefinition;
import com.workflow.llm.tooluse.ToolUseRequest;
import com.workflow.llm.tooluse.ToolUseResponse;
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

    private static final int DEFAULT_MAX_ITERATIONS = 40;
    private static final double DEFAULT_BUDGET_USD_CAP = 5.0;

    @Autowired private LlmClient llmClient;
    @Autowired private ToolRegistry toolRegistry;
    @Autowired private ObjectMapper objectMapper;
    @Autowired(required = false) private ToolCallAuditRepository auditRepository;

    @Override public String getName() { return "agent_with_tools"; }

    @Override public String getDescription() {
        return "Runs an LLM tool-use loop with a configured set of native tools "
            + "(Read/Write/Edit/Glob/Grep/Bash) scoped to the project's working_dir.";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig blockConfig, PipelineRun run) throws Exception {
        Map<String, Object> cfg = blockConfig.getConfig() != null ? blockConfig.getConfig() : Map.of();

        String workingDirStr = asRequiredString(cfg, "working_dir");
        Path workingDir = Paths.get(workingDirStr).toAbsolutePath();
        if (!workingDir.toFile().isDirectory()) {
            throw new IllegalArgumentException(
                "agent_with_tools: working_dir is not an existing directory: " + workingDir);
        }

        String userTemplate = asRequiredString(cfg, "user_message");
        String userMessage = interpolate(userTemplate, input);

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
        ToolContext toolCtx = new ToolContext(workingDir, bashAllowlist);

        AgentConfig agent = blockConfig.getAgent() != null ? blockConfig.getAgent() : new AgentConfig();
        String model = agent.getModel() != null ? agent.getModel() : "fast";
        String systemPrompt = agent.getSystemPrompt();

        int maxIterations = asInt(cfg, "max_iterations", DEFAULT_MAX_ITERATIONS);
        double budgetUsdCap = asDouble(cfg, "budget_usd_cap", DEFAULT_BUDGET_USD_CAP);

        ToolUseRequest request = ToolUseRequest.builder()
            .model(model)
            .systemPrompt(systemPrompt)
            .userMessage(userMessage)
            .tools(toolDefs)
            .maxTokens(agent.getMaxTokensOrDefault())
            .temperature(agent.getTemperatureOrDefault())
            .maxIterations(maxIterations)
            .budgetUsdCap(budgetUsdCap)
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
}
