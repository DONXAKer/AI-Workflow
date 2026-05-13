package com.workflow.llm.provider;

import com.workflow.llm.LlmCallContext;
import com.workflow.llm.LlmProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picks the right {@link LlmProviderClient} for the current call. Sole place that
 * reads {@link LlmCallContext}'s {@code preferredProvider} ThreadLocal — every
 * provider implementation stays oblivious to routing concerns.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>If {@code preferredProvider} is pinned (set from {@code Project.defaultProvider}
 *       around each block by {@code PipelineRunner}), return the matching provider.
 *   <li>Otherwise, give {@link ClaudeCliProviderClient#canHandle(String)} a chance —
 *       it routes anthropic/-prefixed and bare-CLI names through the CLI when the
 *       integration is configured.
 *   <li>Fall back to OpenRouter (the default cloud route).
 * </ol>
 *
 * <p>Adding a new provider: implement {@link LlmProviderClient}, mark it {@code @Service},
 * Spring autowires it into {@code byType} automatically. To make it selectable, ensure
 * the new {@link LlmProvider} enum value is set on {@code Project.defaultProvider} or
 * via {@code LlmCallContext.set}.
 */
@Service
public class LlmProviderRouter {

    private final Map<LlmProvider, LlmProviderClient> byType;
    private final OpenRouterProviderClient defaultClient;
    private final ClaudeCliProviderClient cliClient;

    @Autowired
    public LlmProviderRouter(List<LlmProviderClient> clients,
                             OpenRouterProviderClient defaultClient,
                             ClaudeCliProviderClient cliClient) {
        this.byType = clients.stream()
            .collect(Collectors.toUnmodifiableMap(
                LlmProviderClient::providerType, Function.identity()));
        this.defaultClient = defaultClient;
        this.cliClient = cliClient;
    }

    /** Returns the provider client to use for a call with the given model name. */
    public LlmProviderClient route(String model) {
        LlmProvider preferred = LlmCallContext.current()
            .map(LlmCallContext.Context::preferredProvider)
            .orElse(null);

        if (preferred != null) {
            LlmProviderClient explicit = byType.get(preferred);
            if (explicit != null) return explicit;
        }

        // No ThreadLocal pin — only the CLI uses model-prefix heuristic (anthropic/,
        // bare claude-*) gated on its integration being configured.
        if (cliClient.canHandle(model)) return cliClient;

        return defaultClient;
    }
}
