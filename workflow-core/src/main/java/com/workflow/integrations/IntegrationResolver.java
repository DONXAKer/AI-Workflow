package com.workflow.integrations;

import com.workflow.llm.LlmProvider;
import com.workflow.model.IntegrationConfig;
import com.workflow.model.IntegrationConfigRepository;
import com.workflow.model.IntegrationType;
import com.workflow.project.ProjectContext;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Single source of truth for resolving integration credentials and LLM-provider readiness
 * from the {@link IntegrationConfig} store. Both the runtime ({@code PipelineRunner}) and the
 * pre-run diagnostic ({@code com.workflow.preflight}) call into here, so a pre-run check reads
 * the <em>exact same</em> source a block will read at execution time — no drift between
 * "diagnostic said OK" and "block found no credentials".
 *
 * <p>All lookups are account-scoped automatically by the Hibernate {@code accountFilter} on
 * {@link IntegrationConfig}; project scope comes from {@link ProjectContext}.
 */
@Component
public class IntegrationResolver {

    private final IntegrationConfigRepository repository;

    public IntegrationResolver(IntegrationConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * Resolve an integration the way the runtime does: explicit {@code configName} (project-scoped,
     * then cross-project legacy) → project default of {@code type} → global default of {@code type}.
     *
     * <p>Extracted verbatim from {@code PipelineRunner.resolveIntegration} so both paths stay in lockstep.
     */
    public Optional<IntegrationConfig> resolve(String configName, IntegrationType type) {
        String scope = ProjectContext.get();
        if (configName != null && !configName.isBlank()) {
            Optional<IntegrationConfig> scoped = repository.findByNameAndProjectSlug(configName, scope);
            if (scoped.isPresent()) return scoped;
            // Fall back to cross-project lookup for legacy configs without a project slug set.
            return repository.findByName(configName);
        }
        Optional<IntegrationConfig> scoped = repository.findByTypeAndIsDefaultTrueAndProjectSlug(type, scope);
        if (scoped.isPresent()) return scoped;
        return repository.findFirstByTypeAndIsDefaultTrue(type);
    }

    /** True iff the integration resolves and carries a non-blank token. */
    public boolean hasToken(String configName, IntegrationType type) {
        return resolve(configName, type)
                .map(IntegrationConfig::getToken)
                .filter(t -> t != null && !t.isBlank())
                .isPresent();
    }

    // ------------------------------------------------------------------
    // LLM provider readiness — mirrors the provider clients' buildWebClient()
    // resolution (IntegrationConfig default token → env var fallback).
    // ------------------------------------------------------------------

    /**
     * Static metadata per provider so the diagnostic resolves credentials and the L1 probe URL
     * the same way the provider client does at runtime.
     *
     * @param type           {@link IntegrationType} the provider's default row carries
     * @param envVar         env-var credential fallback (null for keyless local providers)
     * @param requiresToken  whether a token is required at all (false for OLLAMA/VLLM local servers)
     * @param defaultBaseUrl base URL used when no {@link IntegrationConfig} overrides it
     */
    public record ProviderMeta(IntegrationType type, String envVar, boolean requiresToken, String defaultBaseUrl) {}

    public ProviderMeta providerMeta(LlmProvider provider) {
        return switch (provider) {
            case OPENROUTER -> new ProviderMeta(IntegrationType.OPENROUTER, "OPENROUTER_API_KEY", true,
                    "https://openrouter.ai/api/v1");
            case AITUNNEL -> new ProviderMeta(IntegrationType.AITUNNEL, "AITUNNEL_API_KEY", true,
                    "https://api.aitunnel.ru/v1");
            case ALLTOKENS -> new ProviderMeta(IntegrationType.ALLTOKENS, "ALLTOKENS_API_KEY", true,
                    "https://api.alltokens.ru/api/v1");
            case OLLAMA -> new ProviderMeta(IntegrationType.OLLAMA, null, false,
                    "http://localhost:11434/v1");
            case VLLM -> new ProviderMeta(IntegrationType.VLLM, null, false,
                    "http://host.docker.internal:8003/v1");
            case CLAUDE_CODE_CLI -> new ProviderMeta(IntegrationType.CLAUDE_CODE_CLI, "CLAUDE_BIN", false, null);
        };
    }

    /**
     * Whether the provider has usable credentials: keyless local providers (Ollama/vLLM) are always
     * "configured"; CLI readiness is a binary check handled elsewhere; HTTP providers need a non-blank
     * token in their default {@link IntegrationConfig} row OR the env-var fallback.
     */
    public boolean isProviderConfigured(LlmProvider provider) {
        ProviderMeta meta = providerMeta(provider);
        if (!meta.requiresToken()) return true;
        Optional<IntegrationConfig> cfg = repository.findFirstByTypeAndIsDefaultTrue(meta.type());
        if (cfg.isPresent()) {
            String token = cfg.get().getToken();
            if (token != null && !token.isBlank()) return true;
        }
        String env = meta.envVar() != null ? System.getenv(meta.envVar()) : null;
        return env != null && !env.isBlank();
    }

    /** Effective base URL for the provider — DB override wins, else the documented default. */
    public String providerBaseUrl(LlmProvider provider) {
        ProviderMeta meta = providerMeta(provider);
        Optional<IntegrationConfig> cfg = repository.findFirstByTypeAndIsDefaultTrue(meta.type());
        if (cfg.isPresent()) {
            String base = cfg.get().getBaseUrl();
            if (base != null && !base.isBlank()) return base;
        }
        return meta.defaultBaseUrl();
    }

    /**
     * The {@code claude} CLI binary name/path, resolved the same way as
     * {@code ClaudeCliProviderClient.getCliBin()}: {@code CLAUDE_BIN} env → CLI integration baseUrl → {@code claude}.
     */
    public String cliBinary() {
        String envBin = System.getenv("CLAUDE_BIN");
        if (envBin != null && !envBin.isBlank()) return envBin;
        return repository.findFirstByTypeAndIsDefaultTrue(IntegrationType.CLAUDE_CODE_CLI)
                .map(IntegrationConfig::getBaseUrl)
                .filter(s -> s != null && !s.isBlank())
                .orElse("claude");
    }
}
