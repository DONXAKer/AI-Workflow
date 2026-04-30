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
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                reactor.netty.http.client.HttpClient.create()
                    .responseTimeout(java.time.Duration.ofSeconds(120))))
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

        // Whether we've already injected the "wrap up" reminder. The reminder is a
        // synthetic user message appended once near the end of the loop so the model
        // knows to emit a final answer instead of continuing to explore. Role:user
        // (NOT system) — the system message must stay stable for future prompt-cache
        // compatibility; mutating it would invalidate the cached prefix.
        boolean softCapReminderSent = false;

        while (iterations < request.maxIterations()) {
            iterations++;

            pruneContextIfNeeded(messages);

            // Inject the soft-cap reminder at iter == max - 3 so the model has 3 more
            // round-trips to wrap up. Skip if max is too small for this to help.
            if (!softCapReminderSent
                && request.maxIterations() >= 6
                && iterations == request.maxIterations() - 3) {
                ObjectNode reminder = messages.addObject();
                reminder.put("role", "user");
                reminder.put("content", String.format(
                    "REMINDER: 3 iterations remaining ($%.2f of $%.2f budget used). "
                        + "If you have enough information, emit the final answer (JSON/end_turn) now — "
                        + "further exploration risks running out without producing a result.",
                    totalCostUsd, request.budgetUsdCap()));
                softCapReminderSent = true;
                log.info("orchestrator soft-cap reminder injected at iter {}/{}",
                    iterations, request.maxIterations());
            }

            if (request.progressCallback() != null) {
                request.progressCallback().accept(
                    "Итерация " + iterations + " / " + request.maxIterations());
            }

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
                String responseBody = null;
                Exception lastEx = null;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        responseBody = client.post()
                            .uri("/chat/completions")
                            .bodyValue(body)
                            .retrieve()
                            .onStatus(
                                status -> status.is4xxClientError() || status.is5xxServerError(),
                                resp -> resp.bodyToMono(String.class).map(errBody -> {
                                    int code = resp.statusCode().value();
                                    String friendly;
                                    if (code == 402) {
                                        friendly = "Недостаточно кредитов OpenRouter. Пополните баланс: https://openrouter.ai/settings/credits";
                                    } else if (code == 401) {
                                        friendly = "Неверный OPENROUTER_API_KEY (401 Unauthorized)";
                                    } else if (code == 429) {
                                        friendly = "Превышен лимит запросов OpenRouter (429 Rate Limit)";
                                    } else {
                                        friendly = "OpenRouter " + code + ": " + errBody;
                                    }
                                    log.error("OpenRouter ошибка {}: {}", code, friendly);
                                    return new org.springframework.web.reactive.function.client.WebClientResponseException(
                                        code, friendly, null,
                                        friendly.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
                                })
                            )
                            .bodyToMono(String.class)
                            .block();
                        break;
                    } catch (Exception ex) {
                        boolean transient_ = ex.getMessage() != null && (
                            ex.getMessage().contains("PrematureCloseException") ||
                            ex.getMessage().contains("Connection reset") ||
                            ex.getMessage().contains("connection") && ex.getMessage().contains("closed"));
                        if (transient_ && attempt < 3) {
                            log.warn("Tool-use iteration {} attempt {}/3 transient error, retrying: {}", iterations, attempt, ex.getMessage());
                            Thread.sleep(2000L * attempt);
                        } else {
                            lastEx = ex;
                            break;
                        }
                    }
                }
                if (responseBody == null) {
                    throw lastEx != null ? lastEx : new RuntimeException("Empty response from OpenRouter");
                }
                responseJson = objectMapper.readTree(responseBody);
            } catch (Exception e) {
                log.error("Tool-use iteration {} failed: {}", iterations, e.getMessage());
                throw new RuntimeException("completeWithTools iteration " + iterations + " failed: " + e.getMessage(), e);
            }

            JsonNode choice = responseJson.path("choices").path(0);
            JsonNode messageNode = choice.path("message");
            String finishReason = choice.path("finish_reason").asText("");
            String content = messageNode.path("content").isNull()
                ? "" : messageNode.path("content").asText("");
            // Gemini 2.5 Pro thinking models return content:null with the answer in reasoning.
            // Fall back to reasoning field so the tool-use loop gets the final text.
            if (content.isBlank()) {
                String reasoning = messageNode.path("reasoning").asText("");
                if (reasoning.isBlank()) {
                    JsonNode rd = messageNode.path("reasoning_details");
                    if (rd.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode r : rd) sb.append(r.path("text").asText(""));
                        reasoning = sb.toString();
                    }
                }
                if (!reasoning.isBlank()) content = reasoning;
            }
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

            // For reasoning models the content field includes <think>...</think> blocks;
            // strip them so callers (e.g. OrchestratorBlock) see only the actual output.
            String strippedContent = stripThinkingBlocks(content);

            if (!strippedContent.isBlank()) {
                finalText = strippedContent;
            } else if (!content.isBlank()) {
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

            // Strip reasoning_content/reasoning from assistant messages before adding to history.
            // Reasoning models (qwen3, deepseek-r1) return these fields but OpenRouter
            // returns 400 if they're sent back in subsequent requests.
            ObjectNode historyMsg = messageNode.deepCopy();
            historyMsg.remove("reasoning_content");
            historyMsg.remove("reasoning");
            // Also strip <think>...</think> from content field in history so it doesn't accumulate.
            if (historyMsg.has("content") && !historyMsg.path("content").isNull()) {
                String stripped = stripThinkingBlocks(historyMsg.path("content").asText(""));
                if (!stripped.isBlank()) {
                    historyMsg.put("content", stripped);
                }
            }
            messages.add(historyMsg);

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

    /**
     * Keeps context size manageable by dropping old assistant+tool pairs when
     * messages array grows past threshold. Always preserves:
     * 1. System message (role=system) if present at index 0
     * 2. First user message (initial task)
     * 3. The most recent KEEP_RECENT messages
     */
    private static final int MAX_CONTEXT_MSGS = 40;
    private static final int KEEP_RECENT_MSGS = 28;

    private void pruneContextIfNeeded(ArrayNode messages) {
        if (messages.size() <= MAX_CONTEXT_MSGS) return;

        List<com.fasterxml.jackson.databind.JsonNode> all = new ArrayList<>();
        messages.forEach(all::add);

        // Determine how many "anchor" messages to keep at the front
        int anchor = 0;
        if (!all.isEmpty() && "system".equals(all.get(0).path("role").asText())) anchor++;
        if (anchor < all.size() && "user".equals(all.get(anchor).path("role").asText())) anchor++;

        // Keep anchor messages + KEEP_RECENT_MSGS from the tail,
        // but don't trim in the middle of an assistant+tool group
        int tailStart = Math.max(anchor, all.size() - KEEP_RECENT_MSGS);
        // Walk back until we find an assistant message (to avoid orphaned tool results)
        while (tailStart > anchor && !"assistant".equals(all.get(tailStart).path("role").asText())) {
            tailStart--;
        }

        int dropped = tailStart - anchor;
        if (dropped <= 0) return;

        log.info("Context pruning: dropping {} old messages (total was {})", dropped, all.size());
        messages.removeAll();
        for (int i = 0; i < anchor; i++) messages.add(all.get(i));
        for (int i = tailStart; i < all.size(); i++) messages.add(all.get(i));
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
     * Strips {@code <think>...</think>} reasoning blocks from model output.
     * Reasoning models (qwen3, deepseek-r1) embed thinking in the content field;
     * callers typically only need the non-reasoning part.
     */
    public static String stripThinkingBlocks(String text) {
        if (text == null || !text.contains("<think>")) return text;
        // Remove all <think>...</think> blocks (greedy, handles multi-line)
        return text.replaceAll("(?s)<think>.*?</think>", "").strip();
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
