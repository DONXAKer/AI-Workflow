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
    CLAUDE_CODE_CLI
}
