package com.workflow.blocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.llm.LlmClient;
import com.workflow.llm.tooluse.StopReason;
import com.workflow.llm.tooluse.ToolExecutor;
import com.workflow.llm.tooluse.ToolUseRequest;
import com.workflow.llm.tooluse.ToolUseResponse;
import com.workflow.project.Project;
import com.workflow.project.ProjectContext;
import com.workflow.project.ProjectRepository;
import com.workflow.tools.ReadTool;
import com.workflow.tools.ToolRegistry;
import com.workflow.tools.WriteTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentWithToolsBlockTest {

    private AgentWithToolsBlock block;
    private LlmClient llmClient;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        block = new AgentWithToolsBlock();
        llmClient = mock(LlmClient.class);
        registry = new ToolRegistry(List.of(new ReadTool(), new WriteTool()));
        ReflectionTestUtils.setField(block, "llmClient", llmClient);
        ReflectionTestUtils.setField(block, "toolRegistry", registry);
        ReflectionTestUtils.setField(block, "objectMapper", new ObjectMapper());
    }

    private BlockConfig cfg(Map<String, Object> configMap) {
        BlockConfig bc = new BlockConfig();
        bc.setId("test-block");
        bc.setBlock("agent_with_tools");
        AgentConfig agent = new AgentConfig();
        agent.setModel("fast");
        agent.setSystemPrompt("you are a test");
        agent.setMaxTokens(1024);
        agent.setTemperature(0.0);
        bc.setAgent(agent);
        bc.setConfig(configMap);
        return bc;
    }

    @Test
    void happyPath(@TempDir Path wd) throws Exception {
        ToolUseResponse stub = new ToolUseResponse("done", StopReason.END_TURN,
            List.of(), 2, 100, 50, 0.01);
        when(llmClient.completeWithTools(any(), any())).thenReturn(stub);

        Map<String, Object> config = new HashMap<>();
        config.put("working_dir", wd.toString());
        config.put("user_message", "Process {task}");
        config.put("allowed_tools", List.of("Read", "Write"));

        Map<String, Object> input = Map.of("task", "feature-42");
        Map<String, Object> out = block.run(input, cfg(config), new PipelineRun());

        ArgumentCaptor<ToolUseRequest> reqCap = ArgumentCaptor.forClass(ToolUseRequest.class);
        verify(llmClient).completeWithTools(reqCap.capture(), any(ToolExecutor.class));
        ToolUseRequest req = reqCap.getValue();
        assertEquals("Process feature-42", req.userMessage());
        assertEquals("fast", req.model());
        assertEquals(2, req.tools().size());
        assertEquals("Read", req.tools().get(0).name());
        assertEquals("Write", req.tools().get(1).name());

        assertEquals("done", out.get("final_text"));
        assertEquals("END_TURN", out.get("stop_reason"));
        assertEquals(2, out.get("iterations_used"));
    }

    @Test
    void missingWorkingDirFails(@TempDir Path wd) {
        Map<String, Object> config = new HashMap<>();
        config.put("user_message", "x");
        config.put("allowed_tools", List.of("Read"));
        assertThrows(IllegalArgumentException.class,
            () -> block.run(Map.of(), cfg(config), new PipelineRun()));
    }

    @Test
    void nonexistentWorkingDirFails(@TempDir Path wd) {
        Map<String, Object> config = new HashMap<>();
        config.put("working_dir", wd.resolve("nope").toString());
        config.put("user_message", "x");
        config.put("allowed_tools", List.of("Read"));
        assertThrows(IllegalArgumentException.class,
            () -> block.run(Map.of(), cfg(config), new PipelineRun()));
    }

    @Test
    void emptyAllowedToolsFails(@TempDir Path wd) {
        Map<String, Object> config = new HashMap<>();
        config.put("working_dir", wd.toString());
        config.put("user_message", "x");
        config.put("allowed_tools", List.of());
        assertThrows(IllegalArgumentException.class,
            () -> block.run(Map.of(), cfg(config), new PipelineRun()));
    }

    @Test
    void fallsBackToProjectWorkingDir(@TempDir Path wd) throws Exception {
        Project project = new Project();
        project.setSlug("myproj");
        project.setWorkingDir(wd.toString());
        ProjectRepository projectRepo = mock(ProjectRepository.class);
        when(projectRepo.findBySlug("myproj")).thenReturn(Optional.of(project));
        ReflectionTestUtils.setField(block, "projectRepository", projectRepo);

        when(llmClient.completeWithTools(any(), any()))
            .thenReturn(new ToolUseResponse("done", StopReason.END_TURN, List.of(), 1, 0, 0, 0));

        Map<String, Object> config = new HashMap<>();
        // no working_dir in config on purpose
        config.put("user_message", "x");
        config.put("allowed_tools", List.of("Read"));

        ProjectContext.set("myproj");
        try {
            Map<String, Object> out = block.run(Map.of(), cfg(config), new PipelineRun());
            assertEquals("END_TURN", out.get("stop_reason"));
        } finally {
            ProjectContext.clear();
        }
    }

    @Test
    void loopbackFeedbackAppendedToUserMessage(@TempDir Path wd) throws Exception {
        when(llmClient.completeWithTools(any(), any()))
            .thenReturn(new ToolUseResponse("done", StopReason.END_TURN, List.of(), 1, 0, 0, 0));

        Map<String, Object> config = new HashMap<>();
        config.put("working_dir", wd.toString());
        config.put("user_message", "Do the thing");
        config.put("allowed_tools", List.of("Read"));

        Map<String, Object> loopback = new LinkedHashMap<>();
        loopback.put("iteration", 2);
        loopback.put("issues", List.of("USTRUCT missing", "client test failed"));
        loopback.put("verify_from", "verify_contract_drift");
        Map<String, Object> input = new HashMap<>();
        input.put("_loopback", loopback);

        block.run(input, cfg(config), new PipelineRun());

        ArgumentCaptor<ToolUseRequest> req = ArgumentCaptor.forClass(ToolUseRequest.class);
        verify(llmClient).completeWithTools(req.capture(), any());
        String msg = req.getValue().userMessage();

        // Loopback header must appear BEFORE the main task so LLMs prioritise retry instructions.
        assertTrue(msg.startsWith("## ВАЖНО"), "loopback header must be at the top");
        assertTrue(msg.contains("итерация 2") || msg.contains("iteration 2"), "iteration number present");
        assertTrue(msg.contains("USTRUCT missing"));
        assertTrue(msg.contains("client test failed"));
        assertTrue(msg.contains("## Основная задача"), "main task section present");
        assertTrue(msg.contains("Do the thing"), "original message preserved");
        assertTrue(msg.indexOf("ВАЖНО") < msg.indexOf("Do the thing"),
            "loopback section must come before the main task");
        assertTrue(msg.contains("verify_contract_drift"),
            "extra loopback keys carried through");
    }

    @Test
    void noLoopbackLeavesUserMessageClean(@TempDir Path wd) throws Exception {
        when(llmClient.completeWithTools(any(), any()))
            .thenReturn(new ToolUseResponse("done", StopReason.END_TURN, List.of(), 1, 0, 0, 0));

        Map<String, Object> config = new HashMap<>();
        config.put("working_dir", wd.toString());
        config.put("user_message", "Clean request");
        config.put("allowed_tools", List.of("Read"));

        block.run(Map.of(), cfg(config), new PipelineRun());

        ArgumentCaptor<ToolUseRequest> req = ArgumentCaptor.forClass(ToolUseRequest.class);
        verify(llmClient).completeWithTools(req.capture(), any());
        assertEquals("Clean request", req.getValue().userMessage());
    }

    @Test
    void capsFlowThrough(@TempDir Path wd) throws Exception {
        when(llmClient.completeWithTools(any(), any()))
            .thenReturn(new ToolUseResponse("", StopReason.END_TURN, List.of(), 1, 0, 0, 0));

        Map<String, Object> config = new HashMap<>();
        config.put("working_dir", wd.toString());
        config.put("user_message", "x");
        config.put("allowed_tools", List.of("Read"));
        config.put("max_iterations", 7);
        config.put("budget_usd_cap", 0.25);

        block.run(Map.of(), cfg(config), new PipelineRun());

        ArgumentCaptor<ToolUseRequest> req = ArgumentCaptor.forClass(ToolUseRequest.class);
        verify(llmClient).completeWithTools(req.capture(), any());
        assertEquals(7, req.getValue().maxIterations());
        assertEquals(0.25, req.getValue().budgetUsdCap(), 0.0001);
    }
}
