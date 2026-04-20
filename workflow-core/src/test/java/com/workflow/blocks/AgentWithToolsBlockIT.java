package com.workflow.blocks;

import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.llm.LlmCall;
import com.workflow.llm.LlmCallRepository;
import com.workflow.tools.ToolCallAudit;
import com.workflow.tools.ToolCallAuditRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: {@code agent_with_tools} block drives an LLM tool-use loop against live
 * OpenRouter with real {@link com.workflow.tools.ReadTool} and
 * {@link com.workflow.tools.WriteTool} scoped to a temp working directory. Proves the
 * whole M2 stack — block → LlmClient → DefaultToolExecutor → Tool → audit — works
 * together.
 *
 * <p>Skipped when {@code OPENROUTER_API_KEY} is unset. In-memory H2 so assertions on
 * {@link LlmCallRepository} and {@link ToolCallAuditRepository} start from a clean slate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:agent-it;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "workflow.mode=cli"
})
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
@Tag("integration")
class AgentWithToolsBlockIT {

    @Autowired private AgentWithToolsBlock block;
    @Autowired private LlmCallRepository llmCallRepository;
    @Autowired private ToolCallAuditRepository toolCallAuditRepository;

    @Test
    void readsInputAndWritesOutput(@TempDir Path wd) throws Exception {
        Files.writeString(wd.resolve("greeting.txt"), "Hello");

        BlockConfig cfg = new BlockConfig();
        cfg.setId("agent-it");
        cfg.setBlock("agent_with_tools");
        AgentConfig agent = new AgentConfig();
        agent.setModel("anthropic/claude-haiku-4-5");
        agent.setSystemPrompt(
            "You are a precise file-manipulation agent. Use the provided tools to "
                + "complete the user's request exactly. When the task is done, reply "
                + "with a single short sentence confirming completion and end your turn.");
        agent.setMaxTokens(1024);
        agent.setTemperature(0.0);
        cfg.setAgent(agent);

        Map<String, Object> config = new HashMap<>();
        config.put("working_dir", wd.toAbsolutePath().toString());
        config.put("user_message",
            "1. Use the Read tool to read 'greeting.txt'. "
                + "2. Use the Write tool to create 'farewell.txt' whose contents are "
                + "the greeting from step 1 followed by ', Goodbye.' — so if the file "
                + "contained 'Hello', farewell.txt must contain exactly 'Hello, Goodbye.'.");
        config.put("allowed_tools", List.of("Read", "Write"));
        config.put("max_iterations", 8);
        config.put("budget_usd_cap", 0.5);
        cfg.setConfig(config);

        long llmCallsBefore = llmCallRepository.count();
        long toolCallsBefore = toolCallAuditRepository.count();

        Map<String, Object> out = block.run(Map.of(), cfg, new PipelineRun());

        assertEquals("END_TURN", out.get("stop_reason"),
            () -> "expected END_TURN, got " + out.get("stop_reason")
                + " — tool_calls_made=" + out.get("tool_calls_made"));
        @SuppressWarnings("unchecked")
        List<String> toolsCalled = (List<String>) out.get("tool_calls_made");
        assertTrue(toolsCalled.contains("Read"), "agent should have used Read, got: " + toolsCalled);
        assertTrue(toolsCalled.contains("Write"), "agent should have used Write, got: " + toolsCalled);

        Path farewell = wd.resolve("farewell.txt");
        assertTrue(Files.exists(farewell), "farewell.txt should have been created");
        String actual = Files.readString(farewell).trim();
        assertTrue(actual.contains("Hello") && actual.contains("Goodbye"),
            "farewell.txt should contain 'Hello' and 'Goodbye', got: '" + actual + "'");

        assertTrue(llmCallRepository.count() > llmCallsBefore,
            "LlmCall rows should have been persisted");
        List<LlmCall> llmIterRows = llmCallRepository.findAll().stream()
            .filter(c -> c.getIteration() >= 1)
            .toList();
        assertFalse(llmIterRows.isEmpty(), "expected LlmCall rows from the tool-use loop");

        assertTrue(toolCallAuditRepository.count() > toolCallsBefore,
            "ToolCallAudit rows should have been persisted");
        List<ToolCallAudit> audits = toolCallAuditRepository.findAll();
        assertTrue(audits.stream().anyMatch(a -> "Read".equals(a.getToolName())),
            "audit should include a Read invocation");
        assertTrue(audits.stream().anyMatch(a -> "Write".equals(a.getToolName())),
            "audit should include a Write invocation");
        audits.forEach(a -> {
            assertNotNull(a.getInputJson(), "audit row should capture inputJson");
            assertNotNull(a.getOutputText(), "audit row should capture outputText");
            assertTrue(a.getIteration() != null && a.getIteration() >= 1,
                "audit iteration should be 1..N, got: " + a.getIteration());
        });
    }
}
