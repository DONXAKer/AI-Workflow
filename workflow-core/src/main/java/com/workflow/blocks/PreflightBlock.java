package com.workflow.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.preflight.PreflightCacheService;
import com.workflow.preflight.PreflightCommands;
import com.workflow.preflight.PreflightConfigResolver;
import com.workflow.preflight.PreflightSnapshot;
import com.workflow.preflight.PreflightStatus;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Baseline check executed at the very start of a pipeline. Runs the target project's
 * own build + test commands (resolved from {@code <workingDir>/CLAUDE.md ## Preflight}
 * or auto-detected from build manifests) against the current {@code main} commit and
 * caches the result keyed by {@code (projectSlug, mainCommitSha, configHash)}.
 *
 * <p>Phase A2 PR #1 keeps the scope minimal:
 * <ul>
 *   <li>No FQN extraction from test output — {@code baseline_failures} is always
 *       empty; downstream verify/CI blocks treat any failure as a regression.</li>
 *   <li>No {@code ls-remote} freshness check — cache hits are trusted up to TTL.</li>
 *   <li>No autofix branch — {@code on_red: block} surfaces a {@code RED_BLOCKED} status
 *       that the operator handles via a downstream {@code condition:} gate or approval.</li>
 * </ul>
 *
 * <p>Block config keys:
 * <pre>
 *   on_red: block | warn      # default: block — surface RED_BLOCKED on failure
 *   timeout_sec: 900          # default 900 — applies to each of build/test phases
 *   working_dir: /abs/path    # falls back to Project.workingDir from ProjectContext
 * </pre>
 *
 * <p>Output keys: {@code status, build_status, test_status, baseline_failures,
 * baseline_hash, cached, cache_source_sha, duration_ms, commands, log_excerpt,
 * preflight_source} (where {@code preflight_source} = claude_md / auto_detect / fallback).
 */
@Component
public class PreflightBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(PreflightBlock.class);

    static final int DEFAULT_TIMEOUT_SEC = 900;
    static final int MAX_OUTPUT_BYTES = 256 * 1024;
    static final int LOG_EXCERPT_BYTES = 4 * 1024;

    @Autowired private PreflightConfigResolver configResolver;
    @Autowired private PreflightCacheService cacheService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired(required = false) private ProjectRepository projectRepository;

    @Override public String getName() { return "preflight"; }

    @Override public String getDescription() {
        return "Бейслайн билд+тесты проекта до старта основного flow. Кэшируется по (project, main SHA, configHash). "
                + "Команды читаются из <workingDir>/CLAUDE.md ## Preflight или auto-detect (gradle/maven/npm/pytest/...).";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
                "Preflight",
                "verify",
                Phase.ANY,
                List.of(
                        FieldSchema.enumField("on_red", "Поведение при красном baseline",
                                List.of("block", "warn"), "block",
                                "block — RED_BLOCKED статус (оператор разруливает); warn — продолжает с warning."),
                        FieldSchema.number("timeout_sec", "Таймаут (сек) на каждую фазу", DEFAULT_TIMEOUT_SEC,
                                "Применяется отдельно к build и к test."),
                        FieldSchema.string("working_dir", "Рабочая директория",
                                "Абсолютный путь. Пусто — берётся workingDir текущего проекта.")
                ),
                false,
                Map.of()
        );
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig blockConfig, PipelineRun run) throws Exception {
        Map<String, Object> cfg = blockConfig.getConfig() != null ? blockConfig.getConfig() : Map.of();
        String onRed = asStr(cfg, "on_red", "block");
        int timeoutSec = asInt(cfg, "timeout_sec", DEFAULT_TIMEOUT_SEC);
        if (timeoutSec <= 0) timeoutSec = DEFAULT_TIMEOUT_SEC;

        Path workingDir = resolveWorkingDir(cfg, run);
        String projectSlug = run.getProjectSlug() != null ? run.getProjectSlug() : "default";

        PreflightCommands cmds = configResolver.resolve(workingDir);
        if (cmds.isFallback() || !cmds.hasCommands()) {
            log.warn("preflight[{}]: no build/test commands resolved; returning WARNING ({})",
                    blockConfig.getId(), cmds.detected());
            return fallbackOutput(cmds);
        }

        DenyList.assertBashAllowed(cmds.buildCmd());
        DenyList.assertBashAllowed(cmds.testCmd());

        String mainSha = readMainCommitSha(workingDir);
        if (mainSha == null) {
            log.warn("preflight[{}]: working dir {} is not a git checkout; running without cache",
                    blockConfig.getId(), workingDir);
        }
        String configHash = PreflightCacheService.computeConfigHash(cmds);

        // Cache lookup
        if (mainSha != null) {
            var cached = cacheService.lookup(projectSlug, mainSha, configHash);
            if (cached.isPresent()) {
                return cachedOutput(cached.get(), cmds);
            }
        }

        // Run build + test
        long started = System.currentTimeMillis();
        StringBuilder logExcerpt = new StringBuilder();
        ProcessResult buildResult = runCommand(cmds.buildCmd(), workingDir, timeoutSec, logExcerpt, "build");
        boolean buildOk = buildResult.exitCode == 0;
        ProcessResult testResult;
        if (buildOk) {
            testResult = runCommand(cmds.testCmd(), workingDir, timeoutSec, logExcerpt, "test");
        } else {
            log.info("preflight[{}]: build failed (exit {}), skipping tests", blockConfig.getId(), buildResult.exitCode);
            testResult = new ProcessResult(-1, "");  // skipped
        }
        long durationMs = System.currentTimeMillis() - started;
        boolean testOk = testResult.exitCode == 0;

        // Extract failed test FQNs from the combined log so downstream verify/CI blocks
        // can compute "tests we broke" = current failures \ baseline failures.
        List<String> baselineFailures = extractFailedTestFqns(logExcerpt.toString(), cmds.fqnFormat());

        PreflightStatus status;
        if (buildOk && testOk) {
            status = PreflightStatus.PASSED;
        } else if ("warn".equalsIgnoreCase(onRed)) {
            status = PreflightStatus.WARNING;
        } else {
            status = PreflightStatus.RED_BLOCKED;
        }

        // Persist snapshot (only when we have a SHA to key on)
        PreflightSnapshot snapshot = null;
        if (mainSha != null) {
            snapshot = new PreflightSnapshot();
            snapshot.setProjectSlug(projectSlug);
            snapshot.setMainCommitSha(mainSha);
            snapshot.setConfigHash(configHash);
            snapshot.setStatus(status);
            snapshot.setBuildOk(buildOk);
            snapshot.setTestOk(testOk);
            try {
                snapshot.setBaselineFailuresJson(objectMapper.writeValueAsString(baselineFailures));
            } catch (Exception e) {
                snapshot.setBaselineFailuresJson("[]");
            }
            snapshot.setBuildCmd(cmds.buildCmd());
            snapshot.setTestCmd(cmds.testCmd());
            snapshot.setLogExcerpt(truncate(logExcerpt.toString(), LOG_EXCERPT_BYTES));
            snapshot.setDurationMs(durationMs);
            cacheService.store(snapshot);
        }

        return buildOutput(cmds, status, buildOk, testOk, mainSha, configHash,
                false, durationMs, logExcerpt.toString(), baselineFailures);
    }

    // ── failed-test FQN extraction ─────────────────────────────────────────────

    /**
     * Gradle / JUnit console: {@code ClassName > methodName(args)[idx] FAILED}.
     * Class name may include {@code $} (inner classes) and {@code .} (FQ); method
     * name is a single identifier; tail allows anything (parameterized indices,
     * display names) before the literal FAILED at end-of-line.
     */
    private static final Pattern GRADLE_FAILED = Pattern.compile(
            "^([\\w$.]+)\\s*>\\s*(\\w+)[^\\n]*?\\bFAILED\\s*$",
            Pattern.MULTILINE);

    /** pytest console: {@code FAILED path/to/test_file.py::TestClass::test_method - reason} */
    private static final Pattern PYTEST_FAILED = Pattern.compile(
            "^FAILED\\s+([^\\s]+)\\s*(?:-.*)?$",
            Pattern.MULTILINE);

    /** Jest console: {@code  FAIL src/foo.test.ts > describe > it} (× simplified to file path only here) */
    private static final Pattern JEST_FAILED = Pattern.compile(
            "^\\s*FAIL\\s+([^\\s]+)\\s*$",
            Pattern.MULTILINE);

    /** Go test console: {@code --- FAIL: TestFooBar (0.00s)} */
    private static final Pattern GO_FAILED = Pattern.compile(
            "^---\\s+FAIL:\\s+(\\w+)(?:\\s+\\([^)]*\\))?\\s*$",
            Pattern.MULTILINE);

    /**
     * Parses test runner output for failed test identifiers. Returns a deduplicated
     * insertion-ordered list of {@code "Class.method"} (or runner-equivalent) strings
     * suitable for set-difference computation against later runs.
     *
     * <p>Supports {@code junit5} / {@code junit4} (gradle/maven), {@code pytest},
     * {@code jest}, {@code go}. Unknown format → empty list.
     */
    static List<String> extractFailedTestFqns(String log, String fqnFormat) {
        if (log == null || log.isBlank()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        switch (fqnFormat == null ? "" : fqnFormat.toLowerCase()) {
            case "junit5", "junit4", "junit" -> {
                Matcher m = GRADLE_FAILED.matcher(log);
                while (m.find()) seen.add(m.group(1) + "." + m.group(2));
            }
            case "pytest" -> {
                Matcher m = PYTEST_FAILED.matcher(log);
                while (m.find()) seen.add(m.group(1));
            }
            case "jest" -> {
                Matcher m = JEST_FAILED.matcher(log);
                while (m.find()) seen.add(m.group(1));
            }
            case "go" -> {
                Matcher m = GO_FAILED.matcher(log);
                while (m.find()) seen.add(m.group(1));
            }
            default -> { /* none / unknown — return empty */ }
        }
        return new ArrayList<>(seen);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> fallbackOutput(PreflightCommands cmds) {
        Map<String, Object> out = baseOutput(cmds, PreflightStatus.WARNING);
        out.put("build_status", "skipped");
        out.put("test_status", "skipped");
        out.put("cached", false);
        out.put("duration_ms", 0L);
        out.put("baseline_failures", List.of());
        out.put("baseline_hash", "");
        out.put("log_excerpt", "preflight skipped: " + cmds.detected());
        return out;
    }

    private Map<String, Object> cachedOutput(PreflightSnapshot s, PreflightCommands cmds) {
        List<String> baseline;
        try {
            baseline = objectMapper.readValue(
                    s.getBaselineFailuresJson() != null ? s.getBaselineFailuresJson() : "[]",
                    new TypeReference<List<String>>() {});
        } catch (Exception e) {
            baseline = List.of();
        }
        Map<String, Object> out = buildOutput(cmds, s.getStatus(), s.isBuildOk(), s.isTestOk(),
                s.getMainCommitSha(), s.getConfigHash(), true, s.getDurationMs(),
                s.getLogExcerpt() != null ? s.getLogExcerpt() : "", baseline);
        out.put("cache_source_id", s.getId());
        return out;
    }

    private Map<String, Object> buildOutput(PreflightCommands cmds, PreflightStatus status,
                                              boolean buildOk, boolean testOk,
                                              String mainSha, String configHash,
                                              boolean cached, long durationMs,
                                              String logExcerpt, List<String> baselineFailures) {
        Map<String, Object> out = baseOutput(cmds, status);
        out.put("build_status", buildOk ? "ok" : "failed");
        out.put("test_status", buildOk ? (testOk ? "ok" : "failed") : "skipped");
        out.put("baseline_failures", baselineFailures);
        out.put("baseline_hash", configHash != null ? configHash : "");
        out.put("cached", cached);
        out.put("cache_source_sha", mainSha != null ? mainSha : "");
        out.put("duration_ms", durationMs);
        out.put("log_excerpt", truncate(logExcerpt, LOG_EXCERPT_BYTES));
        // Convenience boolean for ${preflight.passed} condition gating.
        // PASSED + WARNING are both "downstream may proceed" — operator chose
        // `on_red: warn` deliberately to continue past pre-existing red baseline.
        // RED_BLOCKED is the only state that gates downstream.
        out.put("passed", status != PreflightStatus.RED_BLOCKED);
        // Distinct flag for "actually green vs proceeding-on-warning" so downstream
        // blocks that care can branch (`$.preflight.green == true` for stricter gates).
        out.put("green", status == PreflightStatus.PASSED);
        return out;
    }

    private Map<String, Object> baseOutput(PreflightCommands cmds, PreflightStatus status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status.name());
        Map<String, Object> commands = new LinkedHashMap<>();
        commands.put("build", cmds.buildCmd());
        commands.put("test", cmds.testCmd());
        commands.put("fqn_format", cmds.fqnFormat());
        out.put("commands", commands);
        out.put("preflight_source", cmds.source());
        if (cmds.detected() != null) out.put("preflight_detected", cmds.detected());
        return out;
    }

    private Path resolveWorkingDir(Map<String, Object> cfg, PipelineRun run) {
        Object raw = cfg.get("working_dir");
        String resolved = raw != null ? raw.toString() : null;
        if ((resolved == null || resolved.isBlank()) && projectRepository != null) {
            String slug = run.getProjectSlug() != null ? run.getProjectSlug() : ProjectContext.get();
            if (slug != null && !slug.isBlank()) {
                Project p = projectRepository.findBySlug(slug).orElse(null);
                if (p != null && p.getWorkingDir() != null && !p.getWorkingDir().isBlank()) {
                    resolved = p.getWorkingDir();
                }
            }
        }
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException(
                    "preflight: working_dir is not set in block config and current project has no workingDir");
        }
        Path path = Paths.get(resolved).toAbsolutePath();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("preflight: working_dir is not a directory: " + path);
        }
        return path;
    }

    private String readMainCommitSha(Path workingDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = readCapped(p.getInputStream(), 4_096).trim();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) return null;
            if (out.matches("[0-9a-fA-F]{7,64}")) return out;
            return null;
        } catch (Exception e) {
            log.debug("preflight: git rev-parse HEAD failed in {}: {}", workingDir, e.getMessage());
            return null;
        }
    }

    private ProcessResult runCommand(String command, Path workingDir, int timeoutSec,
                                       StringBuilder logSink, String phase) throws Exception {
        log.info("preflight[{}]: cwd={} cmd={}", phase, workingDir, command);
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        AtomicReference<String> combined = new AtomicReference<>("");
        Thread reader = new Thread(() -> {
            try { combined.set(readCapped(proc.getInputStream(), MAX_OUTPUT_BYTES)); }
            catch (IOException ignored) {}
        }, "preflight-" + phase);
        reader.setDaemon(true);
        reader.start();

        boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            reader.interrupt();
            logSink.append("\n[").append(phase).append("] TIMED OUT after ").append(timeoutSec).append("s\n");
            return new ProcessResult(-1, "");
        }
        reader.join(5_000);
        String captured = combined.get();
        logSink.append("\n[").append(phase).append(" exit=").append(proc.exitValue()).append("]\n");
        logSink.append(captured);
        return new ProcessResult(proc.exitValue(), captured);
    }

    private static String readCapped(InputStream in, int maxBytes) throws IOException {
        byte[] buf = new byte[Math.min(8192, maxBytes)];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            int room = maxBytes - total;
            if (room <= 0) {
                while (in.read(buf) != -1) {}
                break;
            }
            int take = Math.min(n, room);
            out.write(buf, 0, take);
            total += take;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String truncate(String s, int maxBytes) {
        if (s == null) return "";
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) return s;
        // Keep the tail (errors usually surface at the end)
        return "...[head truncated]\n" + new String(b, b.length - maxBytes, maxBytes, StandardCharsets.UTF_8);
    }

    private static String asStr(Map<String, Object> cfg, String key, String def) {
        Object v = cfg.get(key);
        return v != null && !v.toString().isBlank() ? v.toString() : def;
    }

    private static int asInt(Map<String, Object> cfg, String key, int def) {
        Object v = cfg.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (Exception e) { return def; }
    }

    private record ProcessResult(int exitCode, String stdout) {}
}
