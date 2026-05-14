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
 * Local vLLM provider — pure OpenAI-compatible chat/completions, but with sampling
 * defaults that mirror the {@code mychen76/qwen3_cline_roocode} Modelfile (which is
 * what the OLLAMA route uses for the same model line).
 *
 * <p>Unlike Ollama's OpenAI-compat endpoint, vLLM does <b>not</b> understand
 * {@code think:false} or {@code options.num_ctx} — those are silently dropped
 * (best case) or rejected (worst case). The wire format is identical to OpenRouter,
 * only baseUrl differs. Without the Modelfile-equivalent defaults below, vLLM
 * falls back to temperature=1.0 (OpenAI default) and the Cline-tuned model goes
 * off-rails on agentic tool calls.
 *
 * <p>Cost recorded as 0.0 (local inference).
 */
@Service
public class VllmProviderClient implements LlmProviderClient {

    private static final Logger log = LoggerFactory.getLogger(VllmProviderClient.class);

    /** Modelfile-equivalent temperature for vLLM. Applied when caller leaves
     *  temperature at the system default of 1.0 (see {@link ToolUseRequest.Builder}).
     *  Explicit per-block {@code agent.temperature} in YAML resolves to a non-1.0
     *  value and bypasses this default. */
    private static final double VLLM_DEFAULT_TEMPERATURE = 0.25;
    private static final double VLLM_DEFAULT_TOP_P = 0.9;
    private static final int    VLLM_DEFAULT_TOP_K = 40;
    private static final double VLLM_DEFAULT_REPETITION_PENALTY = 1.1;
    /** Sentinel for "operator did not override temperature". Matches
     *  {@code AgentConfig.getTemperatureOrDefault()} and {@code ToolUseRequest.Builder}
     *  defaults. If a block sets {@code agent.temperature: 1.0} explicitly the
     *  Modelfile default still wins — acceptable since 1.0 is a poor choice for
     *  tool-use anyway. */
    private static final double VLLM_TEMP_UNSET_SENTINEL = 1.0;
    /** Tokens cap consistent with consumer-GPU vLLM deployments. vLLM streams
     *  cleanly with larger values too, but a tight cap keeps a tool-use iteration
     *  responsive. Set to 3000 (not 4096) because Qwen3-4B-AWQ on 8GB serves
     *  max_model_len=16384 — input+output must fit, and tool-use loops accumulate
     *  Read results fast (4096 output left ~12K input headroom, which the agent
     *  blew past after 5 file Reads on iteration 2). 3000 gives ~13K input. */
    private static final int VLLM_MAX_TOKENS_CAP = 3000;

    private final ObjectMapper objectMapper;
    private final IntegrationConfigRepository integrationConfigRepository;
    private final WebClient.Builder webClientBuilder;
    private final ModelPresetResolver presetResolver;
    private final LlmCallRepository llmCallRepository;

    @Autowired
    public VllmProviderClient(ObjectMapper objectMapper,
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
        return LlmProvider.VLLM;
    }

    @Override
    public String complete(String model, String system, String user, int maxTokens, double temperature) {
        String resolved = resolveVllmModel(model);
        ArrayNode messages = objectMapper.createArrayNode();
        if (system != null && !system.isBlank()) {
            messages.addObject().put("role", "system").put("content", system);
        }
        messages.addObject().put("role", "user").put("content", user);
        return chat(resolved, messages, maxTokens, temperature);
    }

