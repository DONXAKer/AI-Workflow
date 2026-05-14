package com.workflow.preflight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the build/test commands to use for preflight on a target project.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Explicit {@code ## Preflight} section in {@code <workingDir>/CLAUDE.md} —
 *       parsed as {@code key: value} pairs (build, test, fqn_format).</li>
 *   <li>Auto-detect from build manifests: {@code pom.xml}, {@code build.gradle},
 *       {@code build.gradle.kts}, {@code package.json}, {@code pyproject.toml},
 *       {@code go.mod}, {@code Cargo.toml}.</li>
 *   <li>Fallback: empty commands, {@code source: fallback} — caller should warn.</li>
 * </ol>
 */
@Service
public class PreflightConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(PreflightConfigResolver.class);

    /** Matches a level-2 heading "## Preflight" (case-insensitive) up to the next ## heading or EOF. */
    private static final Pattern PREFLIGHT_SECTION = Pattern.compile(
            "(?im)^##\\s*preflight\\s*$\\s*([\\s\\S]*?)(?=^##\\s|\\z)");

    /** Matches "key: value" lines, ignoring leading whitespace and comments. */
    private static final Pattern KV_LINE = Pattern.compile(
            "^\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);

    public PreflightCommands resolve(Path workingDir) {
        if (workingDir == null) return fallback("workingDir is null");

        // 1) CLAUDE.md ## Preflight section
        PreflightCommands fromClaudeMd = readFromClaudeMd(workingDir);
        if (fromClaudeMd != null) return fromClaudeMd;

        // 2) Auto-detect from manifests
        PreflightCommands detected = autoDetect(workingDir);
        if (detected != null) return detected;

        // 3) Fallback
        return fallback("no manifests detected and no CLAUDE.md ## Preflight section");
    }

    PreflightCommands readFromClaudeMd(Path workingDir) {
        Path file = workingDir.resolve("CLAUDE.md");
        if (!Files.isRegularFile(file)) return null;
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = PREFLIGHT_SECTION.matcher(content);
            if (!m.find()) return null;
            String body = m.group(1);

            Map<String, String> kv = new LinkedHashMap<>();
            Matcher kvm = KV_LINE.matcher(body);
            while (kvm.find()) {
                kv.put(kvm.group(1).toLowerCase(), kvm.group(2).trim());
            }
            String build = kv.getOrDefault("build", "");
            String test = kv.getOrDefault("test", "");
            String fqn = kv.getOrDefault("fqn_format", kv.getOrDefault("test_fqn_format", "junit5"));
            if (build.isBlank() && test.isBlank()) {
                log.debug("CLAUDE.md has ## Preflight section but no build/test keys at {}", file);
                return null;
            }
            return new PreflightCommands(build, test, fqn,
                    PreflightCommands.SOURCE_CLAUDE_MD, null);
        } catch (IOException e) {
            log.debug("Could not read {}: {}", file, e.getMessage());
            return null;
        }
    }

    PreflightCommands autoDetect(Path workingDir) {
        boolean hasGradleWrapper = Files.isRegularFile(workingDir.resolve("gradlew"));
        String gradleCmd = hasGradleWrapper ? "./gradlew" : "gradle";

        // Order matters when multiple manifests coexist (multi-stack monorepo):
        // build.gradle wins over pom.xml (rare combo, but gradle is more common in this codebase);
        // package.json + pyproject mix → just take the first that matches.
        if (Files.isRegularFile(workingDir.resolve("build.gradle.kts"))
                || Files.isRegularFile(workingDir.resolve("build.gradle"))) {
            return new PreflightCommands(
                    gradleCmd + " build -x test --no-daemon",
                    gradleCmd + " test --no-daemon",
                    "junit5",
                    PreflightCommands.SOURCE_AUTO,
                    "gradle (" + (hasGradleWrapper ? "wrapper" : "system") + ")"
            );
        }
        if (Files.isRegularFile(workingDir.resolve("pom.xml"))) {
            return new PreflightCommands(
                    "mvn -B -DskipTests=true compile",
                    "mvn -B test",
                    "junit5",
                    PreflightCommands.SOURCE_AUTO,
                    "maven"
            );
        }
        if (Files.isRegularFile(workingDir.resolve("package.json"))) {
            // Conservative: do `npm install` for build (deps must be available for tests),
            // and `npm test` for tests. Operator can override via CLAUDE.md if their
            // project uses `pnpm`, `yarn`, or has a separate build script.
            return new PreflightCommands(
                    "npm install --no-audit --no-fund",
                    "npm test",
                    "jest",
                    PreflightCommands.SOURCE_AUTO,
                    "npm"
            );
        }
        if (Files.isRegularFile(workingDir.resolve("pyproject.toml"))) {
            return new PreflightCommands(
                    "pip install -e .",
                    "pytest",
                    "pytest",
                    PreflightCommands.SOURCE_AUTO,
                    "pyproject"
            );
        }
        if (Files.isRegularFile(workingDir.resolve("go.mod"))) {
            return new PreflightCommands(
                    "go build ./...",
                    "go test ./...",
                    "go",
                    PreflightCommands.SOURCE_AUTO,
                    "go"
            );
        }
        if (Files.isRegularFile(workingDir.resolve("Cargo.toml"))) {
            return new PreflightCommands(
                    "cargo build",
                    "cargo test",
                    "none",
                    PreflightCommands.SOURCE_AUTO,
                    "cargo"
            );
        }
        return null;
    }

    private PreflightCommands fallback(String reason) {
        log.warn("PreflightConfigResolver: falling back ({}) — preflight will be a no-op", reason);
        return new PreflightCommands("", "", "none", PreflightCommands.SOURCE_FALLBACK, reason);
    }
}
