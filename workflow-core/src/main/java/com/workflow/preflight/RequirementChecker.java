package com.workflow.preflight;

import com.workflow.integrations.IntegrationResolver;
import com.workflow.llm.LlmProvider;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;

/**
 * Verifies a single {@link Requirement} against the live environment. Pattern-matches the sealed
 * {@link Requirement} hierarchy so adding a variant forces a new case here.
 *
 * <p>Result semantics ({@link CheckResult.Kind}):
 * <ul>
 *   <li>local/presence failures (dir/binary/token/git missing) → {@code HARD_FAIL}</li>
 *   <li>network reachability failure or inconclusive probe → {@code SOFT_FAIL} (WARN)</li>
 * </ul>
 * The conditional-block downgrade (HARD_FAIL → WARN for {@code condition:}-gated blocks) is applied
 * by {@link RunDiagnostics}, not here — this class only reports the raw environment truth.
 */
@Component
public class RequirementChecker {

    /** Per-check network timeout. Local checks are instant; this bounds the L1 reachability probe. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final IntegrationResolver integrationResolver;
    private final ReachabilityProbe reachabilityProbe;

    public RequirementChecker(IntegrationResolver integrationResolver, ReachabilityProbe reachabilityProbe) {
        this.integrationResolver = integrationResolver;
        this.reachabilityProbe = reachabilityProbe;
    }

    public CheckResult check(Requirement req, PreflightContext ctx) {
        return switch (req) {
            case Requirement.WorkingDir wd -> checkWorkingDir(wd, ctx);
            case Requirement.Binary b -> checkBinary(b);
            case Requirement.Provider p -> checkProvider(p, ctx);
            case Requirement.Integration i -> checkIntegration(i, ctx);
            case Requirement.GitRepo g -> checkGitRepo(g, ctx);
        };
    }

    private CheckResult checkWorkingDir(Requirement.WorkingDir req, PreflightContext ctx) {
        String raw = req.path() != null ? req.path() : ctx.workingDir();
        if (raw == null || raw.isBlank()) {
            return CheckResult.hardFail(req, "no working directory configured for this project");
        }
        Path p;
        try {
            p = Paths.get(raw).toAbsolutePath();
        } catch (Exception e) {
            return CheckResult.hardFail(req, "invalid working directory path: " + raw);
        }
        if (!Files.exists(p)) {
            return CheckResult.hardFail(req, "working directory does not exist: " + p);
        }
        if (!Files.isDirectory(p)) {
            return CheckResult.hardFail(req, "working directory is not a directory: " + p);
        }
        if (!Files.isWritable(p)) {
            return CheckResult.hardFail(req, "working directory is not writable: " + p);
        }
        return CheckResult.pass(req);
    }

    private CheckResult checkBinary(Requirement.Binary req) {
        String name = req.name();
        if (name == null || name.isBlank()) {
            return CheckResult.pass(req); // nothing to check (e.g. binary could not be parsed)
        }
        return binaryExists(name)
                ? CheckResult.pass(req)
                : CheckResult.hardFail(req, "executable not found on PATH: " + name);
    }

    private CheckResult checkProvider(Requirement.Provider req, PreflightContext ctx) {
        LlmProvider provider = req.provider();
        if (provider == LlmProvider.CLAUDE_CODE_CLI) {
            String bin = integrationResolver.cliBinary();
            return binaryExists(bin)
                    ? CheckResult.pass(req)
                    : CheckResult.hardFail(req, "Claude CLI binary not found: " + bin
                        + " (set CLAUDE_BIN or install the claude CLI)");
        }
        // Presence is a deterministic hard requirement; reachability is best-effort (WARN).
        if (!integrationResolver.isProviderConfigured(provider)) {
            return CheckResult.hardFail(req, "no API key configured for " + provider
                    + " (set the env var or add an integration)");
        }
        String baseUrl = integrationResolver.providerBaseUrl(provider);
        ReachabilityProbe.Result probe = reachabilityProbe.probe(baseUrl, PROBE_TIMEOUT);
        return probe.reachable()
                ? CheckResult.pass(req)
                : CheckResult.softFail(req, provider + " unreachable (" + probe.detail() + ")");
    }

    private CheckResult checkIntegration(Requirement.Integration req, PreflightContext ctx) {
        String name = ctx.integrationName(req.type());
        // L0 presence: token must resolve. Reachability (L1) intentionally skipped for
        // YouTrack/GitHub/GitLab — no uniform cheap unauthenticated probe across these APIs.
        return integrationResolver.hasToken(name, req.type())
                ? CheckResult.pass(req)
                : CheckResult.hardFail(req, "no " + req.type()
                    + " integration configured with a token for this project");
    }

    private CheckResult checkGitRepo(Requirement.GitRepo req, PreflightContext ctx) {
        String wd = ctx.workingDir();
        if (wd == null || wd.isBlank()) {
            return CheckResult.hardFail(req, "no working directory configured — cannot locate a git repo");
        }
        Path gitEntry = Paths.get(wd).resolve(".git");
        return Files.exists(gitEntry)
                ? CheckResult.pass(req)
                : CheckResult.hardFail(req, "not a git repository (no .git in " + wd + ")");
    }

    /**
     * PATH-style executable lookup. Absolute/relative paths are checked directly; bare names are
     * searched across {@code $PATH} entries (with {@code .exe/.cmd/.bat} suffixes on Windows).
     */
    boolean binaryExists(String name) {
        if (name == null || name.isBlank()) return false;
        // Explicit path (absolute or containing a separator) — check the file directly.
        if (name.contains(File.separator) || name.contains("/")) {
            File f = new File(name);
            return f.isFile() && f.canExecute();
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) return false;
        String[] suffixes = WINDOWS ? new String[]{"", ".exe", ".cmd", ".bat"} : new String[]{""};
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String suffix : suffixes) {
                File candidate = new File(dir, name + suffix);
                if (candidate.isFile() && candidate.canExecute()) return true;
            }
        }
        return false;
    }
}