    @Override
    public String completeWithMessages(String model, List<Map<String, String>> messages,
                                       int maxTokens, double temperature) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("completeWithMessages: messages must not be empty");
        }
        String resolved = resolveVllmModel(model);
        ArrayNode arr = objectMapper.createArrayNode();
        for (var msg : messages) {
            ObjectNode m = arr.addObject();
            m.put("role", msg.getOrDefault("role", "user"));
            m.put("content", msg.getOrDefault("content", ""));
        }
        return chat(resolved, arr, maxTokens, temperature);
    }

    private String chat(String model, ArrayNode messages, int maxTokens, double temperature) {
        WebClient client = buildWebClient();
        double effTemp = resolveTemperature(temperature);
        int effMaxTokens = Math.min(maxTokens > 0 ? maxTokens : VLLM_MAX_TOKENS_CAP, VLLM_MAX_TOKENS_CAP);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", effMaxTokens);
        requestBody.put("temperature", effTemp);
        requestBody.put("top_p", VLLM_DEFAULT_TOP_P);
        // vLLM accepts top_k and repetition_penalty as top-level OpenAI extensions
        // (documented under "Extra Parameters" in vLLM OpenAI server docs).
        requestBody.put("top_k", VLLM_DEFAULT_TOP_K);
        requestBody.put("repetition_penalty", VLLM_DEFAULT_REPETITION_PENALTY);
        requestBody.set("messages", messages);

        log.info("Calling vLLM model: {} (maxTokens={}, temperature={}, msgs={})",
            model, effMaxTokens, effTemp, messages.size());
        long startedAt = System.currentTimeMillis();
        try {
            String responseBody = client.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();
            if (responseBody == null) throw new RuntimeException("Empty response from vLLM");

            JsonNode responseJson = objectMapper.readTree(responseBody);
            String content = responseJson.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                log.warn("vLLM returned empty content. Full response: {}", responseBody);
                throw new RuntimeException("Empty content in vLLM response");
            }
            recordUsage(responseJson, model, (int)(System.currentTimeMillis() - startedAt));
            return LlmTextUtils.stripCodeFences(LlmTextUtils.stripThinkingBlocks(content.strip()));
        } catch (Exception e) {
            log.error("vLLM call failed: {}", e.getMessage(), e);
            throw new RuntimeException("vLLM call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ToolUseResponse completeWithTools(ToolUseRequest request, ToolExecutor executor) {
        if (request == null) throw new IllegalArgumentException("request required");
        if (executor == null) throw new IllegalArgumentException("executor required");

        String resolvedModel = resolveVllmModel(request.model());
        WebClient client = buildWebClient();
        double effTemp = resolveTemperature(request.temperature());

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
        String finalText = "";
        boolean earlyReminderSent = false;
        boolean softCapReminderSent = false;
        int earlyReminderAt = Math.max(5, request.maxIterations() / 3);

        log.info("Starting vLLM tool-use loop: model={} tools={} maxIterations={} temp={}",
            resolvedModel, toolsJson.size(), request.maxIterations(), effTemp);

        while (iterations < request.maxIterations()) {
            iterations++;
            LlmTextUtils.pruneContextIfNeeded(messages);

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
                messages.addObject().put("role", "user").put("content",
                    "REMINDER: 3 iterations remaining. If you have enough information, emit the final answer now.");
                softCapReminderSent = true;
            }

            if (request.progressCallback() != null) {
                request.progressCallback().accept(
                    "[" + resolvedModel + "] Итерация " + iterations + "/" + request.maxIterations());
            }

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", resolvedModel);
            body.put("max_tokens", Math.min(request.maxTokens(), VLLM_MAX_TOKENS_CAP));
            body.put("temperature", effTemp);
            body.put("top_p", VLLM_DEFAULT_TOP_P);
            body.put("top_k", VLLM_DEFAULT_TOP_K);
            body.put("repetition_penalty", VLLM_DEFAULT_REPETITION_PENALTY);
            body.set("messages", messages);
            boolean hasTools = toolsJson.size() > 0;
            if (hasTools) {
                body.set("tools", toolsJson);
                body.put("tool_choice", "auto");
            }
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
                if (responseBody == null) throw new RuntimeException("Empty response from vLLM");
                responseJson = objectMapper.readTree(responseBody);
            } catch (Exception e) {
                log.error("vLLM tool-use iteration {} failed: {}", iterations, e.getMessage());
                throw new RuntimeException("vLLM completeWithTools iteration " + iterations + " failed: " + e.getMessage(), e);
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
                log.info("vLLM tool-use loop finished: iterations={} stop={} tokens={}/{}",
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

        log.warn("vLLM tool-use loop hit maxIterations={}", request.maxIterations());
        return new ToolUseResponse(LlmTextUtils.stripCodeFences(finalText.strip()),
            StopReason.MAX_ITERATIONS, history, iterations, totalTokensIn, totalTokensOut, 0.0);
    }

    private String resolveVllmModel(String model) {
        return presetResolver != null ? presetResolver.resolveVllm(model) : (model != null ? model : Models.VLLM_FALLBACK);
    }

    private WebClient buildWebClient() {
        // vLLM is a standalone service (lives in D:\Проекты\vllm-stack on the
        // developer's machine, not coupled to this project). On Docker Desktop
        // for Windows, `host.docker.internal` routes from any container to the
        // host network, so this default works out of the box when the operator
        // has the vllm-stack compose running on host port 8003. For LAN / remote
        // vLLM hosts, override via the VLLM IntegrationConfig.baseUrl in UI.
        String baseUrl = "http://host.docker.internal:8003/v1";
        String apiKey = null;
        Optional<IntegrationConfig> vllmConfig =
            integrationConfigRepository.findByTypeAndIsDefaultTrue(IntegrationType.VLLM);
        if (vllmConfig.isPresent()) {
            IntegrationConfig cfg = vllmConfig.get();
            if (cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()) {
                baseUrl = cfg.getBaseUrl().replaceAll("/+$", "");
                if (!baseUrl.endsWith("/v1")) baseUrl = baseUrl + "/v1";
            }
            apiKey = cfg.getToken();
        }

        WebClient.Builder builder = webClientBuilder
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                reactor.netty.http.client.HttpClient.create()
                    .responseTimeout(java.time.Duration.ofSeconds(900))));
        // vLLM defaults to unauthenticated, but supports --api-key for ops who
        // expose it on a LAN. Send the header only when configured — vLLM rejects
        // empty Bearer.
        if (apiKey != null && !apiKey.isBlank()) {
            builder = builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    /** Returns the effective temperature: the Modelfile default (0.25) when the
     *  caller passed the system sentinel (1.0), otherwise the operator's override. */
    private static double resolveTemperature(double requested) {
        return requested == VLLM_TEMP_UNSET_SENTINEL ? VLLM_DEFAULT_TEMPERATURE : requested;
    }

    private void recordUsage(JsonNode responseJson, String model, int durationMs) {
        if (llmCallRepository == null) return;
        try {
            JsonNode usage = responseJson.path("usage");
            int tokensIn = usage.path("prompt_tokens").asInt(0);
            int tokensOut = usage.path("completion_tokens").asInt(0);
            String finishReason = responseJson.path("choices").path(0).path("finish_reason").asText("");
            LlmCall call = new LlmCall();
            call.setTimestamp(java.time.Instant.now());
            call.setModel(model);
            call.setTokensIn(tokensIn); call.setTokensOut(tokensOut); call.setCostUsd(0.0);
            call.setDurationMs(durationMs);
            call.setProjectSlug(com.workflow.project.ProjectContext.get());
            call.setProvider(LlmProvider.VLLM);
            if (!finishReason.isBlank()) call.setFinishReason(finishReason);
            LlmCallContext.current().ifPresent(ctx -> { call.setRunId(ctx.runId()); call.setBlockId(ctx.blockId()); });
            llmCallRepository.save(call);
        } catch (Exception e) { log.debug("LlmCall persist failed (vLLM): {}", e.getMessage()); }
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
            call.setProvider(LlmProvider.VLLM);
            if (finishReason != null && !finishReason.isBlank()) call.setFinishReason(finishReason);
            LlmCallContext.current().ifPresent(ctx -> { call.setRunId(ctx.runId()); call.setBlockId(ctx.blockId()); });
            if (toolNames != null && !toolNames.isEmpty())
                call.setToolCallsMadeJson(objectMapper.writeValueAsString(toolNames));
            llmCallRepository.save(call);
        } catch (Exception e) { log.debug("LlmCall persist failed (vLLM tool-use iter {}): {}", iteration, e.getMessage()); }
    }
}
