package com.workflow.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a preset name (e.g. {@code fast}, {@code smart}, {@code cheap}) to a concrete
 * OpenRouter model identifier (e.g. {@code anthropic/claude-opus-4-7}). Skills and blocks
 * reference presets; the actual model is configured centrally so a single swap updates
 * every downstream caller.
 *
 * <p>Defaults follow the design agreed in memory (OpenRouter routing, fast/smart/cheap).
 * Overrides can be provided via {@code workflow.model-presets.*} properties:
 * <pre>
 * workflow:
 *   model-presets:
 *     smart: anthropic/claude-opus-4-7
 *     fast: anthropic/claude-haiku-4-5
 *     cheap: openai/gpt-4o-mini
 * </pre>
 *
 * <p>Strings that already look like full model identifiers (contain a '/') are passed
 * through unchanged — so existing configs with literal model names continue to work.
 */
@Service
public class ModelPresetResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelPresetResolver.class);

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
        // Tier presets — primary semantic abstraction. Use these as `tier:` in
        // block AgentConfig: smart for analytical roles (analysis, verify, plan/review),
        // flash for executor roles (codegen, agent_with_tools impl).
        // Defaults match the model pair that performs best for the WarCard pipeline
        // (operator-validated). Override per-tier in application.yaml under
        // workflow.model-presets if your stack prefers different models.
        Map.entry("smart",        "z-ai/glm-4.6"),
        Map.entry("flash",        "z-ai/glm-4.7-flash"),
        // Legacy / extended presets
        Map.entry("fast",         "anthropic/claude-haiku-4-5"),
        Map.entry("reasoning",    "anthropic/claude-opus-4-7"),
        Map.entry("cheap",        "openai/gpt-4o-mini"),
        Map.entry("deepseek",     "deepseek/deepseek-chat-v3-0324"),
        Map.entry("glm",          "z-ai/glm-5.1"),
        Map.entry("gemini-pro",   "google/gemini-2.5-pro"),
        Map.entry("gemini-flash", "google/gemini-2.0-flash-001"),
        Map.entry("gpt4o",        "openai/gpt-4o"),
        Map.entry("mistral",      "mistralai/mistral-large-2411"),
        Map.entry("qwen",         "qwen/qwen-2.5-72b-instruct")
    );

    @Value("#{${workflow.model-presets:{:}}}")
    private Map<String, String> overrides = new HashMap<>();

    public String resolve(String presetOrModel) {
        if (presetOrModel == null || presetOrModel.isBlank()) return DEFAULTS.get("smart");

        // Pass-through if it already looks like a vendor/model identifier.
        if (presetOrModel.contains("/")) return presetOrModel;

        String lower = presetOrModel.toLowerCase();
        if (overrides != null && overrides.containsKey(lower)) return overrides.get(lower);
        if (DEFAULTS.containsKey(lower)) return DEFAULTS.get(lower);

        // Not a known preset — assume it's a raw Anthropic model name (backward compat).
        log.debug("Unknown preset '{}' — passing through as raw model name", presetOrModel);
        return presetOrModel;
    }

    public Map<String, String> allPresets() {
        Map<String, String> merged = new HashMap<>(DEFAULTS);
        if (overrides != null) merged.putAll(overrides);
        return merged;
    }
}
