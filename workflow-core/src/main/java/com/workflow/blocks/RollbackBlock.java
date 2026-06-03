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
import java.util.Optional;
import java.util.Set;

@Component
public class RollbackBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(RollbackBlock.class);
    private static final Set<String> VALID_STRATEGIES = Set.of("auto", "manual", "forward_fix");

    @Autowired(required = false) private StringInterpolator stringInterpolator;
    @Autowired(required = false) private ProjectRepository projectRepository;
    @Autowired(required = false) private ShellCommandRunner shellRunner;
    @Autowired(required = false) private DeploymentHistoryRepository deploymentHistoryRepository;

    @Override
    public String getName() {
        return "rollback";
    }

    @Override
    public String getDescription() {
        return "Откатывает деплой. Если задан 'command' — выполняет rollback-команду (kubectl rollout undo / helm rollback / "
            + "argocd app rollback) в workingDir. strategy=forward_fix пропускает блок (фиксим вперёд).";
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

        String strategy = stringOr(cfg.get("strategy"), "manual");
        if (!VALID_STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("RollbackBlock: invalid strategy '" + strategy
                + "' (expected auto|manual|forward_fix)");
        }

        String environment = stringOr(cfg.get("environment"), "prod");

        if ("forward_fix".equals(strategy)) {
            log.info("Rollback strategy = forward_fix — no rollback performed, expected fix-forward in next iteration");
            Map<String, Object> skipped = new HashMap<>();
            skipped.put("strategy", strategy);
            skipped.put("environment", environment);
            skipped.put("status", "skipped");
            skipped.put("success", true);
            skipped.put("reason", "forward_fix requested");
            return skipped;
        }

        Map<String, Object> previousDeploy = resolvePreviousDeploy(input, cfg, run, environment);
        String command = stringOr(cfg.get("command"), null);
        int timeoutSec = intOr(cfg.get("timeout_sec"), 600);
        Path workingDir = resolveWorkingDir(cfg);

        Map<String, Object> result = new HashMap<>();
        result.put("strategy", strategy);
        result.put("environment", environment);
        result.put("previous_artifact_id", previousDeploy != null ? previousDeploy.get("artifact_id") : null);
        result.put("rolled_back_at", Instant.now().toString());

        if (command == null || shellRunner == null) {
            log.info("Rollback placeholder (no command): env={}, target={}",
                environment, previousDeploy != null ? previousDeploy.get("artifact_id") : "unknown");
            result.put("status", "success");
            result.put("success", true);
            result.put("placeholder", true);
            return result;
        }

        String prevArtifactId = previousDeploy != null && previousDeploy.get("artifact_id") != null
            ? previousDeploy.get("artifact_id").toString() : "";
        String withSubs = command
            .replace("{previous_artifact_id}", prevArtifactId)
            .replace("{env}", environment);
        String resolvedCmd = stringInterpolator != null
            ? stringInterpolator.interpolate(withSubs, run, input)
            : withSubs;
        DenyList.assertBashAllowed(resolvedCmd);

        log.info("Rollback run: env={} strategy={} cmd={} cwd={}", environment, strategy, resolvedCmd, workingDir);
        ShellCommandRunner.ExecResult exec = shellRunner.exec(resolvedCmd, workingDir, timeoutSec);

        result.put("command", resolvedCmd);
        result.put("exit_code", exec.exitCode());
        result.put("stdout", exec.stdout());
        result.put("stderr", exec.stderr());
        result.put("duration_ms", exec.durationMs());

        if (exec.success()) {
            result.put("status", "success");
            result.put("success", true);
        } else {
            result.put("status", "failed");
            result.put("success", false);
            result.put("error", "Rollback command exited " + exec.exitCode());
        }
        return result;
    }

    /**
     * Resolution cascade for the previous artifact to roll back to:
     * <ol>
     *   <li>Explicit {@code previous_artifact_id} in block config (operator override).</li>
     *   <li>{@code deployment_history} table — the most recent successful deploy for the same
     *       {@code (projectSlug, environment)} that <em>isn't</em> the currently-broken one
     *       (we exclude the artifact_id present in the upstream {@code build}/{@code deploy} input).</li>
     *   <li>Any block output in {@code input} carrying an {@code artifact_id} (legacy fallback).</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolvePreviousDeploy(Map<String, Object> input, Map<String, Object> cfg,
                                                        PipelineRun run, String environment) {
        Object explicit = cfg.get("previous_artifact_id");
        if (explicit instanceof String s && !s.isBlank()) {
            Map<String, Object> synth = new HashMap<>();
            synth.put("artifact_id", s);
            synth.put("source", "config_override");
            return synth;
        }
        if (deploymentHistoryRepository != null) {
            String currentArtifactId = resolveCurrentArtifactId(input);
            Optional<DeploymentHistory> previous = currentArtifactId != null
                ? deploymentHistoryRepository.findPreviousSuccess(environment, run.getProjectSlug(), currentArtifactId)
                : deploymentHistoryRepository.findLatestSuccess(environment, run.getProjectSlug());
            if (previous.isPresent()) {
                DeploymentHistory h = previous.get();
                Map<String, Object> synth = new HashMap<>();
                synth.put("artifact_id", h.getArtifactId());
                synth.put("artifact_version", h.getArtifactVersion());
                synth.put("deployment_url", h.getDeploymentUrl());
                synth.put("deployed_at", h.getDeployedAt() != null ? h.getDeployedAt().toString() : null);
                synth.put("source", "deployment_history");
                log.info("Rollback target resolved from deployment_history: env={} artifact={}",
                    environment, h.getArtifactId());
                return synth;
            }
        }
        for (Object val : input.values()) {
            if (val instanceof Map<?, ?> m && ((Map<String, Object>) m).containsKey("artifact_id")) {
                Map<String, Object> synth = new HashMap<>((Map<String, Object>) m);
                synth.put("source", "input_fallback");
                return synth;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String resolveCurrentArtifactId(Map<String, Object> input) {
        // The "currently broken" artifact came from either upstream build or upstream deploy.
        for (String key : new String[]{"deploy", "build"}) {
            Object val = input.get(key);
            if (val instanceof Map<?, ?> m) {
                Object aid = ((Map<String, Object>) m).get("artifact_id");
                if (aid instanceof String s && !s.isBlank()) return s;
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
            throw new IllegalArgumentException("RollbackBlock: working_dir is not a directory: " + path);
        }
        return path;
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
