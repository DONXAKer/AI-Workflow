package com.workflow.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.llm.tooluse.StopReason;
import com.workflow.llm.tooluse.ToolCall;
import com.workflow.llm.tooluse.ToolDefinition;
import com.workflow.llm.tooluse.ToolExecutor;
import com.workflow.llm.tooluse.ToolResult;
import com.workflow.llm.tooluse.ToolUseRequest;
import com.workflow.llm.tooluse.ToolUseResponse;
import com.workflow.tools.ToolCallIteration;
import com.workflow.model.IntegrationConfig;
import com.workflow.model.IntegrationConfigRepository;
import com.workflow.model.IntegrationType;
import com.workflow.skills.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final IntegrationConfigRepository integrationConfigRepository;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    @Autowired
    public LlmClient(IntegrationConfigRepository integrationConfigRepository,
                     ObjectMapper objectMapper,
                     WebClient.Builder webClientBuilder) {
        this.integrationConfigRepository = integrationConfigRepository;
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
    }

    /**
     * Calls the OpenRouter API to complete a prompt.
     *
     * @param model       Model name (e.g. "claude-sonnet-4-6" or "anthropic/claude-3-5-sonnet")
     * @param system      System prompt
     * @param user        User prompt
     * @param maxTokens   Max tokens in response
     * @param temperature Sampling temperature
     * @return The assistant's text response, stripped of markdown code fences
     */
    @Autowired(required = false)
    private ModelPresetResolver presetResolver;

    @Autowired(required = false)
    private LlmCallRepository llmCallRepository;

    public String complete(String model, String system, String user, int maxTokens, double temperature) {
        String resolvedModel = resolveModel(model);
        WebClient client = buildOpenRouterClient();

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", resolvedModel);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);

        ArrayNode messages = requestBody.putArray("messages");
        if (system != null && !system.isBlank()) {
            ObjectNode systemMsg = messages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", system);
        }
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", user);

        log.info("Calling OpenRouter model: {} (maxTokens={}, temperature={})", resolvedModel, maxTokens, temperature);

        long startedAt = System.currentTimeMillis();
        try {
            String responseBody = client.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            if (responseBody == null) {
                throw new RuntimeException("Empty response from OpenRouter");
            }

            JsonNode responseJson = objectMapper.readTree(responseBody);
            String content = responseJson
                .path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText();

            if (content == null || content.isBlank()) {
                log.warn("OpenRouter returned empty content. Full response: {}", responseBody);
                throw new RuntimeException("Empty content in OpenRouter response");
            }

            recordUsage(responseJson, resolvedModel, (int) (System.currentTimeMillis() - startedAt));

            return stripCodeFences(content.strip());

        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage(), e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private String resolveModel(String model) {
        String resolved = presetResolver != null ? presetResolver.resolve(model) : model;
        if (resolved != null && !resolved.contains("/")) {
            resolved = "anthropic/" + resolved;
        }
        return resolved;
    }

    private WebClient buildOpenRouterClient() {
        String baseUrl = "https://openrouter.ai/api/v1";
        String apiKey = null;

        Optional<IntegrationConfig> openRouterConfig =
            integrationConfigRepository.findByTypeAndIsDefaultTrue(IntegrationType.OPENROUTER);

        if (openRouterConfig.isPresent()) {
            IntegrationConfig cfg = openRouterConfig.get();
            if (cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()) {
                baseUrl = cfg.getBaseUrl();
            }
            apiKey = cfg.getToken();
        }

        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("OPENROUTER_API_KEY");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("No OpenRouter API key configured. Set OPENROUTER_API_KEY env var or configure via /api/integrations.");
        }

        return webClientBuilder
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .defaultHeader("HTTP-Referer", "https://workflow.app")
            .defaultHeader("X-Title", "Workflow Pipeline")
            .build();
    }

    private void recordUsage(JsonNode responseJson, String model, int durationMs) {
        if (llmCallRepository == null) return;
        try {
            JsonNode usage = responseJson.path("usage");
            int tokensIn = usage.path("prompt_tokens").asInt(0);
            int tokensOut = usage.path("completion_tokens").asInt(0);
            // OpenRouter returns cost in its "usage" block when the user is on OpenRouter credits;
            // fall back to 0 otherwise — real rate cards can be applied server-side later.
            double cost = usage.path("cost").asDouble(0.0);

            LlmCall call = new LlmCall();
            call.setTimestamp(java.time.Instant.now());
            call.setModel(model);
            call.setTokensIn(tokensIn);
            call.setTokensOut(tokensOut);
            call.setCostUsd(cost);
            call.setDurationMs(durationMs);
            call.setProjectSlug(com.workflow.project.ProjectContext.get());
            LlmCallContext.current().ifPresent(ctx -> {
                call.setRunId(ctx.runId());
                call.setBlockId(ctx.blockId());
            });
            llmCallRepository.save(call);
        } catch (Exception e) {
            log.debug("LlmCall persist failed: {}", e.getMessage());
        }
    }

    /**
     * Runs an agentic tool-use loop against OpenRouter using the OpenAI-compatible
     * chat/completions tools format.
     *
     * <p>Each iteration: POST messages+tools → parse response → if {@code finish_reason ==
     * tool_calls}, dispatch each requested {@link com.workflow.llm.tooluse.ToolCall} to the
     * {@code executor}, append the assistant turn and per-call {@code role:"tool"} results
     * to the message history, and loop. Stops when the model finishes with {@code stop}
     * ({@link StopReason#END_TURN}), exhausts {@code maxIterations}, exceeds
     * {@code budgetUsdCap}, or the provider reports {@code length}
     * ({@link StopReason#MAX_TOKENS}).
     *
     * <p>Every API iteration persists its own {@link LlmCall} row tagged with the
     * iteration index and invoked tool names, so per-step audit is preserved.
     *
     * <p>Executor exceptions are caught and surfaced to the LLM as error tool results —
     * callers do not need to wrap their executor logic.
     */
    public ToolUseResponse completeWithTools(ToolUseRequest request, ToolExecutor executor) {
        if (request == null) throw new IllegalArgumentException("request required");
        if (executor == null) throw new IllegalArgumentException("executor required");

        String resolvedModel = resolveModel(request.model());
        WebClient client = buildOpenRouterClient();

        ArrayNode messages = objectMapper.createArrayNode();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ObjectNode systemMsg = messages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", request.systemPrompt());
        }
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", request.userMessage());

        ArrayNode toolsJson = buildToolsJson(request.tools());

        List<ToolUseResponse.ToolCallTrace> history = new ArrayList<>();
        int iterations = 0;
        int totalTokensIn = 0;
        int totalTokensOut = 0;
        double totalCostUsd = 0.0;
        String finalText = "";

        log.info("Starting tool-use loop: model={} tools={} maxIterations={} budget=${}",
            resolvedModel, toolsJson.size(), request.maxIterations(), request.budgetUsdCap());

        while (iterations < request.maxIterations()) {
            iterations++;

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", resolvedModel);
            body.put("max_tokens", request.maxTokens());
            body.put("temperature", request.temperature());
            body.set("messages", messages);
            if (toolsJson.size() > 0) {
                body.set("tools", toolsJson);
                body.put("tool_choice", "auto");
            }

            long startedAt = System.currentTimeMillis();
            JsonNode responseJson;
            try {
                String responseBody = client.post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
                if (responseBody == null) {
                    throw new RuntimeException("Empty response from OpenRouter");
                }
                responseJson = objectMapper.readTree(responseBody);
            } catch (Exception e) {
                log.error("Tool-use iteration {} failed: {}", iterations, e.getMessage(), e);
                throw new RuntimeException("completeWithTools iteration " + iterations + " failed: " + e.getMessage(), e);
            }

            JsonNode choice = responseJson.path("choices").path(0);
            JsonNode messageNode = choice.path("message");
            String finishReason = choice.path("finish_reason").asText("");
            String content = messageNode.path("content").isNull()
                ? "" : messageNode.path("content").asText("");
            JsonNode toolCalls = messageNode.path("tool_calls");

            JsonNode usage = responseJson.path("usage");
            int tokensIn = usage.path("prompt_tokens").asInt(0);
            int tokensOut = usage.path("completion_tokens").asInt(0);
            double cost = usage.path("cost").asDouble(0.0);
            totalTokensIn += tokensIn;
            totalTokensOut += tokensOut;
            totalCostUsd += cost;

            List<String> toolNames = new ArrayList<>();
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    toolNames.add(tc.path("function").path("name").asText(""));
                }
            }
            recordToolUseUsage(resolvedModel, iterations,
                (int) (System.currentTimeMillis() - startedAt),
                tokensIn, tokensOut, cost, toolNames);

            if (!content.isBlank()) {
                finalText = content;
            }

            boolean hasToolCalls = "tool_calls".equals(finishReason)
                && toolCalls.isArray() && toolCalls.size() > 0;

            if (!hasToolCalls) {
                StopReason stop = "length".equals(finishReason)
                    ? StopReason.MAX_TOKENS : StopReason.END_TURN;
                log.info("Tool-use loop finished: iterations={} stop={} tokens={}/{} cost=${}",
                    iterations, stop, totalTokensIn, totalTokensOut, totalCostUsd);
                return new ToolUseResponse(stripCodeFences(finalText.strip()), stop, history,
                    iterations, totalTokensIn, totalTokensOut, totalCostUsd);
            }

            messages.add(messageNode.deepCopy());

            for (JsonNode tc : toolCalls) {
                String callId = tc.path("id").asText("");
                String toolName = tc.path("function").path("name").asText("");
                String argsStr = tc.path("function").path("arguments").asText("{}");
                JsonNode input;
                try {
                    input = objectMapper.readTree(argsStr == null || argsStr.isBlank() ? "{}" : argsStr);
                } catch (Exception e) {
                    input = objectMapper.createObjectNode();
                }
                ToolCall call = new ToolCall(callId, toolName, input);

                ToolResult result;
                ToolCallIteration.set(iterations);
                try {
                    result = executor.execute(call);
                    if (result == null) {
                        result = ToolResult.error(callId, "executor returned null");
                    }
                } catch (Exception e) {
                    log.warn("Tool executor threw for {}: {}", toolName, e.getMessage());
                    result = ToolResult.error(callId, "executor_failure: " + e.getMessage());
                } finally {
                    ToolCallIteration.clear();
                }
                history.add(new ToolUseResponse.ToolCallTrace(iterations, call, result));

                ObjectNode toolMsg = messages.addObject();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", callId);
                toolMsg.put("content", result.content() == null ? "" : result.content());
            }

            if (totalCostUsd >= request.budgetUsdCap()) {
                log.warn("Tool-use budget exceeded: spent=${} cap=${}",
                    totalCostUsd, request.budgetUsdCap());
                return new ToolUseResponse(stripCodeFences(finalText.strip()),
                    StopReason.BUDGET_EXCEEDED, history,
                    iterations, totalTokensIn, totalTokensOut, totalCostUsd);
            }
        }

        log.warn("Tool-use loop hit maxIterations={}", request.maxIterations());
        return new ToolUseResponse(stripCodeFences(finalText.strip()),
            StopReason.MAX_ITERATIONS, history,
            iterations, totalTokensIn, totalTokensOut, totalCostUsd);
    }

    private ArrayNode buildToolsJson(List<ToolDefinition> tools) {
        ArrayNode arr = objectMapper.createArrayNode();
        if (tools == null) return arr;
        for (ToolDefinition t : tools) {
            ObjectNode wrapper = arr.addObject();
            wrapper.put("type", "function");
            ObjectNode fn = wrapper.putObject("function");
            fn.put("name", t.name());
            if (t.description() != null) {
                fn.put("description", t.description());
            }
            if (t.inputSchema() != null) {
                fn.set("parameters", t.inputSchema());
            } else {
                ObjectNode params = fn.putObject("parameters");
                params.put("type", "object");
                params.putObject("properties");
            }
        }
        return arr;
    }

    private void recordToolUseUsage(String model, int iteration, int durationMs,
                                    int tokensIn, int tokensOut, double cost,
                                    List<String> toolNames) {
        if (llmCallRepository == null) return;
        try {
            LlmCall call = new LlmCall();
            call.setTimestamp(java.time.Instant.now());
            call.setModel(model);
            call.setTokensIn(tokensIn);
            call.setTokensOut(tokensOut);
            call.setCostUsd(cost);
            call.setDurationMs(durationMs);
            call.setIteration(iteration);
            call.setProjectSlug(com.workflow.project.ProjectContext.get());
            LlmCallContext.current().ifPresent(ctx -> {
                call.setRunId(ctx.runId());
                call.setBlockId(ctx.blockId());
            });
            if (toolNames != null && !toolNames.isEmpty()) {
                call.setToolCallsMadeJson(objectMapper.writeValueAsString(toolNames));
            }
            llmCallRepository.save(call);
        } catch (Exception e) {
            log.debug("LlmCall persist failed (tool-use iter {}): {}", iteration, e.getMessage());
        }
    }

    /**
     * Removes markdown code fence wrappers (```lang...```) from LLM output.
     */
    public String stripCodeFences(String text) {
        if (text == null) return null;
        String stripped = text.strip();

        // Match ```optionalLang\n...\n```
        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            if (firstNewline > 0) {
                String afterFence = stripped.substring(firstNewline + 1);
                if (afterFence.endsWith("```")) {
                    return afterFence.substring(0, afterFence.length() - 3).strip();
                }
            }
        }
        return stripped;
    }
}
