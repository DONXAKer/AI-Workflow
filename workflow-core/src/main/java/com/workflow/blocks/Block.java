package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;

import java.util.Map;

public interface Block {
    String getName();
    String getDescription();
    Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception;
}
