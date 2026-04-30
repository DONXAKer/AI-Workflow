package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.core.expr.StringInterpolator;
import com.workflow.project.Project;
import com.workflow.project.ProjectContext;
import com.workflow.project.ProjectRepository;
import com.workflow.tools.DenyList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a shell command as a pipeline step (no LLM involved). Unlike the Bash tool that
 * lives inside the agentic loop, shell_exec is invoked directly from YAML:
 *
 * <pre>
 * - id: run_tests
 *   block: shell_exec
 *   config:
 *     command: "gradle test --tests '${input.test_class}'"
 *     working_dir: /abs/path               # optional; falls back to Project.workingDir
 *     timeout_sec: 300                     # default 300
 *     allow_nonzero_exit: false            # default false -> block fails on nonzero
 * </pre>
 *
 * <p>Output map: {@code command}, {@code exit_code}, {@code stdout}, {@code stderr},
 * {@code stdout_lines} (convenience list), {@code last_line}, {@code success}.
 *
 * <p>Security: the global {@link DenyList#assertBashAllowed} still applies — no
 * {@code rm -rf}, {@code git push --force}, or pipes to {@code sh}, even when invoked
 * from YAML. There is no per-block allowlist (that's an LLM concern); the trust model
 * here is "the pipeline author wrote this command and it's in version control".
 */
@Component
public class ShellExecBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(ShellExecBlock.class);

    static final int DEFAULT_TIMEOUT_SEC = 300;
    static final int MAX_OUTPUT_BYTES = 256 * 1024;

    @Autowired(required = false) private StringInterpolator stringInterpolator;
    @Autowired(required = false) private ProjectRepository projectRepository;

    @Override public String getName() { return "shell_exec"; }

    @Override public String getDescription() {
        return "Выполняет shell-команду как шаг пайплайна (без LLM). Рабочая директория — workingDir проекта. Глобальный deny-list применяется.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
            "Shell exec",
            "infra",
            List.of(
                new FieldSchema("command", "Команда", "string", true, null,
                    "Shell-команда (sh -c). Поддерживает интерполяцию ${block.field}.",
                    Map.of("multiline", true, "monospace", true)),
                FieldSchema.string("working_dir", "Рабочая директория",
                    "Абсолютный путь. Если пусто — берётся workingDir проекта."),
                FieldSchema.number("timeout_sec", "Таймаут (сек)", DEFAULT_TIMEOUT_SEC,
                    "Максимальное время выполнения. По умолчанию 300."),
                FieldSchema.bool("allow_nonzero_exit", "Разрешать ненулевой exit", false,
                    "Если true, блок не падает на ненулевом exit code (но выставляет success=false).")
            ),
            false,
            Map.of()
        );
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig blockConfig, PipelineRun run) throws Exception {
        Map<String, Object> cfg = blockConfig.getConfig() != null ? blockConfig.getConfig() : Map.of();

        String rawCommand = asRequiredString(cfg, "command");
        String command = stringInterpolator != null
            ? stringInterpolator.interpolate(rawCommand, run, input)
            : rawCommand;
        DenyList.assertBashAllowed(command);

        Path workingDir = resolveWorkingDir(cfg, input, run);
        int timeoutSec = asInt(cfg, "timeout_sec", DEFAULT_TIMEOUT_SEC);
        if (timeoutSec <= 0) timeoutSec = DEFAULT_TIMEOUT_SEC;
        boolean allowNonzero = asBool(cfg, "allow_nonzero_exit", false);

        log.info("shell_exec[{}]: cwd={} cmd={}", blockConfig.getId(), workingDir, command);

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);

        long started = System.currentTimeMillis();
        Process proc = pb.start();

        AtomicReference<String> stdout = new AtomicReference<>("");
        AtomicReference<String> stderr = new AtomicReference<>("");
        Thread outReader = drain(proc.getInputStream(), stdout, "shell_exec-stdout");
        Thread errReader = drain(proc.getErrorStream(), stderr, "shell_exec-stderr");

        boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            outReader.interrupt();
            errReader.interrupt();
            throw new RuntimeException(
                "shell_exec timed out after " + timeoutSec + "s: " + command);
        }
        outReader.join(5_000);
        errReader.join(5_000);
        int exit = proc.exitValue();
        int durationMs = (int) (System.currentTimeMillis() - started);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", command);
        out.put("working_dir", workingDir.toString());
        out.put("exit_code", exit);
        out.put("success", exit == 0);
        out.put("duration_ms", durationMs);
        String stdoutStr = stdout.get();
        out.put("stdout", stdoutStr);
        out.put("stderr", stderr.get());

        List<String> lines = stdoutStr.isEmpty() ? List.of() : Arrays.asList(stdoutStr.split("\\R"));
        out.put("stdout_lines", lines);
        out.put("last_line", lines.isEmpty() ? "" : lines.get(lines.size() - 1));

        if (exit != 0 && !allowNonzero) {
            throw new RuntimeException(
                "shell_exec: command exited " + exit + " (command: " + command + ")\n"
                    + "stderr: " + truncate(stderr.get(), 2000));
        }

        return out;
    }

    private Path resolveWorkingDir(Map<String, Object> cfg, Map<String, Object> input, PipelineRun run) {
        Object raw = cfg.get("working_dir");
        String resolved = null;
        if (raw != null && !raw.toString().isBlank()) {
            resolved = stringInterpolator != null
                ? stringInterpolator.interpolate(raw.toString(), run, input)
                : raw.toString();
        }
        if (resolved == null && projectRepository != null) {
            String slug = ProjectContext.get();
            if (slug != null && !slug.isBlank()) {
                Project p = projectRepository.findBySlug(slug).orElse(null);
                if (p != null && p.getWorkingDir() != null && !p.getWorkingDir().isBlank()) {
                    resolved = p.getWorkingDir();
                }
            }
        }
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException(
                "shell_exec: working_dir not set in block config and current project "
                    + "has no workingDir");
        }
        Path path = Paths.get(resolved).toAbsolutePath();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("shell_exec: working_dir is not a directory: " + path);
        }
        return path;
    }

    private Thread drain(InputStream in, AtomicReference<String> ref, String name) {
        Thread t = new Thread(() -> {
            try {
                ref.set(readCapped(in, MAX_OUTPUT_BYTES));
            } catch (IOException ignored) {}
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
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
                while (in.read(buf) != -1) {}
                break;
            }
            int take = Math.min(n, room);
            out.write(buf, 0, take);
            total += take;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n... [truncated]";
    }

    private static String asRequiredString(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("shell_exec: config." + key + " is required");
        }
        return v.toString();
    }

    private static int asInt(Map<String, Object> cfg, String key, int def) {
        Object v = cfg.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString().trim());
    }

    private static boolean asBool(Map<String, Object> cfg, String key, boolean def) {
        Object v = cfg.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString().trim());
    }
}
