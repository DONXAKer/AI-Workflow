package com.workflow.llm;

/**
 * Single source of truth for every model identifier used in the platform.
 *
 * <p>Anyone adding a new model — please put the literal here first, then reference the
 * constant from {@link ModelPresetResolver}, {@link LlmClient}, blocks, or tests.
 * Grep this file to find every place a particular model is wired.
 *
 * <p>Per-block YAML overrides (e.g. {@code agent.model: "mychen76/qwen3_cline_roocode:8b"})
 * still inline string literals — they are project configuration, not platform code.
 *
 * <p>Naming convention: {@code <PROVIDER>_<FAMILY>_<VARIANT>}. Family detection helpers
 * (used by {@link LlmClient} / {@link com.workflow.blocks.OrchestratorBlock} to switch
 * behaviour per model line) live at the bottom under {@code FAMILY_*}.
 */
public final class Models {
    private Models() {}

    // ── Ollama (local) ─────────────────────────────────────────────────────
    /** Cline/Roocode-tuned qwen3 — all-around default for Ollama agentic loops. */
    public static final String OLLAMA_CLINE_ROOCODE = "mychen76/qwen3_cline_roocode:8b";
    /** Embedding model for Qdrant RAG. Must match what the project index was built with. */
    public static final String OLLAMA_EMBED_NOMIC = "nomic-embed-text:v1.5";
    /** Hard fallback when no preset resolver is wired (defensive). */
    public static final String OLLAMA_FALLBACK = OLLAMA_CLINE_ROOCODE;

    // ── Claude Code CLI (Anthropic via local CLI) ──────────────────────────
    public static final String CLI_SONNET   = "claude-sonnet-4-6";
    public static final String CLI_HAIKU    = "claude-haiku-4-5";
    public static final String CLI_OPUS     = "claude-opus-4-7";
    public static final String CLI_FALLBACK = CLI_SONNET;

    // ── OpenRouter tier defaults (operator-validated for WarCard pipeline) ─
    public static final String OR_SMART     = "z-ai/glm-4.6";
    public static final String OR_FLASH     = "z-ai/glm-4.7-flash";
    public static final String OR_FAST      = "google/gemini-2.5-flash-lite";
    public static final String OR_REASONING = "google/gemini-2.5-pro";
    public static final String OR_CHEAP     = "openai/gpt-4o-mini";
    public static final String OR_FALLBACK  = OR_SMART;

    // ── OpenRouter extras (explicit pin via preset name) ───────────────────
    public static final String OR_DEEPSEEK     = "deepseek/deepseek-chat-v3-0324";
    public static final String OR_GLM          = "z-ai/glm-5.1";
    public static final String OR_GEMINI_PRO   = "google/gemini-2.5-pro";
    public static final String OR_GEMINI_FLASH = "google/gemini-2.0-flash-001";
    public static final String OR_GPT4O        = "openai/gpt-4o";
    public static final String OR_MISTRAL      = "mistralai/mistral-large-2411";
    public static final String OR_QWEN         = "qwen/qwen-2.5-72b-instruct";

    // ── Model family prefixes (used by family-detection helpers) ───────────
    /** Generic qwen3 family — supports {@code think:false} for low-latency tool calls. */
    public static final String FAMILY_QWEN3 = "qwen3";
    /**
     * qwen3.6 specifically — empirically requires thinking enabled (returns empty
     * content under {@code think:false}) and only emits structured JSON when
     * {@code response_format: json_object} is forced.
     */
    public static final String FAMILY_QWEN36 = "qwen3.6";
}
