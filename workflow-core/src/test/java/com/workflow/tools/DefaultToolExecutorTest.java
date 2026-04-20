package com.workflow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.llm.tooluse.ToolCall;
import com.workflow.llm.tooluse.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultToolExecutorTest {

    private final ObjectMapper om = new ObjectMapper();

    private ToolRegistry registryOf(Tool... tools) {
        return new ToolRegistry(List.of(tools));
    }

    private ToolCall call(String toolName, ObjectNode input) {
        return new ToolCall("call-1", toolName, input);
    }

    @Test
    void routesToMatchingTool(@TempDir Path wd) throws Exception {
        Files.writeString(wd.resolve("hi.txt"), "content");
        DefaultToolExecutor exec = new DefaultToolExecutor(
            registryOf(new ReadTool()), ToolContext.of(wd));

        ToolResult r = exec.execute(call("Read", om.createObjectNode().put("file_path", "hi.txt")));

        assertFalse(r.isError(), "read should succeed, got: " + r.content());
        assertTrue(r.content().contains("content"));
        assertEquals("call-1", r.toolUseId());
    }

    @Test
    void unknownToolReturnsError(@TempDir Path wd) {
        DefaultToolExecutor exec = new DefaultToolExecutor(
            registryOf(new ReadTool()), ToolContext.of(wd));

        ToolResult r = exec.execute(call("Nope", om.createObjectNode()));

        assertTrue(r.isError());
        assertTrue(r.content().contains("unknown tool"));
        assertTrue(r.content().contains("Read"));
    }

    @Test
    void toolInvocationExceptionBecomesErrorResult(@TempDir Path wd) {
        DefaultToolExecutor exec = new DefaultToolExecutor(
            registryOf(new ReadTool()), ToolContext.of(wd));

        ToolResult r = exec.execute(call("Read",
            om.createObjectNode().put("file_path", "missing.txt")));

        assertTrue(r.isError());
        assertTrue(r.content().contains("not found"));
    }

    @Test
    void uncheckedExceptionBecomesErrorWithoutStackTrace(@TempDir Path wd) {
        Tool crashing = new Tool() {
            public String name() { return "Boom"; }
            public String description() { return "crashes"; }
            public ObjectNode inputSchema(ObjectMapper om) { return om.createObjectNode(); }
            public String execute(ToolContext c, JsonNode in) {
                throw new RuntimeException("internal explosion");
            }
        };
        DefaultToolExecutor exec = new DefaultToolExecutor(
            registryOf(crashing), ToolContext.of(wd));

        ToolResult r = exec.execute(call("Boom", om.createObjectNode()));

        assertTrue(r.isError());
        assertTrue(r.content().contains("Boom"));
        assertTrue(r.content().contains("RuntimeException"));
        assertFalse(r.content().contains("at com.workflow"),
            "stack trace must not leak into tool_result");
    }

    @Test
    void nullToolContentCoerced(@TempDir Path wd) {
        Tool nullReturner = new Tool() {
            public String name() { return "Null"; }
            public String description() { return "returns null"; }
            public ObjectNode inputSchema(ObjectMapper om) { return om.createObjectNode(); }
            public String execute(ToolContext c, JsonNode in) { return null; }
        };
        DefaultToolExecutor exec = new DefaultToolExecutor(
            registryOf(nullReturner), ToolContext.of(wd));

        ToolResult r = exec.execute(call("Null", om.createObjectNode()));

        assertFalse(r.isError());
        assertEquals("", r.content());
    }
}
