package com.workflow.preflight;

import com.workflow.config.IntegrationsConfig;
import com.workflow.llm.LlmProvider;

/**
 * Read-only snapshot of run-start state handed to {@link com.workflow.blocks.Block#preflightRequirements}
 * and {@link RequirementChecker}. Built by {@link com.workflow.api.RunController} <em>before</em> a
 * {@link com.workflow.core.PipelineRun} is created — so it deliberately does not carry a run entity.
 *
 * @param workingDir   effective run working directory (provisioned workspace or {@code Project.workingDir});
 *                     may be {@code null} when the project has none configured
 * @param provider     run-level resolved {@link LlmProvider} (from run inputs → project default → OpenRouter)
 * @param projectSlug  current project scope (mirrors {@link com.workflow.project.ProjectContext#get()})
 * @param integrations pipeline {@code integrations:} section — supplies the integration <em>name</em>
 *                     used as the primary lookup key, matching {@code PipelineRunner.resolveIntegration}
 */
public record PreflightContext(
        String workingDir,
        LlmProvider provider,
        String projectSlug,
        IntegrationsConfig integrations
) {
    /** Integration name configured for {@code type} in the pipeline {@code integrations:} block, or {@code null}. */
    public String integrationName(com.workflow.model.IntegrationType type) {
        if (integrations == null) return null;
        return switch (type) {
            case YOUTRACK -> integrations.getYoutrack();
            case GITHUB -> integrations.getGithub();
            case GITLAB -> integrations.getGitlab();
            default -> null;
        };
    }
}
