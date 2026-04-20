package com.workflow.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BashToolsTest {

    private final ObjectMapper om = new ObjectMapper();

    private ObjectNode input(String cmd) {
        return om.createObjectNode().put("command", cmd);
    }

    @Nested
    class BashAllowlistTest {
        @Test
        void starMatchesAnyArgs() {
            assertDoesNotThrow(() -> BashAllowlist.assertMatch("git status", List.of("Bash(git *)")));
            assertDoesNotThrow(() -> BashAllowlist.assertMatch("git commit -m 'msg'", List.of("Bash(git *)")));
        }

        @Test
        void exactMatch() {
            assertDoesNotThrow(() -> BashAllowlist.assertMatch("gradle test", List.of("Bash(gradle test)")));
            assertThrows(ToolInvocationException.class,
                () -> BashAllowlist.assertMatch("gradle test --info", List.of("Bash(gradle test)")));
        }

        @Test
        void anchoredStartAndEnd() {
            // Pattern "Bash(ls)" must match exactly "ls", not "ls -la"
            assertThrows(ToolInvocationException.class,
                () -> BashAllowlist.assertMatch("ls -la", List.of("Bash(ls)")));
        }

        @Test
        void mismatchRejected() {
            ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> BashAllowlist.assertMatch("rm somefile", List.of("Bash(git *)", "Bash(gradle *)")));
            assertTrue(ex.getMessage().contains("not in allowlist"));
        }

        @Test
        void emptyAllowlistBlocksEverything() {
            assertThrows(ToolInvocationException.class,
                () -> BashAllowlist.assertMatch("echo hi", List.of()));
            assertThrows(ToolInvocationException.class,
                () -> BashAllowlist.assertMatch("echo hi", null));
        }

        @Test
        void regexMetacharsAreLiteral() {
            // '.' inside the pattern should match literal '.', not any char
            assertDoesNotThrow(() -> BashAllowlist.assertMatch("npm run test:unit",
                List.of("Bash(npm run test:*)")));
            assertThrows(ToolInvocationException.class,
                () -> BashAllowlist.assertMatch("npmxrun test:unit",
                    List.of("Bash(npm run test:*)")));
        }

        @Test
        void bareGlobWithoutWrapperAccepted() {
            // Test convenience: tolerate patterns without the Bash(...) wrapper.
            assertDoesNotThrow(() -> BashAllowlist.assertMatch("git status", List.of("git *")));
        }
    }

    @Nested
    @DisabledOnOs(OS.WINDOWS)  // needs POSIX `sh` on PATH
    class BashToolTest {
        private final BashTool tool = new BashTool();

        private ToolContext ctx(Path wd, List<String> allow) {
            return new ToolContext(wd, allow);
        }

        @Test
        void runsSimpleCommand(@TempDir Path wd) throws Exception {
            String out = tool.execute(ctx(wd, List.of("Bash(echo *)")), input("echo hello"));
            assertTrue(out.contains("hello"));
            assertTrue(out.contains("[exit 0]"));
        }

        @Test
        void runsInWorkingDir(@TempDir Path wd) throws Exception {
            java.nio.file.Files.writeString(wd.resolve("marker"), "x");
            String out = tool.execute(ctx(wd, List.of("Bash(ls*)")), input("ls"));
            assertTrue(out.contains("marker"));
        }

        @Test
        void nonzeroExitCaptured(@TempDir Path wd) throws Exception {
            String out = tool.execute(ctx(wd, List.of("Bash(sh -c *)", "Bash(false)")),
                input("false"));
            assertTrue(out.contains("[exit 1]"));
        }

        @Test
        void notInAllowlistRejected(@TempDir Path wd) {
            assertThrows(ToolInvocationException.class,
                () -> tool.execute(ctx(wd, List.of("Bash(git *)")), input("echo hi")));
        }

        @Test
        void denyListBlocksEvenIfInAllowlist(@TempDir Path wd) {
            // Allowlist says "anything goes", but DenyList must still block rm -rf.
            assertThrows(ToolInvocationException.class,
                () -> tool.execute(ctx(wd, List.of("Bash(*)")), input("rm -rf /tmp/whatever")));
        }

        @Test
        void scrubsSecretEnvVars(@TempDir Path wd) throws Exception {
            String marker = "scrubs-it-" + System.nanoTime();
            String parentValue = "visible-only-to-parent-" + marker;

            // Simulate a secret in the parent env. We can't really mutate JVM env at
            // runtime (setenv is OS-only), so we drive ProcessBuilder directly to
            // verify scrubSecrets removes entries we put in its env map.
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "echo OPENROUTER_API_KEY=${OPENROUTER_API_KEY:-MISSING}; "
                    + "echo GITHUB_TOKEN=${GITHUB_TOKEN:-MISSING}; "
                    + "echo MY_API_KEY=${MY_API_KEY:-MISSING}; "
                    + "echo HARMLESS=${HARMLESS:-MISSING}");
            pb.redirectErrorStream(true);
            pb.environment().put("OPENROUTER_API_KEY", parentValue);
            pb.environment().put("GITHUB_TOKEN", parentValue);
            pb.environment().put("MY_API_KEY", parentValue);
            pb.environment().put("HARMLESS", "kept");

            BashTool.scrubSecrets(pb);

            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes());
            proc.waitFor(10, TimeUnit.SECONDS);

            assertFalse(out.contains(parentValue),
                "scrubbed secrets must not leak into subprocess stdout: " + out);
            assertTrue(out.contains("OPENROUTER_API_KEY=MISSING"));
            assertTrue(out.contains("GITHUB_TOKEN=MISSING"));
            assertTrue(out.contains("MY_API_KEY=MISSING"),
                "suffix _API_KEY should catch custom names");
            assertTrue(out.contains("HARMLESS=kept"),
                "non-secret vars must survive");
        }

        @Test
        void timeoutKillsHangingCommand(@TempDir Path wd) {
            ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> tool.execute(ctx(wd, List.of("Bash(sleep *)")),
                    input("sleep 5").put("timeout_sec", 1)));
            assertTrue(ex.getMessage().contains("timeout"));
        }
    }
}
