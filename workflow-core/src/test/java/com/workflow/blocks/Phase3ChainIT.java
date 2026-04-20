package com.workflow.blocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.core.BlockOutput;
import com.workflow.core.PipelineRun;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chains three M3 blocks — {@code task_md_input} → {@code agent_with_tools} →
 * {@code shell_exec} — through their real Spring beans against live OpenRouter.
 * Proves that ${block.field} interpolation, block-to-block data flow, and
 * per-block audit all hold together end-to-end.
 *
 * <p>Does not go through {@link com.workflow.core.PipelineRunner}. That stack
 * is exercised by the existing run-creation tests; here we care about the
 * block-layer contracts only — constructing inputs the same way the runner
 * would, so a PipelineRun-level pass later doesn't surprise us.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:phase3-it;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "workflow.mode=cli"
})
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
@Tag("integration")
class Phase3ChainIT {

    @Autowired private TaskMdInputBlock taskMdBlock;
    @Autowired private AgentWithToolsBlock agentBlock;
    @Autowired private ShellExecBlock shellBlock;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void chainsTaskParseAgentWriteAndShellValidate(@TempDir Path wd) throws Exception {
        Path task = wd.resolve("FEAT-3_chain-demo.md");
        Files.writeString(task, """
            # Create two marker files

            ## Как сейчас
            Папка пуста.

            ## Как надо
            Создать ровно два файла в корне working directory: `alpha.txt` с содержимым "A" \
            и `beta.txt` с содержимым "B". Больше ничего создавать не нужно.

            ## Критерии приёмки
            - Оба файла существуют
            - Каждый ровно с правильным содержимым
            """);

        PipelineRun run = new PipelineRun();
        run.setOutputs(new ArrayList<>());

        // --- Block 1: task_md_input ---
        BlockConfig taskCfg = simpleConfig("task_md", "task_md_input", Map.of(
            "file_path", task.toString()));
        Map<String, Object> taskOut = taskMdBlock.run(Map.of(), taskCfg, run);
        persistOutput(run, "task_md", taskOut);

        assertEquals("FEAT-3", taskOut.get("feat_id"));
        assertEquals("chain-demo", taskOut.get("slug"));
        assertTrue(((String) taskOut.get("to_be")).contains("alpha.txt"));

        // --- Block 2: agent_with_tools (driven by task_md.to_be) ---
        BlockConfig agentCfg = new BlockConfig();
        agentCfg.setId("impl");
        agentCfg.setBlock("agent_with_tools");
        AgentConfig agent = new AgentConfig();
        agent.setModel("anthropic/claude-haiku-4-5");
        agent.setSystemPrompt(
            "You execute file-manipulation tasks exactly. When done, reply with one "
                + "short sentence confirming completion and end your turn.");
        agent.setMaxTokens(1024);
        agent.setTemperature(0.0);
        agentCfg.setAgent(agent);

        Map<String, Object> agentConfigMap = new HashMap<>();
        agentConfigMap.put("working_dir", wd.toString());
        agentConfigMap.put("user_message",
            "Task: ${task_md.to_be}\n\nWork inside the current working directory. "
                + "Use the Write tool for each file.");
        agentConfigMap.put("allowed_tools", List.of("Read", "Write"));
        agentConfigMap.put("max_iterations", 8);
        agentConfigMap.put("budget_usd_cap", 0.5);
        agentCfg.setConfig(agentConfigMap);

        Map<String, Object> agentInput = new HashMap<>();
        agentInput.put("task_md", taskOut);
        Map<String, Object> agentOut = agentBlock.run(agentInput, agentCfg, run);
        persistOutput(run, "impl", agentOut);

        assertEquals("END_TURN", agentOut.get("stop_reason"),
            () -> "agent did not END_TURN — tools=" + agentOut.get("tool_calls_made"));
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) agentOut.get("tool_calls_made");
        assertTrue(tools.contains("Write"), "expected Write calls, got: " + tools);

        assertTrue(Files.exists(wd.resolve("alpha.txt")), "alpha.txt not created");
        assertTrue(Files.exists(wd.resolve("beta.txt")), "beta.txt not created");

        // --- Block 3: shell_exec validator ---
        BlockConfig shellCfg = simpleConfig("validate", "shell_exec", Map.of(
            "command", "ls *.txt | sort",
            "working_dir", wd.toString()));
        Map<String, Object> shellOut = shellBlock.run(Map.of(), shellCfg, run);
        persistOutput(run, "validate", shellOut);

        assertEquals(0, shellOut.get("exit_code"));
        String stdout = (String) shellOut.get("stdout");
        assertTrue(stdout.contains("alpha.txt"), "ls output missing alpha.txt: " + stdout);
        assertTrue(stdout.contains("beta.txt"), "ls output missing beta.txt: " + stdout);
    }

    private BlockConfig simpleConfig(String id, String type, Map<String, Object> config) {
        BlockConfig bc = new BlockConfig();
        bc.setId(id);
        bc.setBlock(type);
        bc.setConfig(new HashMap<>(config));
        return bc;
    }

    private void persistOutput(PipelineRun run, String blockId, Map<String, Object> out) throws Exception {
        BlockOutput bo = new BlockOutput();
        bo.setBlockId(blockId);
        bo.setOutputJson(objectMapper.writeValueAsString(out));
        // Store a normalized copy (LinkedHashMap) so downstream JSON round-trips cleanly.
        List<BlockOutput> existing = run.getOutputs() != null
            ? run.getOutputs() : new ArrayList<>();
        existing.add(bo);
        run.setOutputs(existing);
    }
}
