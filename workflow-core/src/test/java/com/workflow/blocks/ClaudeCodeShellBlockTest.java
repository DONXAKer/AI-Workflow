package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class ClaudeCodeShellBlockTest {

    private ClaudeCodeShellBlock block;

    @BeforeEach
    void setUp() {
        block = new ClaudeCodeShellBlock();
    }

    /** Creates an executable shell script that echoes its argv and exits 0. */
    private Path cliStub(Path dir, String script) throws Exception {
        Path bin = dir.resolve("claude-stub.sh");
        Files.writeString(bin, "#!/bin/sh\n" + script);
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    private BlockConfig cfgWith(Map<String, Object> cfg) {
        BlockConfig bc = new BlockConfig();
        bc.setId("ccs");
        bc.setBlock("claude_code_shell");
        bc.setConfig(cfg);
        return bc;
    }

    @Test
    void buildsArgvAndCapturesStdout(@TempDir Path wd) throws Exception {
        Path stub = cliStub(wd, "echo \"CLI got: $*\"\nexit 0\n");

        Map<String, Object> cfg = new HashMap<>();
        cfg.put("cli_bin", stub.toString());
        cfg.put("prompt", "design BP");
        cfg.put("working_dir", wd.toString());
        cfg.put("model", "sonnet");
        cfg.put("allowed_tools", "Read,Glob");
        cfg.put("mcp_config", ".mcp.json");

        Map<String, Object> out = block.run(Map.of(), cfgWith(cfg), new PipelineRun());

        assertEquals(0, out.get("exit_code"));
        assertTrue((Boolean) out.get("success"));
        String stdout = (String) out.get("stdout");
        assertTrue(stdout.contains("-p"), "should include -p flag, got: " + stdout);
        assertTrue(stdout.contains("design BP"));
        assertTrue(stdout.contains("--model sonnet"));
        assertTrue(stdout.contains("--allowed-tools Read,Glob"));
        assertTrue(stdout.contains("--mcp-config .mcp.json"));

        @SuppressWarnings("unchecked")
        List<String> argv = (List<String>) out.get("command");
        assertEquals(stub.toString(), argv.get(0));
        assertEquals("-p", argv.get(1));
        assertEquals("design BP", argv.get(2));
    }

    @Test
    void omitsUnspecifiedFlags(@TempDir Path wd) throws Exception {
        Path stub = cliStub(wd, "echo \"$*\"\nexit 0\n");

        Map<String, Object> cfg = new HashMap<>();
        cfg.put("cli_bin", stub.toString());
        cfg.put("prompt", "x");
        cfg.put("working_dir", wd.toString());
        // no model, no allowed_tools, no mcp_config

        Map<String, Object> out = block.run(Map.of(), cfgWith(cfg), new PipelineRun());
        String stdout = (String) out.get("stdout");
        assertFalse(stdout.contains("--model"));
        assertFalse(stdout.contains("--allowed-tools"));
        assertFalse(stdout.contains("--mcp-config"));
    }

    @Test
    void nonzeroExitThrows(@TempDir Path wd) throws Exception {
        Path stub = cliStub(wd, "echo 'boom' 1>&2\nexit 2\n");

        Map<String, Object> cfg = new HashMap<>();
        cfg.put("cli_bin", stub.toString());
        cfg.put("prompt", "x");
        cfg.put("working_dir", wd.toString());

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> block.run(Map.of(), cfgWith(cfg), new PipelineRun()));
        assertTrue(ex.getMessage().contains("exited 2"));
        assertTrue(ex.getMessage().contains("boom"));
    }

    @Test
    void timeoutKillsHangingCli(@TempDir Path wd) throws Exception {
        Path stub = cliStub(wd, "sleep 5\n");

        Map<String, Object> cfg = new HashMap<>();
        cfg.put("cli_bin", stub.toString());
        cfg.put("prompt", "x");
        cfg.put("working_dir", wd.toString());
        cfg.put("timeout_sec", 1);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> block.run(Map.of(), cfgWith(cfg), new PipelineRun()));
        assertTrue(ex.getMessage().contains("timed out"));
    }

    @Test
    void missingPromptFails(@TempDir Path wd) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("working_dir", wd.toString());
        assertThrows(IllegalArgumentException.class,
            () -> block.run(Map.of(), cfgWith(cfg), new PipelineRun()));
    }
}
