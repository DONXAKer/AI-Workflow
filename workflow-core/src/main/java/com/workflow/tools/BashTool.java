package com.workflow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a shell command inside the working directory, gated by {@link BashAllowlist} and
 * {@link DenyList}. Uses {@code sh -c} for POSIX compatibility — works in Alpine, Debian,
 * macOS; on Windows hosts the command must be runnable through whichever {@code sh} is
 * on PATH (typically Git Bash or WSL).
 *
 * <p>Output is captured up to a size cap and returned as a combined stdout/stderr blob
 * with the exit code. On timeout the process is destroyed and an error is surfaced.
 */
@Component
public class BashTool implements Tool {

    @Autowired(required = false)
    private BashApprovalGate bashApprovalGate;

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    static final int DEFAULT_TIMEOUT_SEC = 120;
    static final int MAX_OUTPUT_BYTES = 64 * 1024;

    @Autowired(required = false)
    private FileSystemCache fileSystemCache;

    /**
     * Env vars stripped from the subprocess environment before the shell runs. Keeps the
     * app's own credentials out of reach of whatever the LLM decides to run, even under a
     * narrow bash allowlist — defence in depth, not the primary boundary.
     *
     * <p>Matching is literal + suffix-based: any var whose name equals or ends in one of
     * these tokens gets dropped. Catches {@code OPENROUTER_API_KEY}, {@code FOO_API_KEY},
     * {@code GITHUB_TOKEN}, etc.
     */
    static final List<String> SECRET_ENV_SUFFIXES = List.of(
        "_API_KEY", "_TOKEN", "_SECRET", "_PASSWORD", "_PRIVATE_KEY",
        "OPENROUTER_API_KEY", "WORKFLOW_ENCRYPTION_KEY",
        "GITHUB_TOKEN", "GITLAB_TOKEN", "YOUTRACK_TOKEN",
        "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN"
    );

    @Override
    public String name() { return "Bash"; }

    @Override
    public String description() {
        return "Run a shell command inside the project working directory. "
            + "Subject to the block's bash allowlist and the global deny-list "
            + "(force push, hard reset, rm -rf, pipes to sh). "
            + "Returns combined stdout/stderr and exit code.";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper om) {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("command").put("type", "string")
            .put("description", "Shell command to execute via `sh -c`.");
        props.putObject("timeout_sec").put("type", "integer")
            .put("description", "Override the default 120s per-command timeout.");
        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode input) throws Exception {
        String command = input.path("command").asText();
        int timeoutSec = input.path("timeout_sec").asInt(DEFAULT_TIMEOUT_SEC);
        if (timeoutSec <= 0) timeoutSec = DEFAULT_TIMEOUT_SEC;

        DenyList.assertBashAllowed(command);
        if (!BashAllowlist.matches(command, ctx.bashAllowlist())) {
            if (bashApprovalGate != null && ctx.runId() != null) {
                boolean approved = bashApprovalGate.requestApproval(ctx.runId(), ctx.blockId(), command);
                if (!approved) {
                    throw new ToolInvocationException(
                        "команда отклонена оператором: '" + command + "'");
                }
            } else {
                BashAllowlist.assertMatch(command, ctx.bashAllowlist());
            }
        }

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.directory(ctx.workingDir().toFile());
        pb.redirectErrorStream(true);
        scrubSecrets(pb);

        log.debug("Bash exec in {}: {}", ctx.workingDir(), command);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new ToolInvocationException("failed to start shell: " + e.getMessage(), e);
        }

        // Read output in a separate thread — doing it in this thread would block on the
        // stream until the process closes it, which is never if the process hangs.
        AtomicReference<String> outRef = new AtomicReference<>("");
        Thread reader = new Thread(() -> {
            try {
                outRef.set(readCapped(proc.getInputStream(), MAX_OUTPUT_BYTES));
            } catch (IOException ignored) {
                // process died mid-read — output stays at whatever was captured
            }
        }, "BashTool-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            reader.interrupt();
            throw new ToolInvocationException(
                "bash command exceeded " + timeoutSec + "s timeout and was killed: " + command);
        }
        reader.join(5_000);
        int exit = proc.exitValue();
        String output = outRef.get();

        if (fileSystemCache != null && !fileSystemCache.isBashReadOnly(command)) {
            fileSystemCache.invalidateDirectory(ctx.workingDir());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(command).append('\n');
        if (!output.isEmpty()) sb.append(output);
        if (!output.endsWith("\n")) sb.append('\n');
        sb.append("[exit ").append(exit).append(']');
        return sb.toString();
    }

    static void scrubSecrets(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        env.entrySet().removeIf(e -> {
            String name = e.getKey();
            if (name == null) return false;
            String upper = name.toUpperCase();
            for (String suffix : SECRET_ENV_SUFFIXES) {
                if (upper.equals(suffix) || upper.endsWith(suffix)) return true;
            }
            return false;
        });
    }

    private static String readCapped(InputStream in, int maxBytes) throws IOException {
        byte[] buf = new byte[Math.min(8192, maxBytes)];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            int room = maxBytes - total;
            if (room <= 0) {
                out.write("\n... [output truncated]\n".getBytes(StandardCharsets.UTF_8));
                // drain and discard remainder so the process can exit
                while (in.read(buf) != -1) {}
                break;
            }
            int take = Math.min(n, room);
            out.write(buf, 0, take);
            total += take;
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
