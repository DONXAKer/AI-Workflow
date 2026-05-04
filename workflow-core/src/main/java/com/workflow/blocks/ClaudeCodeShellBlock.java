package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.core.expr.StringInterpolator;
import com.workflow.llm.LlmCall;
import com.workflow.llm.LlmCallContext;
import com.workflow.llm.LlmCallRepository;
import com.workflow.llm.LlmProvider;
import com.workflow.project.Project;
import com.workflow.project.ProjectContext;
import com.workflow.project.ProjectRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Layer 3: invokes the user's Claude Code CLI (typically {@code claude -p --mcp-config
 * ...}) from inside a pipeline block. Separate from {@code agent_with_tools}
 * (Layer 2, OpenRouter) because billing, model access, and MCP plumbing are routed
 * through the user's Max subscription here instead of OpenRouter credits.
 *
 * <p>Universal block — can drive MCP flows, Blueprint generation,
 * or any other Claude-Code-native flow.
 *
 * <p>YAML:
 * <pre>
 * - id: design_bp
 *   block: claude_code_shell
 *   config:
 *     prompt: "Design a BP that ${task_md.to_be}"
 *     working_dir: /abs/path/to/project        # falls back to Project.workingDir
 *     model: sonnet                            # optional, forwarded as --model
 *     allowed_tools: "Read,Glob,Grep,Bash(git *)"
 *     mcp_config: .mcp.json                    # relative to working_dir
 *     permission_mode: default                 # default | acceptEdits | plan | bypassPermissions
 *     timeout_sec: 600                         # default 600
 *     cli_bin: claude                          # default; tests override to a stub
 * </pre>
 *
 * <p>Output: {@code command}, {@code exit_code}, {@code success}, {@code stdout},
 * {@code stderr}, {@code duration_ms}.
 */
