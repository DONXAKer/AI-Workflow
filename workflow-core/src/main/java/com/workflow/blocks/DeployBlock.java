package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.core.ShellCommandRunner;
import com.workflow.core.expr.StringInterpolator;
import com.workflow.model.DeploymentHistory;
import com.workflow.model.DeploymentHistoryRepository;
import com.workflow.preflight.PreflightContext;
import com.workflow.preflight.PreflightRequirements;
import com.workflow.preflight.Requirement;
import com.workflow.preflight.ShellCommandBinary;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DeployBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(DeployBlock.class);
    private static final Set<String> KNOWN_ENVIRONMENTS = Set.of("test", "staging", "prod");

    @Autowired(required = false) private StringInterpolator stringInterpolator;
    @Autowired(required = false) private ProjectRepository projectRepository;
    @Autowired(required = false) private ShellCommandRunner shellRunner;
    @Autowired(required = false) private DeploymentHistoryRepository deploymentHistoryRepository;

    @Override
    public String getName() {
        return "deploy";
    }

    @Override
    public String getDescription() {
        return "Разворачивает артефакт в окружение (test/staging/prod). Если задан 'command' — выполняет deploy-команду "
            + "в workingDir (kubectl apply / docker-compose up / argocd app sync); без command — placeholder result.";
    }

    @Override
    public List<Requirement> preflightRequirements(BlockConfig config, PreflightContext context) {
        List<Requirement> reqs = new ArrayList<>();
        reqs.add(PreflightRequirements.workingDir(config));
        String bin = ShellCommandBinary.firstBinary(PreflightRequirements.literalString(config, "command"));
        if (bin != null) reqs.add(new Requirement.Binary(bin));
        return reqs;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig() != null ? config.getConfig() : Map.of();

        String environment = requireString(cfg.get("environment"),
            "DeployBlock requires 'environment' in config (test|staging|prod)");
        if (!KNOWN_ENVIRONMENTS.contains(environment)) {
            log.warn("Unknown environment '{}', proceeding (custom envs are allowed)", environment);
        }

        Map<String, Object> artifact = resolveArtifact(input);
        if (artifact == null) {
            throw new IllegalStateException("DeployBlock: could not resolve artifact from input — expected 'build' dependency output");
        }

        String deployStrategy = stringOr(cfg.get("strategy"), "rolling");
        int timeoutSec = intOr(cfg.get("timeout_sec"), 600);
        String command = stringOr(cfg.get("command"), null);
        Path workingDir = resolveWorkingDir(cfg);

        Map<String, Object> result = new HashMap<>();
        result.put("environment", environment);
        result.put("artifact_id", artifact.get("artifact_id"));
        result.put("artifact_version", artifact.get("artifact_version"));
        result.put("strategy", deployStrategy);
        result.put("deployment_id", "dep-" + Instant.now().getEpochSecond());
        result.put("deployed_at", Instant.now().toString());

        if (command == null || shellRunner == null) {
            log.info("Deploy placeholder (no command): env={}, artifact={}, strategy={}",
                environment, artifact.get("artifact_id"), deployStrategy);
            result.put("status", "success");
            result.put("success", true);
            result.put("placeholder", true);
            result.put("url", stringOr(cfg.get("url"), "(not configured)"));
            recordDeploymentHistory(run, config.getId(), environment, artifact, "success",
                stringOr(cfg.get("url"), null));
            return result;
        }

        // Resolve {artifact_id} / {artifact_version} / {env} placeholders + standard interpolation.
        String withSubs = substituteArtifactPlaceholders(command, artifact, environment);
        String resolvedCmd = stringInterpolator != null
            ? stringInterpolator.interpolate(withSubs, run, input)
            : withSubs;
        DenyList.assertBashAllowed(resolvedCmd);

        log.info("Deploy run: env={} cmd={} cwd={} timeout={}s", environment, resolvedCmd, workingDir, timeoutSec);
        ShellCommandRunner.ExecResult exec = shellRunner.exec(resolvedCmd, workingDir, timeoutSec);

        result.put("command", resolvedCmd);
        result.put("exit_code", exec.exitCode());
        result.put("stdout", exec.stdout());
        result.put("stderr", exec.stderr());
        result.put("duration_ms", exec.durationMs());
        result.put("url", stringOr(cfg.get("url"), "(not configured)"));

        if (exec.success()) {
            result.put("status", "success");
            result.put("success", true);
            recordDeploymentHistory(run, config.getId(), environment, artifact, "success",
                stringOr(cfg.get("url"), null));
        } else {
            result.put("status", "failed");
            result.put("success", false);
            result.put("error", "Deploy command exited " + exec.exitCode());
        }
        return result;
    }

    /**
     * Persist a row in {@code deployment_history} so {@link com.workflow.blocks.RollbackBlock}
     * can find the previous successful artifact for the same {@code (projectSlug, environment)}.
     * Best-effort: swallows persistence errors so a flaky DB doesn't fail an otherwise-successful deploy.
     */
    private void recordDeploymentHistory(PipelineRun run, String blockId, String environment,
                                          Map<String, Object> artifact, String status, String url) {
        if (deploymentHistoryRepository == null) {
            log.debug("DeploymentHistoryRepository not wired — skipping history record");
            return;
        }
        try {
            DeploymentHistory entry = new DeploymentHistory();
            entry.setProjectSlug(run.getProjectSlug());
            entry.setRunId(run.getId());
            entry.setBlockId(blockId);
            entry.setEnvironment(environment);
            entry.setArtifactId(String.valueOf(artifact.get("artifact_id")));
            Object aver = artifact.get("artifact_version");
            if (aver != null) entry.setArtifactVersion(aver.toString());
            entry.setDeploymentUrl(url);
            entry.setStatus(status);
            deploymentHistoryRepository.save(entry);
            log.info("Recorded deployment history: env={} artifact={} run={}",
                environment, entry.getArtifactId(), run.getId());
        } catch (Exception e) {
            log.warn("Failed to record deployment history for env={}: {}", environment, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveArtifact(Map<String, Object> input) {
        Object direct = input.get("build");
        if (direct instanceof Map<?, ?> m) return (Map<String, Object>) m;
        for (Object val : input.values()) {
            if (val instanceof Map<?, ?> m && ((Map<String, Object>) m).containsKey("artifact_id")) {
                return (Map<String, Object>) m;
            }
        }
        return null;
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
            throw new IllegalArgumentException("DeployBlock: working_dir is not a directory: " + path);
        }
        return path;
    }

    /** Convenience: {artifact_id} / {artifact_version} / {env} substitutions in the command string. */
    private String substituteArtifactPlaceholders(String command, Map<String, Object> artifact, String environment) {
        String result = command;
        Object aid = artifact.get("artifact_id");
        Object aver = artifact.get("artifact_version");
        if (aid  != null) result = result.replace("{artifact_id}", aid.toString());
        if (aver != null) result = result.replace("{artifact_version}", aver.toString());
        result = result.replace("{env}", environment);
        return result;
    }

    private String requireString(Object value, String errorMessage) {
        if (value instanceof String s && !s.isBlank()) return s;
        throw new IllegalArgumentException(errorMessage);
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
