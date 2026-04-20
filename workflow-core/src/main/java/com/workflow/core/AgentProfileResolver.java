package com.workflow.core;

import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.model.AgentProfile;
import com.workflow.model.AgentProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the effective AgentConfig and skills for a block by merging:
 * defaults → agent profile → block-level overrides.
 */
@Service
public class AgentProfileResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentProfileResolver.class);

    private static final String DEFAULT_MODEL = "claude-sonnet-4-6";
    private static final int DEFAULT_MAX_TOKENS = 8192;
    private static final double DEFAULT_TEMPERATURE = 1.0;

    @Autowired
    private AgentProfileRepository profileRepository;

    /**
     * Resolves the effective AgentConfig for a block.
     * Priority: block-level agent fields > profile fields > defaults.
     */
    public AgentConfig resolveAgent(BlockConfig blockConfig) {
        AgentConfig result = new AgentConfig();
        result.setModel(DEFAULT_MODEL);
        result.setMaxTokens(DEFAULT_MAX_TOKENS);
        result.setTemperature(DEFAULT_TEMPERATURE);

        AgentProfile profile = loadProfile(blockConfig.getProfile());

        // Layer 1: apply profile values
        if (profile != null) {
            if (profile.getModel() != null && !profile.getModel().isBlank()) {
                result.setModel(profile.getModel());
            }
            if (profile.getMaxTokens() != null) {
                result.setMaxTokens(profile.getMaxTokens());
            }
            if (profile.getTemperature() != null) {
                result.setTemperature(profile.getTemperature());
            }
            // Build system prompt from profile's rolePrompt + customPrompt
            String profilePrompt = buildProfilePrompt(profile);
            if (profilePrompt != null) {
                result.setSystemPrompt(profilePrompt);
            }
        }

        // Layer 2: apply block-level agent overrides
        AgentConfig blockAgent = blockConfig.getAgent();
        if (blockAgent != null) {
            if (blockAgent.getModel() != null && !blockAgent.getModel().isBlank()) {
                result.setModel(blockAgent.getModel());
            }
            if (blockAgent.getMaxTokens() != null) {
                result.setMaxTokens(blockAgent.getMaxTokens());
            }
            if (blockAgent.getTemperature() != null) {
                result.setTemperature(blockAgent.getTemperature());
            }
            // Block-level systemPrompt appends to (not replaces) profile prompt
            if (blockAgent.getSystemPrompt() != null && !blockAgent.getSystemPrompt().isBlank()) {
                String existing = result.getSystemPrompt();
                if (existing != null && !existing.isBlank()) {
                    result.setSystemPrompt(existing + "\n\n" + blockAgent.getSystemPrompt());
                } else {
                    result.setSystemPrompt(blockAgent.getSystemPrompt());
                }
            }
        }

        return result;
    }

    /**
     * Resolves the effective skills list: union of profile skills + block skills, deduplicated.
     */
    public List<String> resolveSkills(BlockConfig blockConfig) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();

        AgentProfile profile = loadProfile(blockConfig.getProfile());
        if (profile != null) {
            merged.addAll(profile.getSkillNames());
        }

        if (blockConfig.getSkills() != null) {
            merged.addAll(blockConfig.getSkills());
        }

        return new ArrayList<>(merged);
    }

    private AgentProfile loadProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) return null;
        Optional<AgentProfile> opt = profileRepository.findByName(profileName);
        if (opt.isEmpty()) {
            log.warn("Agent profile '{}' not found — using defaults", profileName);
            return null;
        }
        return opt.get();
    }

    private String buildProfilePrompt(AgentProfile profile) {
        StringBuilder sb = new StringBuilder();
        if (profile.getRolePrompt() != null && !profile.getRolePrompt().isBlank()) {
            sb.append(profile.getRolePrompt());
        }
        if (profile.getCustomPrompt() != null && !profile.getCustomPrompt().isBlank()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(profile.getCustomPrompt());
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
