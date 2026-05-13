package com.workflow.llm;

/**
 * Where an LLM call physically goes.
 *
 * <p>{@link #OPENROUTER} — paid OpenAI-compatible HTTP API, billed against the user's
 * OpenRouter credits. Multi-iteration tool-use loop is driven by {@link LlmClient}
 * itself (each iteration emits one {@link LlmCall} row).
 *
 * <p>{@link #CLAUDE_CODE_CLI} — local {@code claude -p} subprocess (Layer 3), billed
 * against the user's Anthropic Max subscription. The multi-iteration loop happens
 * inside the CLI, so we record one synthetic {@link LlmCall} row per block run.
 */
public enum LlmProvider {
    OPENROUTER,
    CLAUDE_CODE_CLI,
    OLLAMA,
    /** AITunnel.ru — OpenAI-compatible Russian aggregator. Same call shape as OpenRouter,
     * different baseUrl + token. Useful when OpenRouter is geoblocked or operator prefers
     * a domestic provider. */
    AITUNNEL,
    /** Local vLLM server — OpenAI-compatible. Hosts AWQ/FP8/NVFP4 quants natively on
     * NVIDIA GPUs; significantly faster than Ollama on the same hardware due to FP8
     * Tensor Cores (Ada+) and Blackwell NVFP4 paths. Coexists with OLLAMA — embeddings
     * stay on Ollama (no benefit from migrating the 137M-param nomic-embed). */
    VLLM
}
