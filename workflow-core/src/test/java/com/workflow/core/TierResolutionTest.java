package com.workflow.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.config.DefaultsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers Phase A of the smart-checklist design:
 * — {@link AgentConfig#getEffectiveModel()} — model wins over tier within a layer
 * — {@link AgentProfileResolver} — block layer overrides profile/defaults; tier and
 *   model interchangeable per layer
 * — YAML round-trip preserves the {@code tier} field
 */
class TierResolutionTest {

    private static AgentProfileResolver resolverWithoutProfiles() {
        AgentProfileResolver r = new AgentProfileResolver();
        // No profile repository wired — loadProfile() returns null for any name
        ReflectionTestUtils.setField(r, "profileRepository", null);
        return r;
    }

    // ------- AgentConfig.getEffectiveModel -------

    @Test
    void effectiveModel_modelWinsOverTier() {
        AgentConfig a = new AgentConfig();
        a.setModel("anthropic/claude-opus-4-7");
        a.setTier("smart");
        assertEquals("anthropic/claude-opus-4-7", a.getEffectiveModel());
    }

    @Test
    void effectiveModel_tierUsedWhenModelAbsent() {
        AgentConfig a = new AgentConfig();
        a.setTier("smart");
        assertEquals("smart", a.getEffectiveModel());
    }

    @Test
    void effectiveModel_blankModelTreatedAsAbsent() {
        AgentConfig a = new AgentConfig();
        a.setModel("   ");
        a.setTier("flash");
        assertEquals("flash", a.getEffectiveModel());
    }

    @Test
    void effectiveModel_nullWhenNeitherSet() {
        assertNull(new AgentConfig().getEffectiveModel());
    }

    // ------- AgentProfileResolver: layered tier/model merge -------

    @Test
    void blockTier_resolvesAsModel() {
        AgentProfileResolver r = resolverWithoutProfiles();
        BlockConfig block = new BlockConfig();
        AgentConfig agent = new AgentConfig();
        agent.setTier("smart");
        block.setAgent(agent);

        AgentConfig effective = r.resolveAgent(block, null);
        assertEquals("smart", effective.getModel());
    }

    @Test
    void blockModel_winsOverBlockTier() {
        AgentProfileResolver r = resolverWithoutProfiles();
        BlockConfig block = new BlockConfig();
        AgentConfig agent = new AgentConfig();
        agent.setModel("anthropic/claude-opus-4-7");
        agent.setTier("smart");
        block.setAgent(agent);

        AgentConfig effective = r.resolveAgent(block, null);
        assertEquals("anthropic/claude-opus-4-7", effective.getModel());
    }

    @Test
    void blockTier_overridesPipelineDefaultModel() {
        AgentProfileResolver r = resolverWithoutProfiles();

        DefaultsConfig defaults = new DefaultsConfig();
        AgentConfig defaultAgent = new AgentConfig();
        defaultAgent.setModel("flash");
        defaults.setAgent(defaultAgent);

        BlockConfig block = new BlockConfig();
        AgentConfig blockAgent = new AgentConfig();
        blockAgent.setTier("smart");
        block.setAgent(blockAgent);

        AgentConfig effective = r.resolveAgent(block, defaults);
        assertEquals("smart", effective.getModel(),
            "block-level tier must override pipeline-default model");
    }

    @Test
    void pipelineDefaultTier_appliedWhenBlockHasNoAgent() {
        AgentProfileResolver r = resolverWithoutProfiles();

        DefaultsConfig defaults = new DefaultsConfig();
        AgentConfig defaultAgent = new AgentConfig();
        defaultAgent.setTier("flash");
        defaults.setAgent(defaultAgent);

        BlockConfig block = new BlockConfig();  // no agent override

        AgentConfig effective = r.resolveAgent(block, defaults);
        assertEquals("flash", effective.getModel());
    }

    @Test
    void noAgentNoDefaults_fallsBackToHardcodedDefault() {
        AgentProfileResolver r = resolverWithoutProfiles();
        AgentConfig effective = r.resolveAgent(new BlockConfig(), null);
        // The hardcoded DEFAULT_MODEL constant — not asserting exact value
        // (it can change), just that something non-null and non-blank is set.
        assertNotNull(effective.getModel());
        assertFalse(effective.getModel().isBlank());
    }

    // ------- YAML round-trip preserves tier -------

    @Test
    void yamlRoundTrip_preservesTier() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

        String yaml = """
            model: anthropic/claude-opus-4-7
            tier: smart
            maxTokens: 4096
            """;

        AgentConfig parsed = mapper.readValue(yaml, AgentConfig.class);
        assertEquals("anthropic/claude-opus-4-7", parsed.getModel());
        assertEquals("smart", parsed.getTier());

        String serialized = mapper.writeValueAsString(parsed);
        AgentConfig reparsed = mapper.readValue(serialized, AgentConfig.class);
        assertEquals("anthropic/claude-opus-4-7", reparsed.getModel(),
            "round-trip lost model. Serialized:\n" + serialized);
        assertEquals("smart", reparsed.getTier(),
            "round-trip lost tier. Serialized:\n" + serialized);
    }

    @Test
    void yamlRoundTrip_tierOnlyNoModel() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

        AgentConfig parsed = mapper.readValue("tier: flash\n", AgentConfig.class);
        assertNull(parsed.getModel());
        assertEquals("flash", parsed.getTier());
        assertEquals("flash", parsed.getEffectiveModel());
    }
}
