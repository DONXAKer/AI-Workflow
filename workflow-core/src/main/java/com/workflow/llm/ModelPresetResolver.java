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

    private static final Map<String, String> OLLAMA_DEFAULTS = Map.of(
        // qwen2.5:7b emits zero tool_calls in agentic loops (empirical, FEAT-AP-002).
        // qwen3_cline_roocode:8b is a Cline-tuned qwen3 that reliably calls Read/
        // Write/Edit/Grep/Bash and handles structured JSON (incl. orchestrator schema).
        // Used as the all-around default for analysis/clarification/planner/impl.
        // For pure speed without tool-use, override per-block with agent.model.
        "smart",     Models.OLLAMA_CLINE_ROOCODE,
        "flash",     Models.OLLAMA_CLINE_ROOCODE,
        "fast",      Models.OLLAMA_CLINE_ROOCODE,
        "reasoning", Models.OLLAMA_CLINE_ROOCODE,
        "cheap",     Models.OLLAMA_CLINE_ROOCODE
    );

    private static final Map<String, String> VLLM_DEFAULTS = Map.of(
        // Single model serves all tiers: vLLM hosts one model per process and switching
        // (model swap) would require a daemon restart. Qwen3-4B-AWQ is the default
        // because it fits comfortably on RTX 4060 8 GB with 32k context, prefix-caching
        // and CUDA graphs all enabled — 8B AWQ crash-loops at startup on the same card
        // (see Models.VLLM_QWEN3_AWQ javadoc for details). Per-block override via
        // agent.model = "Qwen/Qwen3-8B-AWQ" routes a single block to the larger model
        // ONLY if the configured vLLM instance is actually serving it.
        "smart",     Models.VLLM_QWEN3_AWQ,
        "flash",     Models.VLLM_QWEN3_AWQ,
        "fast",      Models.VLLM_QWEN3_AWQ,
        "reasoning", Models.VLLM_QWEN3_AWQ,
        "cheap",     Models.VLLM_QWEN3_AWQ
    );

    private static final Map<String, String> CLI_DEFAULTS = Map.of(
        "smart",     Models.CLI_SONNET,
        "flash",     Models.CLI_HAIKU,
        "fast",      Models.CLI_HAIKU,
        "reasoning", Models.CLI_OPUS,
        "cheap",     Models.CLI_HAIKU
    );

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
        // Tier presets — primary semantic abstraction. Use these as `tier:` in
        // block AgentConfig: smart for analytical roles (analysis, verify, plan/review),
        // flash for executor roles (codegen, agent_with_tools impl).
        // Defaults match the model pair that performs best for the WarCard pipeline
        // (operator-validated, commit 836f307). Override per-tier in application.yaml
        // under workflow.model-presets if your stack prefers different models.
        // NOTE: Anthropic models are reserved for the CLI path (CLI_DEFAULTS) — they
        // never appear here, so OpenRouter routing always picks an Anthropic-free model.
        Map.entry("smart",        Models.OR_SMART),
        Map.entry("flash",        Models.OR_FLASH),
        // Legacy / extended presets
        Map.entry("fast",         Models.OR_FAST),
        Map.entry("reasoning",    Models.OR_REASONING),
        Map.entry("cheap",        Models.OR_CHEAP),
        Map.entry("deepseek",     Models.OR_DEEPSEEK),
        Map.entry("glm",          Models.OR_GLM),
        Map.entry("gemini-pro",   Models.OR_GEMINI_PRO),
        Map.entry("gemini-flash", Models.OR_GEMINI_FLASH),
        Map.entry("gpt4o",        Models.OR_GPT4O),
        Map.entry("mistral",      Models.OR_MISTRAL),
        Map.entry("qwen",         Models.OR_QWEN)
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

    /**
     * Resolves a preset or model name to a native Claude model identifier for use with the
     * Claude CLI ({@code claude -p --model ...}). OpenRouter vendor-prefixed names like
     * {@code anthropic/claude-sonnet-4-6} are stripped to {@code claude-sonnet-4-6}.
     *
     * <p>When a run pins provider=CLAUDE_CODE_CLI, blocks with a YAML-pinned non-Anthropic
     * model (e.g. {@code z-ai/glm-4.6}) would otherwise be sent to the CLI as a literal
     * unknown name — which the CLI rejects. To make the project-level provider switcher
     * actually usable, we fall back to {@link #CLI_DEFAULTS} {@code smart} in that case
     * (sonnet) with a warning, so analysis/orchestrator blocks keep working when switched
     * to CLI without per-block YAML edits.
     */
    public String resolveCli(String presetOrModel) {
        if (presetOrModel == null || presetOrModel.isBlank()) return CLI_DEFAULTS.get("smart");

        if (presetOrModel.contains("/")) {
            String stripped = presetOrModel.substring(presetOrModel.lastIndexOf('/') + 1);
            if (looksLikeClaudeModel(stripped)) return stripped;
            log.warn("Non-Anthropic model '{}' requested under CLI routing — falling back to '{}'",
                presetOrModel, CLI_DEFAULTS.get("smart"));
            return CLI_DEFAULTS.get("smart");
        }

        String lower = presetOrModel.toLowerCase();
        if (CLI_DEFAULTS.containsKey(lower)) return CLI_DEFAULTS.get(lower);

        log.debug("Unknown CLI preset '{}' — passing through as raw model name", presetOrModel);
        return presetOrModel;
    }

    private static boolean looksLikeClaudeModel(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.startsWith("claude") || n.equals("sonnet") || n.equals("opus") || n.equals("haiku");
    }

    /**
     * Resolves a preset or model name to a vLLM model identifier (HuggingFace repo id,
     * e.g. {@code Qwen/Qwen3-8B-AWQ}).
     *
     * <p>vLLM identifies served models by their HF repo id verbatim — there is no
     * Ollama-style {@code :tag} discriminator. A presetOrModel containing '/' is
     * assumed to already be a full HF repo id and passed through unchanged. Bare
     * preset names ({@code smart}, {@code flash}, …) map via {@link #VLLM_DEFAULTS}.
     *
     * <p>Per-block YAML overrides should pin the full HF repo id of a model the
     * configured vLLM instance actually serves; otherwise vLLM responds with HTTP
     * 404 and the call fails loud.
     */
    public String resolveVllm(String presetOrModel) {
        if (presetOrModel == null || presetOrModel.isBlank()) return VLLM_DEFAULTS.get("smart");
        // Full HF repo id ("Qwen/Qwen3-8B-AWQ") — pass through, vLLM matches on this verbatim.
        if (presetOrModel.contains("/")) return presetOrModel;
        String lower = presetOrModel.toLowerCase();
        if (VLLM_DEFAULTS.containsKey(lower)) return VLLM_DEFAULTS.get(lower);
        log.debug("Unknown vLLM preset '{}' — passing through as raw model id", presetOrModel);
        return presetOrModel;
    }

    /**
     * Resolves a preset or model name to an Ollama-native model tag (e.g. {@code qwen3:8b}).
     * Vendor-prefixed names like {@code z-ai/glm-4.6} are stripped to their last segment
     * so switching a project to Ollama doesn't require per-block YAML edits.
     */
    public String resolveOllama(String presetOrModel) {
        if (presetOrModel == null || presetOrModel.isBlank()) return OLLAMA_DEFAULTS.get("smart");
        // Disambiguate two `org/model` shapes:
        //   - OpenRouter: `z-ai/glm-4.6` (vendor/model, no tag) — strip vendor to map to Ollama
        //   - Ollama community: `mychen76/qwen3_cline_roocode:8b` (user/model:tag) — keep full name
        // The `:tag` after the model name is the Ollama discriminator (OpenRouter IDs never tag).
        if (presetOrModel.contains("/") && !presetOrModel.contains(":")) {
            return presetOrModel.substring(presetOrModel.lastIndexOf('/') + 1);
        }
        String lower = presetOrModel.toLowerCase();
        if (OLLAMA_DEFAULTS.containsKey(lower)) return OLLAMA_DEFAULTS.get(lower);
        log.debug("Unknown Ollama preset '{}' — passing through as raw model tag", presetOrModel);
        return presetOrModel;
    }

    public Map<String, String> allPresets() {
        Map<String, String> merged = new HashMap<>(DEFAULTS);
        if (overrides != null) merged.putAll(overrides);
        return merged;
    }
}
