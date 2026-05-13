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
import com.workflow.model.IntegrationConfig;
import com.workflow.model.IntegrationConfigRepository;
import com.workflow.model.IntegrationType;
import com.workflow.tools.ToolCallIteration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Local Ollama provider — speaks Ollama's OpenAI-compat dialect but with platform-
 * specific quirks that don't belong on the OpenAI-compat base class:
 * <ul>
 *   <li>{@code think:false} option for qwen3-family models (disables reasoning to
 *       cut latency on agentic tool-use loops). Excludes qwen3.6 — empirically
 *       returns empty content when {@code think:false} is set.
 *   <li>{@code options.num_ctx = 16384} — Modelfile-overriding context window cap,
 *       tuned for 8 GB VRAM (qwen3-8B at 32K ctx OOMs after ~5 iterations).
 *   <li>{@code max_tokens} cap of 3000 for single-shot calls (longer outputs push
 *       past context window and timeout on consumer GPUs).
 *   <li>VRAM-unload utility ({@link #unloadModelsExcept(String)}) — flushes every
 *       resident model except the named one via Ollama's {@code keep_alive: 0}
 *       contract. Used by blocks before switching between models on 8 GB cards.
 *   <li>Cost recorded as {@code 0.0} (local inference, no provider billing).
 * </ul>
 */
@Service
public class OllamaProviderClient implements LlmProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaProviderClient.class);

    /** Reasonable upper bound for local Ollama outputs — longer ones push past
     *  context and timeout on consumer GPUs. */
    private static final int OLLAMA_MAX_TOKENS_CAP = 3000;
    /** Context window cap. 8K was too small (truncated tool schemas); 32K OOMed
     *  qwen3-8B on RTX 4060 8 GB after ~5 agentic iterations. 16K is the sweet
     *  spot — tools+CLAUDE.md fit, KV-cache stays under 8 GB. */
    private static final int OLLAMA_NUM_CTX = 16384;

    private final ObjectMapper objectMapper;
    private final IntegrationConfigRepository integrationConfigRepository;
    private final WebClient.Builder webClientBuilder;
    private final ModelPresetResolver presetResolver;
    private final LlmCallRepository llmCallRepository;

    @Autowired
    public OllamaProviderClient(ObjectMapper objectMapper,
                                IntegrationConfigRepository integrationConfigRepository,
                                WebClient.Builder webClientBuilder,
                                ModelPresetResolver presetResolver,
                                @Autowired(required = false) LlmCallRepository llmCallRepository) {
        this.objectMapper = objectMapper;
        this.integrationConfigRepository = integrationConfigRepository;
        this.webClientBuilder = webClientBuilder;
        this.presetResolver = presetResolver;
        this.llmCallRepository = llmCallRepository;
    }

    @Override
    public LlmProvider providerType() {
        return LlmProvider.OLLAMA;
    }

    @Override
    public String complete(String model, String system, String user, int maxTokens, double temperature) {
        String resolved = resolveOllamaModel(model);
        ArrayNode messages = objectMapper.createArrayNode();
        if (system != null && !system.isBlank()) {
            messages.addObject().put("role", "system").put("content", system);
        }
        messages.addObject().put("role", "user").put("content", injectNoThink(user, resolved));
        return chat(resolved, messages, maxTokens, temperature);
    }

    @Override
    public String completeWithMessages(String model, List<Map<String, String>> messages,
                                       int maxTokens, double temperature) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("completeWithMessages: messages must not be empty");
        }
        String resolved = resolveOllamaModel(model);
        ArrayNode arr = objectMapper.createArrayNode();
        for (var msg : messages) {
            ObjectNode m = arr.addObject();
            m.put("role", msg.getOrDefault("role", "user"));
            m.put("content", msg.getOrDefault("content", ""));
        }
        return chat(resolved, arr, maxTokens, temperature);
    }

    /**
     * Multi-turn chat completion via Ollama OpenAI-compat endpoint. Honours the
     * same {@code think:false} / num_ctx settings as the single-turn call. Bypasses
     * {@link #OLLAMA_MAX_TOKENS_CAP} since continuation calls may need more headroom.
     */
    private String chat(String model, ArrayNode messages, int maxTokens, double temperature) {
        WebClient client = buildWebClient();

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);
        if (isQwen3Model(model)) requestBody.put("think", false);
        requestBody.putObject("options").put("num_ctx", OLLAMA_NUM_CTX);
        requestBody.set("messages", messages);

        log.info("Calling Ollama model: {} (maxTokens={}, temperature={}, msgs={})",
            model, maxTokens, temperature, messages.size());
        long startedAt = System.currentTimeMillis();
        try {
            String responseBody = client.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            if (responseBody == null) throw new RuntimeException("Empty response from Ollama");

            JsonNode responseJson = objectMapper.readTree(responseBody);
            String content = responseJson.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                log.warn("Ollama returned empty content. Full response: {}", responseBody);
                throw new RuntimeException("Empty content in Ollama response");
            }
            recordUsage(model, (int)(System.currentTimeMillis() - startedAt));
            return LlmTextUtils.stripCodeFences(LlmTextUtils.stripThinkingBlocks(content.strip()));
        } catch (Exception e) {
            log.error("Ollama call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Ollama call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ToolUseResponse completeWithTools(ToolUseRequest request, ToolExecutor executor) {
        if (request == null) throw new IllegalArgumentException("request required");
        if (executor == null) throw new IllegalArgumentException("executor required");

        String resolvedModel = resolveOllamaModel(request.model());
        WebClient client = buildWebClient();

        ArrayNode messages = objectMapper.createArrayNode();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        messages.addObject().put("role", "user").put("content", injectNoThink(request.userMessage(), resolvedModel));

        ArrayNode toolsJson = LlmTextUtils.buildToolsJson(request.tools(), objectMapper);

        List<ToolUseResponse.ToolCallTrace> history = new ArrayList<>();
        int iterations = 0;
        int totalTokensIn = 0;
        int totalTokensOut = 0;
        String finalText = "";
        boolean earlyReminderSent = false;
        boolean softCapReminderSent = false;
        int earlyReminderAt = Math.max(5, request.maxIterations() / 3);

        log.info("Starting Ollama tool-use loop: model={} tools={} maxIterations={}",
            resolvedModel, toolsJson.size(), request.maxIterations());

        while (iterations < request.maxIterations()) {
            iterations++;
            LlmTextUtils.pruneContextIfNeeded(messages);

            // Early reminder at ~1/3 of the iteration budget — see OpenRouter loop comments.
            if (!earlyReminderSent && request.maxIterations() >= 9
                    && iterations == earlyReminderAt) {
                messages.addObject().put("role", "user").put("content", String.format(
                    "PROGRESS CHECK: you've used %d of %d iterations. If you already have enough "
                        + "evidence, emit the final JSON now. Otherwise focus on concrete gaps — "
                        + "avoid re-reading files you've already seen.",
                    iterations, request.maxIterations()));
                earlyReminderSent = true;
            }

            if (!softCapReminderSent && request.maxIterations() >= 6
                    && iterations == request.maxIterations() - 3) {
                messages.addObject().put("role", "user").put("content", String.format(
                    "REMINDER: 3 iterations remaining. If you have enough information, emit the final answer now."));
                softCapReminderSent = true;
            }

            if (request.progressCallback() != null) {
                request.progressCallback().accept(
                    "[" + resolvedModel + "] Итерация " + iterations + "/" + request.maxIterations());
            }

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", resolvedModel);
            body.put("max_tokens", Math.min(request.maxTokens(), OLLAMA_MAX_TOKENS_CAP));
            body.put("temperature", request.temperature());
            body.set("messages", messages);
            // qwen3 plans tool_calls inside its thinking block; setting think:false
            // makes it emit a plain chat completion with zero tool_calls. Keep
            // thinking ON when tools are passed (paying the latency cost) — there's
            // no point in saving tokens if the model never uses the tools we gave it.
            boolean hasTools = toolsJson.size() > 0;
            if (isQwen3Model(resolvedModel) && !hasTools) body.put("think", false);
            body.putObject("options").put("num_ctx", OLLAMA_NUM_CTX);
            if (hasTools) {
                body.set("tools", toolsJson);
                body.put("tool_choice", "auto");
            }
            // Caller-requested response format. We hit Ollama's OpenAI-compat endpoint
            // (/v1/chat/completions), which ignores the native `format` field but honours
            // OpenAI's `response_format: {type: "json_object"}`. Used by OrchestratorBlock
            // to make small local models reliably emit final-state JSON.
            if ("json".equalsIgnoreCase(request.responseFormat())) {
                body.putObject("response_format").put("type", "json_object");
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
                if (responseBody == null) throw new RuntimeException("Empty response from Ollama");
                responseJson = objectMapper.readTree(responseBody);
            } catch (Exception e) {
                log.error("Ollama tool-use iteration {} failed: {}", iterations, e.getMessage());
                throw new RuntimeException("Ollama completeWithTools iteration " + iterations + " failed: " + e.getMessage(), e);
            }

            JsonNode choice = responseJson.path("choices").path(0);
            JsonNode messageNode = choice.path("message");
            String finishReason = choice.path("finish_reason").asText("");
            String content = messageNode.path("content").isNull() ? "" : messageNode.path("content").asText("");
            JsonNode toolCalls = messageNode.path("tool_calls");

            JsonNode usage = responseJson.path("usage");
            int tokensIn = usage.path("prompt_tokens").asInt(0);
            int tokensOut = usage.path("completion_tokens").asInt(0);
            totalTokensIn += tokensIn;
            totalTokensOut += tokensOut;

            List<String> toolNames = new ArrayList<>();
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) toolNames.add(tc.path("function").path("name").asText(""));
            }
            recordToolUseUsage(resolvedModel, iterations,
                (int)(System.currentTimeMillis() - startedAt),
                tokensIn, tokensOut, toolNames, finishReason);

            String strippedContent = LlmTextUtils.stripThinkingBlocks(content);
            if (!strippedContent.isBlank()) finalText = strippedContent;
            else if (!content.isBlank()) finalText = content;

            if (request.completionSignal() != null && !request.completionSignal().isBlank()
                    && finalText.contains(request.completionSignal())) {
                return new ToolUseResponse(LlmTextUtils.stripCodeFences(finalText.replace(request.completionSignal(), "").strip()),
                    StopReason.COMPLETION_SIGNAL, history, iterations, totalTokensIn, totalTokensOut, 0.0);
            }

            boolean hasToolCalls = "tool_calls".equals(finishReason) && toolCalls.isArray() && toolCalls.size() > 0;
            if (!hasToolCalls) {
                StopReason stop = "length".equals(finishReason) ? StopReason.MAX_TOKENS : StopReason.END_TURN;
                log.info("Ollama tool-use loop finished: iterations={} stop={} tokens={}/{}",
                    iterations, stop, totalTokensIn, totalTokensOut);
                return new ToolUseResponse(LlmTextUtils.stripCodeFences(finalText.strip()), stop, history,
                    iterations, totalTokensIn, totalTokensOut, 0.0);
            }

            ObjectNode historyMsg = messageNode.deepCopy();
            historyMsg.remove("reasoning_content");
            historyMsg.remove("reasoning");
            if (historyMsg.has("content") && !historyMsg.path("content").isNull()) {
                String stripped = LlmTextUtils.stripThinkingBlocks(historyMsg.path("content").asText(""));
                if (!stripped.isBlank()) historyMsg.put("content", stripped);
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
                    if (result == null) result = ToolResult.error(callId, "executor returned null");
                } catch (Exception e) {
                    log.warn("Tool executor threw for {}: {}", toolName, e.getMessage());
                    result = ToolResult.error(callId, "executor_failure: " + e.getMessage());
                } finally {
                    ToolCallIteration.clear();
                }
                history.add(new ToolUseResponse.ToolCallTrace(iterations, call, result));
                messages.addObject()
                    .put("role", "tool")
                    .put("tool_call_id", callId)
                    .put("content", result.content() == null ? "" : result.content());
            }
        }

        log.warn("Ollama tool-use loop hit maxIterations={}", request.maxIterations());
        return new ToolUseResponse(LlmTextUtils.stripCodeFences(finalText.strip()),
            StopReason.MAX_ITERATIONS, history, iterations, totalTokensIn, totalTokensOut, 0.0);
    }

    /**
     * Unloads every model currently resident in Ollama VRAM except {@code keepLoaded}
     * (pass {@code null} to unload everything). Triggered by the pipeline runner
     * before each Ollama-bound block — avoids thrashing on 8 GB GPUs where two
     * resident models (e.g. qwen3.6:35b-a3b 28 GB + cline_roocode:8b 5 GB) cause
     * Ollama to return {@code null} errors mid-tool-use loop.
     *
     * <p>Mechanism: Ollama's documented {@code keep_alive: 0} contract on
     * {@code /api/generate} flushes the named model from memory.
     */
    public void unloadModelsExcept(String keepLoaded) {
        try {
            WebClient client = buildWebClient();
            String listing = client.get().uri("/api/ps").retrieve().bodyToMono(String.class)
                .timeout(java.time.Duration.ofSeconds(5)).block();
            if (listing == null) return;
            JsonNode root = objectMapper.readTree(listing);
            JsonNode models = root.path("models");
            if (!models.isArray()) return;
            for (JsonNode m : models) {
                String name = m.path("name").asText("");
                if (name.isEmpty() || name.equals(keepLoaded)) continue;
                try {
                    ObjectNode unload = objectMapper.createObjectNode();
                    unload.put("model", name);
                    unload.put("keep_alive", 0);
                    client.post().uri("/api/generate").bodyValue(unload).retrieve()
                        .bodyToMono(String.class)
                        .timeout(java.time.Duration.ofSeconds(15))
                        .onErrorReturn("")
                        .block();
                    log.info("Ollama: unloaded {} (keeping {})", name, keepLoaded);
                } catch (Exception ignored) { /* best-effort */ }
            }
        } catch (Exception e) {
            log.debug("unloadOllamaModelsExcept({}) skipped: {}", keepLoaded, e.getMessage());
        }
    }

    private String resolveOllamaModel(String model) {
        return presetResolver != null ? presetResolver.resolveOllama(model) : (model != null ? model : Models.OLLAMA_FALLBACK);
    }

    private WebClient buildWebClient() {
        String baseUrl = "http://localhost:11434";
        Optional<IntegrationConfig> ollamaConfig =
            integrationConfigRepository.findByTypeAndIsDefaultTrue(IntegrationType.OLLAMA);
        if (ollamaConfig.isPresent()) {
            IntegrationConfig cfg = ollamaConfig.get();
            if (cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()) {
                baseUrl = cfg.getBaseUrl().replaceAll("/+$", "");
            }
        }
        if (!baseUrl.endsWith("/v1")) baseUrl = baseUrl + "/v1";
        return webClientBuilder
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                reactor.netty.http.client.HttpClient.create()
                    .responseTimeout(java.time.Duration.ofSeconds(900))))
            .build();
    }

    /**
     * Matches qwen3-family models whose chat templates support the {@code think}
     * parameter. qwen3.6 was deliberately excluded after empirical tests — it
     * returned empty content under {@code think:false}, emitted structured JSON
     * only when thinking was on.
     */
    private static boolean isQwen3Model(String model) {
        if (model == null) return false;
        String m = model.toLowerCase();
        if (m.startsWith(Models.FAMILY_QWEN36)) return false;
        return m.startsWith(Models.FAMILY_QWEN3);
    }

    private boolean isReasoningDisabled() {
        Optional<IntegrationConfig> cfg =
            integrationConfigRepository.findByTypeAndIsDefaultTrue(IntegrationType.OLLAMA);
        if (cfg.isEmpty()) return false;
        String extra = cfg.get().getExtraConfigJson();
        if (extra == null || extra.isBlank()) return false;
        try {
            return objectMapper.readTree(extra).path("disableReasoning").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private String injectNoThink(String userMessage, String model) {
        if (!isQwen3Model(model) || !isReasoningDisabled()) return userMessage;
        return "/no_think\n\n" + (userMessage == null ? "" : userMessage);
    }

    private void recordUsage(String model, int durationMs) {
        if (llmCallRepository == null) return;
        try {
            LlmCall call = new LlmCall();
            call.setTimestamp(java.time.Instant.now());
            call.setModel(model);
            call.setTokensIn(0); call.setTokensOut(0); call.setCostUsd(0.0);
            call.setDurationMs(durationMs);
            call.setProjectSlug(com.workflow.project.ProjectContext.get());
            call.setProvider(LlmProvider.OLLAMA);
            LlmCallContext.current().ifPresent(ctx -> { call.setRunId(ctx.runId()); call.setBlockId(ctx.blockId()); });
            llmCallRepository.save(call);
        } catch (Exception e) { log.debug("LlmCall persist failed (Ollama): {}", e.getMessage()); }
    }

    private void recordToolUseUsage(String model, int iteration, int durationMs,
                                    int tokensIn, int tokensOut, List<String> toolNames, String finishReason) {
        if (llmCallRepository == null) return;
        try {
            LlmCall call = new LlmCall();
            call.setTimestamp(java.time.Instant.now());
            call.setModel(model);
            call.setTokensIn(tokensIn); call.setTokensOut(tokensOut); call.setCostUsd(0.0);
            call.setDurationMs(durationMs);
            call.setIteration(iteration);
            call.setProjectSlug(com.workflow.project.ProjectContext.get());
            call.setProvider(LlmProvider.OLLAMA);
            if (finishReason != null && !finishReason.isBlank()) call.setFinishReason(finishReason);
            LlmCallContext.current().ifPresent(ctx -> { call.setRunId(ctx.runId()); call.setBlockId(ctx.blockId()); });
            if (toolNames != null && !toolNames.isEmpty())
                call.setToolCallsMadeJson(objectMapper.writeValueAsString(toolNames));
            llmCallRepository.save(call);
        } catch (Exception e) { log.debug("LlmCall persist failed (Ollama tool-use iter {}): {}", iteration, e.getMessage()); }
    }
}
