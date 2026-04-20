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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)  // needs POSIX `sh`
class ShellExecBlockTest {

    private ShellExecBlock block;

    @BeforeEach
    void setUp() {
        block = new ShellExecBlock();
    }

    private BlockConfig cfgWith(Map<String, Object> cfg) {
        BlockConfig bc = new BlockConfig();
        bc.setId("shell");
        bc.setBlock("shell_exec");
        bc.setConfig(cfg);
        return bc;
    }

    @Test
    void runsCommandAndCapturesOutput(@TempDir Path wd) throws Exception {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("command", "echo hello; echo world");
        cfg.put("working_dir", wd.toString());

        Map<String, Object> out = block.run(Map.of(), cfgWith(cfg), new PipelineRun());

        assertEquals(0, out.get("exit_code"));
        assertEquals(true, out.get("success"));
        assertEquals("hello\nworld\n", out.get("stdout"));
        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) out.get("stdout_lines");
        assertEquals(List.of("hello", "world"), lines);
        assertEquals("world", out.get("last_line"));
    }

    @Test
    void nonzeroExitThrowsByDefault(@TempDir Path wd) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("command", "false");
        cfg.put("working_dir", wd.toString());

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> block.run(Map.of(), cfgWith(cfg), new PipelineRun()));
        assertTrue(ex.getMessage().contains("exited 1"));
    }

    @Test
    void allowNonzeroExitReturnsResult(@TempDir Path wd) throws Exception {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("command", "false");
        cfg.put("working_dir", wd.toString());
        cfg.put("allow_nonzero_exit", true);

        Map<String, Object> out = block.run(Map.of(), cfgWith(cfg), new PipelineRun());
        assertEquals(1, out.get("exit_code"));
        assertEquals(false, out.get("success"));
    }

    @Test
    void commandRunsInWorkingDir(@TempDir Path wd) throws Exception {
        Files.writeString(wd.resolve("marker.txt"), "x");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("command", "ls");
        cfg.put("working_dir", wd.toString());

        Map<String, Object> out = block.run(Map.of(), cfgWith(cfg), new PipelineRun());
        assertTrue(((String) out.get("stdout")).contains("marker.txt"));
    }

    @Test
    void denyListBlocksDestructiveCommand(@TempDir Path wd) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("command", "rm -rf /tmp/whatever");
        cfg.put("working_dir", wd.toString());

        assertThrows(RuntimeException.class,
            () -> block.run(Map.of(), cfgWith(cfg), new PipelineRun()));
    }

    @Test
    void timeoutKillsCommand(@TempDir Path wd) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("command", "sleep 5");
        cfg.put("working_dir", wd.toString());
        cfg.put("timeout_sec", 1);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> block.run(Map.of(), cfgWith(cfg), new PipelineRun()));
        assertTrue(ex.getMessage().contains("timed out"));
    }

    @Test
    void missingCommandFails(@TempDir Path wd) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("working_dir", wd.toString());
        assertThrows(IllegalArgumentException.class,
            () -> block.run(Map.of(), cfgWith(cfg), new PipelineRun()));
    }
}
