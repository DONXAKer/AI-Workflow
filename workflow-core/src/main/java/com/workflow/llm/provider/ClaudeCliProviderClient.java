package com.workflow.llm.provider;

import com.workflow.llm.LlmCall;
import com.workflow.llm.LlmCallContext;
import com.workflow.llm.LlmCallRepository;
import com.workflow.llm.LlmProvider;
import com.workflow.llm.ModelPresetResolver;
import com.workflow.llm.Models;
import com.workflow.llm.tooluse.StopReason;
import com.workflow.llm.tooluse.ToolDefinition;
import com.workflow.llm.tooluse.ToolExecutor;
import com.workflow.llm.tooluse.ToolUseRequest;
import com.workflow.llm.tooluse.ToolUseResponse;
import com.workflow.model.IntegrationConfig;
import com.workflow.model.IntegrationConfigRepository;
import com.workflow.model.IntegrationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Claude Code CLI provider — invokes the local {@code claude -p} subprocess (Layer 3 /
 * Anthropic Max subscription). The multi-iteration tool-use loop happens inside the
 * CLI, so this provider records one synthetic {@link LlmCall} row per block run.
 *
 * <p>Selection: routed by {@link com.workflow.llm.LlmCallContext}'s preferred provider
 * when set to {@code CLAUDE_CODE_CLI}, otherwise auto-detected via {@link #canHandle(String)}
 * — bare {@code claude-*}/anthropic-prefixed names go through CLI when the integration
 * is configured.
 *
 * <p>Heartbeat: while the subprocess runs, a daemon scheduler updates
 * {@code PipelineRun.currentOperation} every 10 s with elapsed time + stdout byte
 * count, so the UI/API shows progress on long codegen sessions (5–15 min on Sonnet).
 *
 * <p>Working directory: subprocess cwd resolves from {@code ToolUseRequest.workingDir}
 * first, then falls back to {@code Project.workingDir} via {@code ProjectContext.get()}.
 * Without --add-dir, the CLI's Read/Glob/Grep see only the container's cwd (/app),
 * so referencing project files by relative path silently no-ops.
 */
@Service
public class ClaudeCliProviderClient implements LlmProviderClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliProviderClient.class);

    private static final int CLI_TIMEOUT_SEC = 1800;
    private static final int CLI_MAX_OUTPUT_BYTES = 1024 * 1024;

    private final IntegrationConfigRepository integrationConfigRepository;
    private final ModelPresetResolver presetResolver;
    private final LlmCallRepository llmCallRepository;
    private final com.workflow.core.PipelineRunRepository runRepositoryForHeartbeat;
    private final com.workflow.project.ProjectRepository projectRepositoryForCwd;

    @Autowired
    public ClaudeCliProviderClient(IntegrationConfigRepository integrationConfigRepository,
                                   ModelPresetResolver presetResolver,
                                   @Autowired(required = false) LlmCallRepository llmCallRepository,
                                   @Autowired(required = false) com.workflow.core.PipelineRunRepository runRepositoryForHeartbeat,
                                   @Autowired(required = false) com.workflow.project.ProjectRepository projectRepositoryForCwd) {
        this.integrationConfigRepository = integrationConfigRepository;
        this.presetResolver = presetResolver;
        this.llmCallRepository = llmCallRepository;
        this.runRepositoryForHeartbeat = runRepositoryForHeartbeat;
        this.projectRepositoryForCwd = projectRepositoryForCwd;
    }

    @Override
    public LlmProvider providerType() {
        return LlmProvider.CLAUDE_CODE_CLI;
    }

    @Override
    public String complete(String model, String system, String user, int maxTokens, double temperature) {
        return completeViaCli(resolveCliModel(model), system, user);
    }

    @Override
    public String completeWithMessages(String model, List<Map<String, String>> messages,
                                       int maxTokens, double temperature) {
        // Claude CLI doesn't expose a chat-history mode through `-p`; degrade to
        // a system+user pair built from the last user message. Continuation-call
        // is rarely needed on CLI (Anthropic models complete JSON reliably) so
        // this lossy path is acceptable.
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("completeWithMessages: messages must not be empty");
        }
        String system = "";
        String user = "";
        for (var msg : messages) {
            if ("system".equals(msg.get("role"))) system = msg.getOrDefault("content", "");
            else user = msg.getOrDefault("content", user);
        }
        return completeViaCli(resolveCliModel(model), system, user);
    }

    @Override
    public ToolUseResponse completeWithTools(ToolUseRequest request, ToolExecutor executor) {
        if (request == null) throw new IllegalArgumentException("request required");
        // executor unused — tool-use loop runs inside the CLI subprocess
        String model = resolveCliModel(request.model());
        String prompt = (request.systemPrompt() != null && !request.systemPrompt().isBlank())
            ? request.systemPrompt() + "\n\n" + request.userMessage()
            : request.userMessage();

        List<String> argv = new ArrayList<>(List.of(getCliBin(), "-p", prompt));
        if (model != null && !model.isBlank()) { argv.add("--model"); argv.add(model); }
        // bypassPermissions is rejected when Claude CLI runs as root (security guard);
        // acceptEdits with an explicit --allowed-tools list works under root and matches
        // what the claude_code_shell block uses successfully.
        // Build --allowed-tools from the block's tool list when present so the CLI's
        // permission floor matches what the block intends (e.g. agent_verify excludes
        // Write/Edit). Falls back to the legacy 6-tool superset if request.tools is empty.
        String allowedTools = "Read,Write,Edit,Bash,Glob,Grep";
        if (request.tools() != null && !request.tools().isEmpty()) {
            allowedTools = request.tools().stream()
                .map(ToolDefinition::name)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining(","));
        }
        argv.addAll(List.of("--allowed-tools", allowedTools,
                            "--permission-mode", "acceptEdits"));

        // Without --add-dir + cwd inside the project, the CLI's Read/Glob/Grep see only
        // /app (workflow-core's container cwd) — so any prompt referencing project files
        // by relative path silently no-ops and the agent returns a placeholder verdict.
        // Pin both the subprocess cwd and the CLI's allowed-dir list to the project root.
        java.io.File subprocessCwd = null;
        if (request.workingDir() != null) {
            java.io.File dir = request.workingDir().toFile();
            if (dir.isDirectory()) subprocessCwd = dir;
            else log.warn("CLI tool-use: request.workingDir not a directory: {}", dir);
        }
        if (subprocessCwd == null) {
            String slug = com.workflow.project.ProjectContext.get();
            if (slug != null && !slug.isBlank() && projectRepositoryForCwd != null) {
                try {
                    java.util.Optional<com.workflow.project.Project> p = projectRepositoryForCwd.findBySlug(slug);
                    if (p.isPresent() && p.get().getWorkingDir() != null && !p.get().getWorkingDir().isBlank()) {
                        java.io.File dir = new java.io.File(p.get().getWorkingDir());
                        if (dir.isDirectory()) subprocessCwd = dir;
                    }
                } catch (Exception e) {
                    log.warn("CLI tool-use: failed to resolve project workingDir for slug={}: {}", slug, e.getMessage());
                }
            }
        }
        if (subprocessCwd != null) {
            argv.add("--add-dir");
            argv.add(subprocessCwd.getAbsolutePath());
        }

        log.info("Calling Claude CLI (tool-use): model={} tools={} cwd={}",
            model, allowedTools, subprocessCwd != null ? subprocessCwd : "<inherit>");
        long startedAt = System.currentTimeMillis();
        try {
            String stdout = runClaudeSubprocess(argv, subprocessCwd);
            recordUsage("cli/" + model, (int)(System.currentTimeMillis() - startedAt));
            return new ToolUseResponse(LlmTextUtils.stripCodeFences(stdout.strip()), StopReason.END_TURN,
                List.of(), 1, 0, 0, 0.0);
        } catch (Exception e) {
            log.error("Claude CLI tool-use failed: {}", e.getMessage(), e);
            throw new RuntimeException("Claude CLI tool-use failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean canHandle(String model) {
        try {
            LlmProvider preferred = LlmCallContext.current()
                .map(LlmCallContext.Context::preferredProvider)
                .orElse(null);
            if (preferred == LlmProvider.OPENROUTER) return false;
            if (preferred == LlmProvider.OLLAMA) return false;
            if (preferred == LlmProvider.AITUNNEL) return false;
            if (preferred == LlmProvider.VLLM) return false;

            // Explicit run-level CLI preference (from Project.defaultProvider) bypasses
            // the integration check — the operator picked CLI in project settings, that's
            // a stronger signal than the optional CLAUDE_CODE_CLI integration row, which
            // only carries a custom binary path. Without integration we fall back to the
            // {@code claude} binary on $PATH (see {@link #getCliBin()}).
            if (preferred == LlmProvider.CLAUDE_CODE_CLI) return true;

            boolean cliAvailable = integrationConfigRepository
                .findByTypeAndIsDefaultTrue(IntegrationType.CLAUDE_CODE_CLI)
                .isPresent();
            if (!cliAvailable) return false;

            if (model == null || model.isBlank()) return true;
            // Explicit non-Anthropic model (google/, deepseek/, openai/, etc.) → OpenRouter
            if (model.contains("/") && !model.startsWith("anthropic/")) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getCliBin() {
        String envBin = System.getenv("CLAUDE_BIN");
        if (envBin != null && !envBin.isBlank()) return envBin;
        try {
            return integrationConfigRepository
                .findByTypeAndIsDefaultTrue(IntegrationType.CLAUDE_CODE_CLI)
                .map(IntegrationConfig::getBaseUrl)
                .filter(s -> s != null && !s.isBlank())
                .orElse("claude");
        } catch (Exception e) {
            return "claude";
        }
    }

    private String resolveCliModel(String model) {
        return presetResolver != null ? presetResolver.resolveCli(model) : (model != null ? model : Models.CLI_FALLBACK);
    }

    private String completeViaCli(String model, String system, String user) {
        List<String> argv = new ArrayList<>(List.of(getCliBin(), "-p", user != null ? user : ""));
        if (model != null && !model.isBlank()) { argv.add("--model"); argv.add(model); }
        if (system != null && !system.isBlank()) { argv.add("--system-prompt"); argv.add(system); }

        log.info("Calling Claude CLI: model={}", model);
        long startedAt = System.currentTimeMillis();
        try {
            String stdout = runClaudeSubprocess(argv, null);
            recordUsage("cli/" + model, (int)(System.currentTimeMillis() - startedAt));
            return LlmTextUtils.stripCodeFences(stdout.strip());
        } catch (Exception e) {
            log.error("Claude CLI call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Claude CLI call failed: " + e.getMessage(), e);
        }
    }

    private String runClaudeSubprocess(List<String> argv, java.io.File workingDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv);
        if (workingDir != null) pb.directory(workingDir);
        pb.redirectErrorStream(false);
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        Process proc = pb.start();

        AtomicReference<String> stdoutRef = new AtomicReference<>("");
        AtomicReference<String> stderrRef = new AtomicReference<>("");
        Thread outReader = drainStream(proc.getInputStream(), stdoutRef, CLI_MAX_OUTPUT_BYTES);
        Thread errReader = drainStream(proc.getErrorStream(), stderrRef, 32 * 1024);

        // Heartbeat: каждые 10 секунд обновляет run.lastActivityAt + currentOperation
        // чтобы UI/API могли видеть прогресс долгих CLI-сессий (codegen на Sonnet
        // регулярно крутится 5-15 минут). Без этого `currentOperation` остаётся
        // "Запущен блок: codegen" всё это время и не понятно жив ли процесс.
        long startTime = System.currentTimeMillis();
        java.util.concurrent.ScheduledExecutorService heartbeat =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cli-heartbeat");
                t.setDaemon(true);
                return t;
            });
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                if (runRepositoryForHeartbeat == null) return;
                LlmCallContext.current().ifPresent(ctx -> {
                    if (ctx.runId() == null) return;
                    long elapsedSec = (System.currentTimeMillis() - startTime) / 1000;
                    int bytes = stdoutRef.get().length();
                    runRepositoryForHeartbeat.findById(ctx.runId()).ifPresent(r -> {
                        r.setCurrentOperation(String.format(
                            "CLI [%s]: %ds elapsed, %d stdout bytes",
                            ctx.blockId() != null ? ctx.blockId() : "?", elapsedSec, bytes));
                        r.setLastActivityAt(java.time.Instant.now());
                        runRepositoryForHeartbeat.save(r);
                    });
                });
            } catch (Exception ignore) {
                // heartbeat must never break the main flow
            }
        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);

        try {
            boolean finished = proc.waitFor(CLI_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                outReader.interrupt();
                errReader.interrupt();
                throw new RuntimeException("Claude CLI timed out after " + CLI_TIMEOUT_SEC + "s");
            }
            outReader.join(5_000);
            errReader.join(5_000);

            int exit = proc.exitValue();
            if (exit != 0) {
                String stderr = stderrRef.get();
                String msg = stderr.isBlank() ? "" : ": " + (stderr.length() > 1000 ? stderr.substring(0, 1000) + "..." : stderr);
                throw new RuntimeException("Claude CLI exited " + exit + msg);
            }
            return stdoutRef.get();
        } finally {
            heartbeat.shutdownNow();
        }
    }

    private Thread drainStream(InputStream in, AtomicReference<String> ref, int maxBytes) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int total = 0, n;
                while ((n = in.read(buf)) != -1) {
                    int take = Math.min(n, maxBytes - total);
                    if (take <= 0) break;
                    baos.write(buf, 0, take);
                    total += take;
                }
                ref.set(baos.toString(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void recordUsage(String model, int durationMs) {
        if (llmCallRepository == null) return;
        try {
            LlmCall call = new LlmCall();
            call.setTimestamp(java.time.Instant.now());
            call.setModel(model);
            call.setTokensIn(0);
            call.setTokensOut(0);
            call.setCostUsd(0.0);
            call.setDurationMs(durationMs);
            call.setProjectSlug(com.workflow.project.ProjectContext.get());
            call.setProvider(LlmProvider.CLAUDE_CODE_CLI);
            LlmCallContext.current().ifPresent(ctx -> {
                call.setRunId(ctx.runId());
                call.setBlockId(ctx.blockId());
            });
            llmCallRepository.save(call);
        } catch (Exception e) {
            log.debug("LlmCall persist failed (CLI): {}", e.getMessage());
        }
    }
}
