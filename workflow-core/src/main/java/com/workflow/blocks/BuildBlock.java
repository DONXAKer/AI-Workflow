package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.core.ShellCommandRunner;
import com.workflow.core.expr.StringInterpolator;
import com.workflow.project.Project;
import com.workflow.project.ProjectContext;
import com.workflow.project.ProjectRepository;
import com.workflow.tools.DenyList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BuildBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(BuildBlock.class);

    /** Conventional stdout marker that the build script can echo to publish its artifact id. */
    private static final Pattern ARTIFACT_ID_LINE = Pattern.compile(
        "^\\s*artifact_id\\s*[=:]\\s*(\\S.*?)\\s*$", Pattern.MULTILINE);

    @Autowired(required = false) private StringInterpolator stringInterpolator;
    @Autowired(required = false) private ProjectRepository projectRepository;
    @Autowired(required = false) private ShellCommandRunner shellRunner;

    @Override
    public String getName() {
        return "build";
    }

    @Override
    public String getDescription() {
        return "Собирает артефакт. Если задан 'command' — выполняет shell-команду в workingDir "
            + "и парсит artifact_id из stdout (строка вида `artifact_id=...`). Без command — synthetic version.";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig() != null ? config.getConfig() : Map.of();

        String buildSystem = stringOr(cfg.get("build_system"), "shell");
        String ref = resolveRef(input, cfg);
        String artifactType = stringOr(cfg.get("artifact_type"), "docker");
        String registry = stringOr(cfg.get("registry"), "");
        String command = stringOr(cfg.get("command"), null);
        int timeoutSec = intOr(cfg.get("timeout_sec"), 1800);
        Path workingDir = resolveWorkingDir(cfg);

        String version = generateVersion(run);
        Map<String, Object> result = new HashMap<>();
        result.put("artifact_type", artifactType);
        result.put("artifact_version", version);
        result.put("registry", registry);
        result.put("ref", ref);
        result.put("build_system", buildSystem);
        result.put("built_at", Instant.now().toString());

        if (command == null || shellRunner == null) {
            log.info("Build placeholder (no command): system={}, ref={}, version={}", buildSystem, ref, version);
            result.put("artifact_id", artifactType + ":" + version);
            result.put("status", "success");
            result.put("success", true);
            result.put("placeholder", true);
            return result;
        }

        String resolvedCmd = stringInterpolator != null
            ? stringInterpolator.interpolate(command, run, input)
            : command;
        DenyList.assertBashAllowed(resolvedCmd);

        log.info("Build run: cmd={} cwd={} timeout={}s", resolvedCmd, workingDir, timeoutSec);
        ShellCommandRunner.ExecResult exec = shellRunner.exec(resolvedCmd, workingDir, timeoutSec);

        result.put("command", resolvedCmd);
        result.put("exit_code", exec.exitCode());
        result.put("stdout", exec.stdout());
        result.put("stderr", exec.stderr());
        result.put("duration_ms", exec.durationMs());

        String parsedArtifact = parseArtifactId(exec.stdout());
        result.put("artifact_id", parsedArtifact != null ? parsedArtifact : artifactType + ":" + version);

        if (exec.success()) {
            result.put("status", "success");
            result.put("success", true);
        } else {
            result.put("status", "failed");
            result.put("success", false);
            result.put("error", "Build command exited " + exec.exitCode());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String resolveRef(Map<String, Object> input, Map<String, Object> cfg) {
        Object refObj = cfg.get("ref");
        if (refObj instanceof String s && !s.isBlank()) return s;
        for (String key : new String[]{"vcs_merge", "merge", "gitlab_merge", "github_merge"}) {
            Object val = input.get(key);
            if (val instanceof Map<?, ?> m) {
                Object sha = ((Map<String, Object>) m).get("merge_sha");
                if (sha instanceof String s && !s.isBlank()) return s;
            }
        }
        return "main";
    }

    private Path resolveWorkingDir(Map<String, Object> cfg) {
        Object raw = cfg.get("working_dir");
        String resolved = null;
        if (raw instanceof String s && !s.isBlank()) resolved = s;
        if (resolved == null && projectRepository != null) {
            String slug = ProjectContext.get();
            if (slug != null && !slug.isBlank()) {
                Project p = projectRepository.findBySlug(slug).orElse(null);
                if (p != null && p.getWorkingDir() != null && !p.getWorkingDir().isBlank()) {
                    resolved = p.getWorkingDir();
                }
            }
        }
        if (resolved == null) return Paths.get(System.getProperty("user.dir"));
        Path path = Paths.get(resolved).toAbsolutePath();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("BuildBlock: working_dir is not a directory: " + path);
        }
        return path;
    }

    private String parseArtifactId(String stdout) {
        if (stdout == null || stdout.isEmpty()) return null;
        Matcher m = ARTIFACT_ID_LINE.matcher(stdout);
        if (m.find()) return m.group(1);
        return null;
    }

    private String generateVersion(PipelineRun run) {
        String runPart = run.getId() != null ? run.getId().toString().substring(0, 8) : "local";
        return Instant.now().getEpochSecond() + "-" + runPart;
    }

    private static String stringOr(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
