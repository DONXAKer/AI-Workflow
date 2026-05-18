package com.workflow.core;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-place shell-exec helper for non-LLM pipeline blocks (build / deploy / rollback /
 * verify_prod). Shares the same semantics as {@link com.workflow.blocks.ShellExecBlock} but
 * exposed as a reusable service so blocks don't each carry their own ProcessBuilder boilerplate.
 *
 * <p>Caller is responsible for: {@code DenyList.assertBashAllowed} on the command,
 * resolving the workingDir, and deciding what to do with the exit code.
 */
@Service
public class ShellCommandRunner {

    public static final int DEFAULT_OUTPUT_CAP_BYTES = 256 * 1024;

    public record ExecResult(int exitCode, String stdout, String stderr, long durationMs) {
        public boolean success() { return exitCode == 0; }
    }

    /**
     * Runs {@code command} via {@code sh -c} in {@code workingDir}. Captures stdout/stderr
     * up to {@link #DEFAULT_OUTPUT_CAP_BYTES}; truncates the rest with a marker line.
     *
     * @throws RuntimeException when the process exceeds {@code timeoutSec}
     */
    public ExecResult exec(String command, Path workingDir, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);

        long started = System.currentTimeMillis();
        Process proc = pb.start();

        AtomicReference<String> stdout = new AtomicReference<>("");
        AtomicReference<String> stderr = new AtomicReference<>("");
        Thread outReader = drain(proc.getInputStream(), stdout, "shell-stdout");
        Thread errReader = drain(proc.getErrorStream(), stderr, "shell-stderr");

        boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            outReader.interrupt();
            errReader.interrupt();
            throw new RuntimeException("Shell command timed out after " + timeoutSec + "s: " + command);
        }
        outReader.join(5_000);
        errReader.join(5_000);
        long elapsed = System.currentTimeMillis() - started;
        return new ExecResult(proc.exitValue(), stdout.get(), stderr.get(), elapsed);
    }

    private Thread drain(InputStream in, AtomicReference<String> ref, String name) {
        Thread t = new Thread(() -> {
            try { ref.set(readCapped(in)); } catch (IOException ignored) {}
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String readCapped(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            int room = DEFAULT_OUTPUT_CAP_BYTES - total;
            if (room <= 0) {
                out.write("\n... [truncated]\n".getBytes(StandardCharsets.UTF_8));
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