@Component
public class ClaudeCodeShellBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeShellBlock.class);

    static final int DEFAULT_TIMEOUT_SEC = 600;
    static final int MAX_OUTPUT_BYTES = 512 * 1024;

    @Autowired(required = false) private StringInterpolator stringInterpolator;
    @Autowired(required = false) private ProjectRepository projectRepository;
    @Autowired(required = false) private LlmCallRepository llmCallRepository;

    @Override public String getName() { return "claude_code_shell"; }

    @Override public String getDescription() {
        return "Вызывает локальный Claude Code CLI (claude -p) с MCP-конфигом и списком разрешённых инструментов. Используется для Layer 3 сценариев (MCP-driven Blueprint), которые не проходят через OpenRouter.";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig blockConfig, PipelineRun run) throws Exception {
        Map<String, Object> cfg = blockConfig.getConfig() != null ? blockConfig.getConfig() : Map.of();

        String rawPrompt = asRequiredString(cfg, "prompt");
        String prompt = interpolate(rawPrompt, run, input);

        Path workingDir = resolveWorkingDir(cfg, input, run);
        String cliBin = asString(cfg, "cli_bin", "claude");
        int timeoutSec = asInt(cfg, "timeout_sec", DEFAULT_TIMEOUT_SEC);
        if (timeoutSec <= 0) timeoutSec = DEFAULT_TIMEOUT_SEC;

        List<String> argv = new ArrayList<>();
        argv.add(cliBin);
        argv.add("-p");
        argv.add(prompt);

        String model = asStringOrNull(cfg, "model", run, input);
        if (model != null) {
            argv.add("--model");
            argv.add(model);
        }
        String allowedTools = asStringOrNull(cfg, "allowed_tools", run, input);
        if (allowedTools != null) {
            argv.add("--allowed-tools");
            argv.add(allowedTools);
        }
        String mcpConfig = asStringOrNull(cfg, "mcp_config", run, input);
        if (mcpConfig != null) {
            argv.add("--mcp-config");
            argv.add(mcpConfig);
        }
        String permissionMode = asStringOrNull(cfg, "permission_mode", run, input);
        if (permissionMode != null) {
            argv.add("--permission-mode");
            argv.add(permissionMode);
        }

        log.info("claude_code_shell[{}]: cwd={} argv={}",
            blockConfig.getId(), workingDir, previewArgv(argv));

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);

        long started = System.currentTimeMillis();
        Process proc = pb.start();

        AtomicReference<String> stdout = new AtomicReference<>("");
        AtomicReference<String> stderr = new AtomicReference<>("");
        Thread outReader = drain(proc.getInputStream(), stdout, "claude-stdout");
        Thread errReader = drain(proc.getErrorStream(), stderr, "claude-stderr");

        boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            outReader.interrupt();
            errReader.interrupt();
            throw new RuntimeException(
                "claude_code_shell timed out after " + timeoutSec + "s");
        }
        outReader.join(5_000);
        errReader.join(5_000);
        int exit = proc.exitValue();
        int durationMs = (int) (System.currentTimeMillis() - started);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", argv);
        out.put("working_dir", workingDir.toString());
        out.put("exit_code", exit);
        out.put("success", exit == 0);
        out.put("duration_ms", durationMs);
        out.put("stdout", stdout.get());
        out.put("stderr", stderr.get());

        recordCliInvocation(model, durationMs, exit == 0);

        if (exit != 0) {
            throw new RuntimeException(
                "claude_code_shell: CLI exited " + exit + "\nstderr: " + truncate(stderr.get(), 2000));
        }
        return out;
    }

    /**
     * Emits one synthetic {@link LlmCall} row per block invocation so the iteration-history
     * UI shows a marker even though the multi-iteration loop is opaque (it lives inside
     * the local {@code claude} subprocess). {@code iteration=0} signals "single shot".
     * Tokens/cost are zero because Claude Code CLI does not expose usage metrics in stdout
     * — drilling per-turn data would require parsing {@code --output-format stream-json},
     * which is intentionally out of scope here.
     */
    private void recordCliInvocation(String model, int durationMs, boolean success) {
        if (llmCallRepository == null) return;
        try {
            LlmCall call = new LlmCall();
            call.setTimestamp(java.time.Instant.now());
            call.setModel(model != null && !model.isBlank() ? model : "claude/local");
            call.setTokensIn(0);
            call.setTokensOut(0);
            call.setCostUsd(0.0);
            call.setDurationMs(durationMs);
            call.setProjectSlug(ProjectContext.get());
            call.setProvider(LlmProvider.CLAUDE_CODE_CLI);
            call.setFinishReason(success ? "END_TURN" : "ERROR");
            LlmCallContext.current().ifPresent(ctx -> {
                call.setRunId(ctx.runId());
                call.setBlockId(ctx.blockId());
            });
            llmCallRepository.save(call);
        } catch (Exception e) {
            log.debug("claude_code_shell: LlmCall persist failed: {}", e.getMessage());
        }
    }

    private Path resolveWorkingDir(Map<String, Object> cfg, Map<String, Object> input, PipelineRun run) {
        Object raw = cfg.get("working_dir");
        String resolved = null;
        if (raw != null && !raw.toString().isBlank()) {
            resolved = interpolate(raw.toString(), run, input);
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
                "claude_code_shell: working_dir not set and current project has no workingDir");
        }
        Path path = Paths.get(resolved).toAbsolutePath();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(
                "claude_code_shell: working_dir is not a directory: " + path);
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

    private String interpolate(String s, PipelineRun run, Map<String, Object> input) {
        return stringInterpolator != null
            ? stringInterpolator.interpolate(s, run, input)
            : s;
    }

    private String asStringOrNull(Map<String, Object> cfg, String key,
                                  PipelineRun run, Map<String, Object> input) {
        Object v = cfg.get(key);
        if (v == null || v.toString().isBlank()) return null;
        return interpolate(v.toString(), run, input);
    }

    private static String previewArgv(List<String> argv) {
        List<String> preview = new ArrayList<>();
        for (String a : argv) {
            preview.add(a.length() > 80 ? a.substring(0, 80) + "..." : a);
        }
        return preview.toString();
    }

    private static String asRequiredString(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("claude_code_shell: config." + key + " is required");
        }
        return v.toString();
    }

    private static String asString(Map<String, Object> cfg, String key, String def) {
        Object v = cfg.get(key);
        return (v == null || v.toString().isBlank()) ? def : v.toString();
    }

    private static int asInt(Map<String, Object> cfg, String key, int def) {
        Object v = cfg.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString().trim());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n... [truncated]";
    }
}
