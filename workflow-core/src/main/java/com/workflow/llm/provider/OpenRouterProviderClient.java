package com.workflow.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.llm.LlmCallRepository;
import com.workflow.llm.LlmProvider;
import com.workflow.llm.ModelPresetResolver;
import com.workflow.model.IntegrationConfig;
import com.workflow.model.IntegrationConfigRepository;
import com.workflow.model.IntegrationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

/**
 * OpenRouter.ai provider — paid OpenAI-compatible cloud API, default route when no
 * project-level provider override is in effect. Bills against the user's OpenRouter
 * credits; cost is reported in the {@code usage.cost} field of each response and
 * persisted to {@link com.workflow.llm.LlmCall#costUsd}.
 *
 * <p>API key resolution: {@code IntegrationConfig(type=OPENROUTER)} token →
 * {@code OPENROUTER_API_KEY} env var → fail loud with operator-friendly message.
 */
@Service
public class OpenRouterProviderClient extends OpenAICompatibleProviderClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterProviderClient.class);

    private final IntegrationConfigRepository integrationConfigRepository;
    private final WebClient.Builder webClientBuilder;

    @Autowired
    public OpenRouterProviderClient(ObjectMapper objectMapper,
                                    ModelPresetResolver presetResolver,
                                    IntegrationConfigRepository integrationConfigRepository,
                                    WebClient.Builder webClientBuilder,
                                    @Autowired(required = false) LlmCallRepository llmCallRepository) {
        super(objectMapper, presetResolver, llmCallRepository);
        this.integrationConfigRepository = integrationConfigRepository;
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public LlmProvider providerType() {
        return LlmProvider.OPENROUTER;
    }

    @Override
    protected Logger logger() {
        return log;
    }

    @Override
    protected WebClient buildWebClient() {
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
}
