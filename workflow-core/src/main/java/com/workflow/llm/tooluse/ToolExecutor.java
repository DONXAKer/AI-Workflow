package com.workflow.llm.tooluse;

/**
 * Strategy for running a {@link ToolCall} and producing a {@link ToolResult}.
 *
 * <p>Blocks supply their own {@code ToolExecutor} that knows how to route tool calls to
 * the registered {@link com.workflow.skills.Skill} instances, apply path-scope
 * validation, enforce bash allow-lists, and record audit entries. The {@link
 * com.workflow.llm.LlmClient} itself knows nothing about skills — it only sees tool
 * definitions going out and tool results coming back.
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * Execute the given call. Must not throw for expected failures — return a {@link
     * ToolResult} with {@code isError=true} instead, so the LLM can react. Exceptions
     * are reserved for unexpected infrastructure failures (e.g. executor was shut down).
     */
    ToolResult execute(ToolCall call) throws Exception;
}
