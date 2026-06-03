package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.preflight.PreflightContext;
import com.workflow.preflight.Requirement;

import java.util.List;
import java.util.Map;

public interface Block {
    String getName();
    String getDescription();
    Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception;

    /**
     * Environment prerequisites this block instance needs to run without dying mid-pipeline
     * (working dir, binaries, LLM provider, integration tokens, a git checkout). Verified by
     * {@link com.workflow.preflight.RunDiagnostics} as a synchronous gate on {@code POST /api/runs}
     * — before any run is created — so a misconfigured environment is reported up-front instead of
     * failing on the block at execution time.
     *
     * <p>The default is empty (no external prerequisites). Blocks read their own {@code config} to
     * derive instance-specific requirements (e.g. {@code shell_exec} extracts the binary from its
     * {@code command:}). The {@code context} carries run-level state (resolved provider, working dir,
     * integration names). Requirements are deduped across blocks by {@link Requirement#dedupKey()}.
     */
    default List<Requirement> preflightRequirements(BlockConfig config, PreflightContext context) {
        return List.of();
    }

    /**
     * UI-editor metadata for this block type. The Pipeline Editor calls
     * {@code GET /api/blocks/registry} which returns this map for every registered
     * block. Default implementation provides label = block name, category = "general",
     * empty schema, and {@code hasCustomForm=false} — UI falls back to a raw-JSON
     * editor for the block's {@code config} map.
     *
     * <p>Top-level blocks (referenced by {@code feature.yaml} / commonly used) override
     * this with proper {@link FieldSchema} entries describing their config keys.
     */
    default BlockMetadata getMetadata() {
        return BlockMetadata.defaultFor(getName());
    }

    /**
     * Whether outputs of this block instance may be reused across runs via the block
     * cache (see {@link com.workflow.core.BlockCacheService}). Default: not cacheable —
     * blocks with FS side-effects (Write/Bash), external API calls, or interactive
     * input MUST keep this false. Pure analytical blocks (analysis, planning) override
     * to true.
     *
     * <p>The {@code BlockConfig} parameter lets a block decide based on its own config —
     * e.g. {@link OrchestratorBlock} caches only mode=plan and never mode=review.
     */
    default boolean isCacheable(BlockConfig config) {
        return false;
    }
}
