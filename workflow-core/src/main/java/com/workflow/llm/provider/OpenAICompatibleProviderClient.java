package com.workflow.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.llm.LlmCall;
import com.workflow.llm.LlmCallContext;
import com.workflow.llm.LlmCallRepository;
import com.workflow.llm.LlmProvider;
import com.workflow.llm.ModelPresetResolver;
import com.workflow.llm.Models;
import com.workflow.llm.tooluse.StopReason;
import com.workflow.llm.tooluse.ToolCall;
import com.workflow.llm.tooluse.ToolExecutor;
import com.workflow.llm.tooluse.ToolResult;
import com.workflow.llm.tooluse.ToolUseRequest;
import com.workflow.llm.tooluse.ToolUseResponse;
import com.workflow.tools.ToolCallIteration;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Common base for OpenAI-compatible HTTP providers ({@link OpenRouterProviderClient},
 * {@link AITunnelProviderClient}). Owns the request shape, retry/error handling,
 * tool-use loop, and usage recording — subclasses contribute only their own
 * {@link #buildWebClient()} (baseUrl/token/headers) and {@link #providerType()}.
 *
 * <p>Both OpenRouter and AITunnel speak the same {@code /chat/completions} dialect
 * with identical tool-use semantics; 95% of the code lived as if-polymorphism on
 * {@code shouldUseAITunnel()} inside the legacy LlmClient monolith. This abstract
 * class is where that body lives now — once per shared codebase.
 *
 * <p>Ollama and vLLM do <b>not</b> extend this class: they have enough quirks
 * ({@code think:false}, {@code num_ctx}, top_k/repetition_penalty, error format,
 * max_tokens caps) that lookup in {@code protected abstract} methods would make
 * the code more confusing than the duplication it would eliminate.
 */
abstract class OpenAICompatibleProviderClient implements LlmProviderClient {

    protected final ObjectMapper objectMapper;
    protected final ModelPresetResolver presetResolver;
    private final LlmCallRepository llmCallRepository;

    protected OpenAICompatibleProviderClient(ObjectMapper objectMapper,
                                             ModelPresetResolver presetResolver,
                                             @Autowired(required = false) LlmCallRepository llmCallRepository) {
        this.objectMapper = objectMapper;
        this.presetResolver = presetResolver;
        this.llmCallRepository = llmCallRepository;
    }

    /** Subclass-specific logger so log lines carry the provider name. */
    protected abstract Logger logger();

    /** Builds a fresh {@link WebClient} for the provider — baseUrl, auth headers,
     *  and {@code responseTimeout}. Called per-request (cheap, all state is on the
     *  builder). Throws when the integration is not configured. */
    protected abstract WebClient buildWebClient();

    @Override
    public String complete(String model, String system, String user, int maxTokens, double temperature) {
        ArrayNode messages = objectMapper.createArrayNode();
        if (system != null && !system.isBlank()) {
            messages.addObject().put("role", "system").put("content", system);
        }
        messages.addObject().put("role", "user").put("content", user);
        return chat(model, messages, maxTokens, temperature);
    }

    @Override
    public String completeWithMessages(String model, List<Map<String, String>> messages,
                                       int maxTokens, double temperature) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("completeWithMessages: messages must not be empty");
        }
        ArrayNode arr = objectMapper.createArrayNode();
        for (var msg : messages) {
            ObjectNode m = arr.addObject();
            m.put("role", msg.getOrDefault("role", "user"));
            m.put("content", msg.getOrDefault("content", ""));
        }
        return chat(model, arr, maxTokens, temperature);
    }

    private String chat(String model, ArrayNode messages, int maxTokens, double temperature) {
        String resolvedModel = resolveModel(model);
        WebClient client = buildWebClient();

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", resolvedModel);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);
        requestBody.set("messages", messages);

        logger().info("Calling {} model: {} (maxTokens={}, temperature={}, msgs={})",
            providerType(), resolvedModel, maxTokens, temperature, messages.size());

        long startedAt = System.currentTimeMillis();
        try {
            String responseBody = client.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            if (responseBody == null) {
                throw new RuntimeException("Empty response from " + providerType());
            }

            JsonNode responseJson = objectMapper.readTree(responseBody);
            String content = responseJson
                .path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText();

            if (content == null || content.isBlank()) {
                logger().warn("{} returned empty content. Full response: {}", providerType(), responseBody);
                throw new RuntimeException("Empty content in " + providerType() + " response");
            }

            recordUsage(responseJson, resolvedModel, (int) (System.currentTimeMillis() - startedAt));

            return LlmTextUtils.stripCodeFences(content.strip());

        } catch (Exception e) {
            logger().error("LLM call failed: {}", e.getMessage(), e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Resolves a preset/model name to a concrete OpenRouter-style identifier (e.g.
     * {@code z-ai/glm-4.6}). Anthropic-prefixed names are reserved for the CLI path
     * — when one slips through to this route (because CLI is inactive), fall back
     * to the {@code smart} tier with a warn log so we never silently bill Anthropic
     * via OpenRouter.
     */
    protected String resolveModel(String model) {
        String resolved = presetResolver != null ? presetResolver.resolve(model) : model;
        if (resolved == null) return null;
        if (!resolved.contains("/")) {
            String n = resolved.toLowerCase();
            if (n.startsWith("claude") || n.equals("sonnet") || n.equals("opus") || n.equals("haiku")) {
                String fallback = presetResolver != null ? presetResolver.resolve("smart") : Models.OR_FALLBACK;
                logger().warn("{} route received bare CLI name '{}' — Anthropic is CLI-only, "
                    + "falling back to smart tier '{}'", providerType(), resolved, fallback);
                return fallback;
            }
        }
        return resolved;
    }

    @Override
    public ToolUseResponse completeWithTools(ToolUseRequest request, ToolExecutor executor) {
        if (request == null) throw new IllegalArgumentException("request required");
        if (executor == null) throw new IllegalArgumentException("executor required");

        String resolvedModel = resolveModel(request.model());
        WebClient client = buildWebClient();

        ArrayNode messages = objectMapper.createArrayNode();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        messages.addObject().put("role", "user").put("content", request.userMessage());

        ArrayNode toolsJson = LlmTextUtils.buildToolsJson(request.tools(), objectMapper);

        List<ToolUseResponse.ToolCallTrace> history = new ArrayList<>();
        int iterations = 0;
        int totalTokensIn = 0;
        int totalTokensOut = 0;
        double totalCostUsd = 0.0;
        String finalText = "";

        logger().info("Starting tool-use loop: model={} tools={} maxIterations={} budget=${}",
            resolvedModel, toolsJson.size(), request.maxIterations(), request.budgetUsdCap());

        // Two reminders nudge the model toward producing a final answer:
        //   - early (~max/3): "you've explored enough, start converging"
        //   - hard (max-3):   "3 iterations left, emit final JSON now"
        // Both are role:user (NOT system) — the system message must stay stable for
        // future prompt-cache compatibility; mutating it would invalidate the prefix.
        boolean earlyReminderSent  = false;
        boolean softCapReminderSent = false;
        int earlyReminderAt = Math.max(5, request.maxIterations() / 3);

        while (iterations < request.maxIterations()) {
            iterations++;

            LlmTextUtils.pruneContextIfNeeded(messages);

            if (!earlyReminderSent
                && request.maxIterations() >= 9
                && iterations == earlyReminderAt) {
                ObjectNode reminder = messages.addObject();
                reminder.put("role", "user");
                reminder.put("content", String.format(
                    "PROGRESS CHECK: you've used %d of %d iterations and $%.2f of $%.2f budget. "
                        + "If you already have enough evidence to answer, emit the final JSON now. "
                        + "Otherwise focus remaining iterations on filling concrete gaps — avoid re-exploring "
                        + "files you've already read.",
                    iterations, request.maxIterations(), totalCostUsd, request.budgetUsdCap()));
                earlyReminderSent = true;
                logger().info("orchestrator early reminder injected at iter {}/{}",
                    iterations, request.maxIterations());
            }

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
                logger().info("orchestrator soft-cap reminder injected at iter {}/{}",
                    iterations, request.maxIterations());
            }

            if (request.progressCallback() != null) {
                request.progressCallback().accept(
                    "[" + resolvedModel + "] Итерация " + iterations + "/" + request.maxIterations());
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
                                    String friendly = friendlyHttpError(code, errBody);
                                    logger().error("{} ошибка {}: {}", providerType(), code, friendly);
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
                            logger().warn("Tool-use iteration {} attempt {}/3 transient error, retrying: {}", iterations, attempt, ex.getMessage());
                            Thread.sleep(2000L * attempt);
                        } else {
                            lastEx = ex;
                            break;
                        }
                    }
                }
                if (responseBody == null) {
                    throw lastEx != null ? lastEx : new RuntimeException("Empty response from " + providerType());
                }
                responseJson = objectMapper.readTree(responseBody);
            } catch (Exception e) {
                logger().error("Tool-use iteration {} failed: {}", iterations, e.getMessage());
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
                tokensIn, tokensOut, cost, toolNames, finishReason);

            String strippedContent = LlmTextUtils.stripThinkingBlocks(content);

            if (!strippedContent.isBlank()) {
                finalText = strippedContent;
            } else if (!content.isBlank()) {
                finalText = content;
            }

            if (request.completionSignal() != null && !request.completionSignal().isBlank()
                    && finalText.contains(request.completionSignal())) {
                String cleanText = finalText.replace(request.completionSignal(), "").strip();
                logger().info("Tool-use loop finished: COMPLETION_SIGNAL at iteration={} cost=${}",
                    iterations, totalCostUsd);
                return new ToolUseResponse(LlmTextUtils.stripCodeFences(cleanText),
                    StopReason.COMPLETION_SIGNAL, history,
                    iterations, totalTokensIn, totalTokensOut, totalCostUsd);
            }

            boolean hasToolCalls = "tool_calls".equals(finishReason)
                && toolCalls.isArray() && toolCalls.size() > 0;

            if (!hasToolCalls) {
                StopReason stop = "length".equals(finishReason)
                    ? StopReason.MAX_TOKENS : StopReason.END_TURN;
                logger().info("Tool-use loop finished: iterations={} stop={} tokens={}/{} cost=${}",
                    iterations, stop, totalTokensIn, totalTokensOut, totalCostUsd);
                return new ToolUseResponse(LlmTextUtils.stripCodeFences(finalText.strip()), stop, history,
                    iterations, totalTokensIn, totalTokensOut, totalCostUsd);
            }

            // Strip reasoning_content/reasoning from assistant messages before adding to history.
            // Reasoning models (qwen3, deepseek-r1) return these fields but OpenRouter
            // returns 400 if they're sent back in subsequent requests.
            ObjectNode historyMsg = messageNode.deepCopy();
            historyMsg.remove("reasoning_content");
            historyMsg.remove("reasoning");
            if (historyMsg.has("content") && !historyMsg.path("content").isNull()) {
                String stripped = LlmTextUtils.stripThinkingBlocks(historyMsg.path("content").asText(""));
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
                    logger().warn("Tool executor threw for {}: {}", toolName, e.getMessage());
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
                logger().warn("Tool-use budget exceeded: spent=${} cap=${}",
                    totalCostUsd, request.budgetUsdCap());
                return new ToolUseResponse(LlmTextUtils.stripCodeFences(finalText.strip()),
                    StopReason.BUDGET_EXCEEDED, history,
                    iterations, totalTokensIn, totalTokensOut, totalCostUsd);
            }
        }

        logger().warn("Tool-use loop hit maxIterations={}", request.maxIterations());
        return new ToolUseResponse(LlmTextUtils.stripCodeFences(finalText.strip()),
            StopReason.MAX_ITERATIONS, history,
            iterations, totalTokensIn, totalTokensOut, totalCostUsd);
    }

    /** Friendly error mapping for common HTTP failures. Overridable per provider —
     *  default messaging is OpenRouter-centric (mentions OpenRouter credits link). */
    protected String friendlyHttpError(int code, String errBody) {
        if (code == 402) {
            return "Недостаточно кредитов OpenRouter. Пополните баланс: https://openrouter.ai/settings/credits";
        } else if (code == 401) {
            return "Неверный OPENROUTER_API_KEY (401 Unauthorized)";
        } else if (code == 429) {
            return "Превышен лимит запросов OpenRouter (429 Rate Limit)";
        } else {
            return providerType() + " " + code + ": " + errBody;
        }
    }

    private void recordUsage(JsonNode responseJson, String model, int durationMs) {
        if (llmCallRepository == null) return;
        try {
            JsonNode usage = responseJson.path("usage");
            int tokensIn = usage.path("prompt_tokens").asInt(0);
            int tokensOut = usage.path("completion_tokens").asInt(0);
            double cost = usage.path("cost").asDouble(0.0);
            String finishReason = responseJson.path("choices").path(0).path("finish_reason").asText("");

            LlmCall call = new LlmCall();
            call.setTimestamp(java.time.Instant.now());
            call.setModel(model);
            call.setTokensIn(tokensIn);
            call.setTokensOut(tokensOut);
            call.setCostUsd(cost);
            call.setDurationMs(durationMs);
            call.setProjectSlug(com.workflow.project.ProjectContext.get());
            call.setProvider(providerType());
            if (!finishReason.isBlank()) call.setFinishReason(finishReason);
            LlmCallContext.current().ifPresent(ctx -> {
                call.setRunId(ctx.runId());
                call.setBlockId(ctx.blockId());
            });
            llmCallRepository.save(call);
        } catch (Exception e) {
            logger().debug("LlmCall persist failed: {}", e.getMessage());
        }
    }

    private void recordToolUseUsage(String model, int iteration, int durationMs,
                                    int tokensIn, int tokensOut, double cost,
                                    List<String> toolNames, String finishReason) {
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
            call.setProvider(providerType());
            if (finishReason != null && !finishReason.isBlank()) call.setFinishReason(finishReason);
            LlmCallContext.current().ifPresent(ctx -> {
                call.setRunId(ctx.runId());
                call.setBlockId(ctx.blockId());
            });
            if (toolNames != null && !toolNames.isEmpty()) {
                call.setToolCallsMadeJson(objectMapper.writeValueAsString(toolNames));
            }
            llmCallRepository.save(call);
        } catch (Exception e) {
            logger().debug("LlmCall persist failed (tool-use iter {}): {}", iteration, e.getMessage());
        }
    }
}
