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
 * AllTokens.ru provider — second OpenAI-compatible Russian aggregator (alongside
 * {@link AITunnelProviderClient}). Adopted as AITunnel fallback after AITunnel's
 * DeepInfra upstream consistently exceeded the 120s WebClient timeout on
 * analysis-size glm-4.6 prompts (2026-05-18). AllTokens claims intelligent
 * cross-provider routing with automatic fallback — same model namespace as
 * OpenRouter (e.g. {@code z-ai/glm-4.6}, {@code google/gemini-2.5-flash-lite}),
 * so existing block configs continue to work without YAML edits.
 *
 * <p>Selected when {@link com.workflow.llm.LlmCallContext} pins
 * {@code preferredProvider=ALLTOKENS} (set from {@code Project.defaultProvider}).
 *
 * <p>API key resolution: {@code IntegrationConfig(type=ALLTOKENS)} token →
 * {@code ALLTOKENS_API_KEY} env var → fail loud.
 */
@Service
public class AllTokensProviderClient extends OpenAICompatibleProviderClient {

    private static final Logger log = LoggerFactory.getLogger(AllTokensProviderClient.class);

    private final IntegrationConfigRepository integrationConfigRepository;
    private final WebClient.Builder webClientBuilder;

    @Autowired
    public AllTokensProviderClient(ObjectMapper objectMapper,
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
        return LlmProvider.ALLTOKENS;
    }

    @Override
    protected Logger logger() {
        return log;
    }

    @Override
    protected WebClient buildWebClient() {
        String baseUrl = "https://api.alltokens.ru/api/v1";
        String apiKey = null;
        Optional<IntegrationConfig> cfg = integrationConfigRepository
            .findByTypeAndIsDefaultTrue(IntegrationType.ALLTOKENS);
        if (cfg.isPresent()) {
            IntegrationConfig c = cfg.get();
            if (c.getBaseUrl() != null && !c.getBaseUrl().isBlank()) baseUrl = c.getBaseUrl();
            apiKey = c.getToken();
        }
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("ALLTOKENS_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("No AllTokens API key configured. Configure via /api/integrations (type=ALLTOKENS) or set ALLTOKENS_API_KEY env var.");
        }
        return webClientBuilder
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                reactor.netty.http.client.HttpClient.create()
                    .responseTimeout(java.time.Duration.ofSeconds(120))))
            .build();
    }
}
