package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;

import java.util.Map;

public interface Block {
    String getName();
    String getDescription();
    Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception;

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
}
