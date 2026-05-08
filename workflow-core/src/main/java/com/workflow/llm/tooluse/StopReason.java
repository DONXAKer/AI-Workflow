package com.workflow.llm.tooluse;

/**
 * Why the tool-use loop terminated.
 */
public enum StopReason {
    /** Provider signaled end_turn — LLM has no more tool calls to make. */
    END_TURN,
    /** Provider hit max_tokens on a single response. */
    MAX_TOKENS,
    /** Hit {@code maxIterations} configured on the request. */
    MAX_ITERATIONS,
    /** Cumulative cost exceeded {@code budgetUsdCap}. */
    BUDGET_EXCEEDED,
    /** Executor threw / loop aborted externally. */
    ERROR,
    /** Agent wrote the configured {@code completionSignal} string in its text response. */
    COMPLETION_SIGNAL
}
