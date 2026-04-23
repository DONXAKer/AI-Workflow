package com.workflow.config;

import java.util.List;

public class PipelineConfigSettingsRequest {

    private DefaultsConfig defaults;
    private List<BlockSettingDto> blocks;

    public PipelineConfigSettingsRequest() {}

    public DefaultsConfig getDefaults() { return defaults; }
    public void setDefaults(DefaultsConfig defaults) { this.defaults = defaults; }

    public List<BlockSettingDto> getBlocks() { return blocks; }
    public void setBlocks(List<BlockSettingDto> blocks) { this.blocks = blocks; }
}
