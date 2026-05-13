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
 * AITunnel.ru provider — OpenAI-compatible Russian aggregator, same call shape as
 * OpenRouter (request/response/tool-use), different baseUrl and token. Selected
 * when {@link com.workflow.llm.LlmCallContext} pins {@code preferredProvider=AITUNNEL}
 * (set from {@code Project.defaultProvider}). Useful when OpenRouter is geoblocked
 * or the operator prefers a domestic gateway.
 *
 * <p>API key resolution: {@code IntegrationConfig(type=AITUNNEL)} token →
 * {@code AITUNNEL_API_KEY} env var → fail loud.
 */
@Service
public class AITunnelProviderClient extends OpenAICompatibleProviderClient {

    private static final Logger log = LoggerFactory.getLogger(AITunnelProviderClient.class);

    private final IntegrationConfigRepository integrationConfigRepository;
    private final WebClient.Builder webClientBuilder;

    @Autowired
    public AITunnelProviderClient(ObjectMapper objectMapper,
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
        return LlmProvider.AITUNNEL;
    }

    @Override
    protected Logger logger() {
        return log;
    }

    @Override
    protected WebClient buildWebClient() {
        String baseUrl = "https://api.aitunnel.ru/v1";
        String apiKey = null;
        Optional<IntegrationConfig> cfg = integrationConfigRepository
            .findByTypeAndIsDefaultTrue(IntegrationType.AITUNNEL);
        if (cfg.isPresent()) {
            IntegrationConfig c = cfg.get();
            if (c.getBaseUrl() != null && !c.getBaseUrl().isBlank()) baseUrl = c.getBaseUrl();
            apiKey = c.getToken();
        }
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("AITUNNEL_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("No AITunnel API key configured. Configure via /api/integrations (type=AITUNNEL) or set AITUNNEL_API_KEY env var.");
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
