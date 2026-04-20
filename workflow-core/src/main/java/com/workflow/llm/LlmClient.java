package com.workflow.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.llm.tooluse.ToolExecutor;
import com.workflow.llm.tooluse.ToolUseRequest;
import com.workflow.llm.tooluse.ToolUseResponse;
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
        // Resolve via preset table first (fast / smart / reasoning / cheap). Full
        // "vendor/model" identifiers pass through; legacy raw Anthropic names get the
        // "anthropic/" prefix appended below.
        String resolvedModel = presetResolver != null ? presetResolver.resolve(model) : model;
        if (resolvedModel != null && !resolvedModel.contains("/")) {
            resolvedModel = "anthropic/" + resolvedModel;
        }

        // Fetch OpenRouter config from DB or fall back to env var
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

        // Build request body
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
            WebClient client = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("HTTP-Referer", "https://workflow.app")
                .defaultHeader("X-Title", "Workflow Pipeline")
                .build();

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
     * Runs an agentic tool-use loop against the configured provider.
     *
     * <p>Each iteration: POST messages+tools → parse response → if {@code stop_reason ==
     * tool_use}, dispatch each requested {@link com.workflow.llm.tooluse.ToolCall} to the
     * {@code executor}, append tool results as the next user turn, and loop. Stops when
     * the model signals {@code end_turn}, exhausts {@code maxIterations}, exceeds
     * {@code budgetUsdCap}, or hits {@code max_tokens}.
     *
     * <p>Every API iteration persists its own {@link LlmCall} row tagged with the
     * iteration index, so per-step audit is preserved.
     *
     * <p>Implementation lands in M1.3 — this stub exists to pin the public surface and
     * let blocks compile against it.
     */
    public ToolUseResponse completeWithTools(ToolUseRequest request, ToolExecutor executor) {
        throw new UnsupportedOperationException(
            "completeWithTools not implemented yet (M1.3 pending) — see project_ai_workflow_phase1_plan.md");
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
