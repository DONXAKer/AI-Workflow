package com.workflow.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.model.IntegrationConfig;
import com.workflow.model.IntegrationConfigRepository;
import com.workflow.model.IntegrationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Embeds text into vector representations via Ollama's {@code /api/embed} endpoint.
 *
 * <p>Default model {@code nomic-embed-text:v1.5} (768-dim, 274 MB) is small enough
 * to coexist with the chat model on an RTX 4060 8 GB; switch to
 * {@code mxbai-embed-large} (1024-dim) by setting
 * {@code workflow.knowledge.embed-model} in {@code application.yaml} when quality
 * matters more than speed.
 *
 * <p>Base URL is resolved from the default OLLAMA {@code IntegrationConfig} so the
 * embedder hits the same Ollama instance as the chat completion path. The
 * {@code /v1} suffix that OpenAI-compat clients append is stripped — embeddings
 * use the native Ollama endpoint at {@code /api/embed}.
 */
@Component
public class OllamaEmbedder {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbedder.class);

    private final ObjectMapper mapper;
    private final IntegrationConfigRepository integrationRepo;
    private final String embedModel;

    @Autowired
    public OllamaEmbedder(ObjectMapper mapper,
                          IntegrationConfigRepository integrationRepo,
                          @Value("${workflow.knowledge.embed-model:" + com.workflow.llm.Models.OLLAMA_EMBED_NOMIC + "}") String embedModel) {
        this.mapper = mapper;
        this.integrationRepo = integrationRepo;
        this.embedModel = embedModel;
    }

    /** Embeds a single passage. Returns null on transport / parse failure. */
    public float[] embedOne(String text) {
        List<float[]> all = embedBatch(List.of(text));
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Batches multiple inputs into a single Ollama call when possible. Ollama's
     * {@code /api/embed} accepts either a string or an array of strings in the
     * {@code input} field; the response always contains an {@code embeddings}
     * array regardless.
     *
     * @return one vector per input, in the same order; empty if the request fails
     */
    public List<float[]> embedBatch(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", embedModel);
        if (inputs.size() == 1) {
            body.put("input", inputs.get(0));
        } else {
            ArrayNode arr = body.putArray("input");
            inputs.forEach(arr::add);
        }

        WebClient client = buildClient();
        try {
            String responseBody = client.post()
                .uri("/api/embed")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                // 300s — chat-side blocks (impl_server agentic loop) hold the Ollama
                // queue for minutes at a time on 8 GB GPUs (OLLAMA_NUM_PARALLEL=1
                // serialises everything). 60s was getting silently swallowed by
                // KnowledgeBase.search → 0-hit fallback during every pipeline run.
                .timeout(Duration.ofSeconds(300))
                .block();
            if (responseBody == null) return List.of();
            JsonNode json = mapper.readTree(responseBody);
            JsonNode embeddings = json.path("embeddings");
            if (!embeddings.isArray()) {
                log.warn("Ollama /api/embed returned no 'embeddings' array: {}", responseBody);
                return List.of();
            }
            List<float[]> out = new ArrayList<>(embeddings.size());
            for (JsonNode vec : embeddings) {
                if (!vec.isArray()) continue;
                float[] v = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) v[i] = (float) vec.get(i).asDouble(0);
                out.add(v);
            }
            return out;
        } catch (Exception e) {
            log.warn("OllamaEmbedder /api/embed failed for {} input(s): {}", inputs.size(), e.getMessage());
            return List.of();
        }
    }

    /** Exposes the configured embedding model name so the indexer can stamp it on entries. */
    public String modelName() { return embedModel; }

    private WebClient buildClient() {
        String baseUrl = "http://localhost:11434";
        Optional<IntegrationConfig> ollamaConfig =
            integrationRepo.findByTypeAndIsDefaultTrue(IntegrationType.OLLAMA);
        if (ollamaConfig.isPresent()) {
            String url = ollamaConfig.get().getBaseUrl();
            if (url != null && !url.isBlank()) {
                baseUrl = url.replaceAll("/+$", "");
                // The chat path uses OpenAI-compat /v1; native /api/embed lives under
                // the bare host, so trim any trailing /v1 if present.
                if (baseUrl.endsWith("/v1")) baseUrl = baseUrl.substring(0, baseUrl.length() - 3);
            }
        }
        return WebClient.builder().baseUrl(baseUrl).build();
    }
}
