package com.workflow.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.PipelineConfig;
import com.workflow.config.PipelineConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Paths;
import java.util.List;

/**
 * On startup, resumes any runs that were RUNNING or PAUSED_FOR_APPROVAL when the JVM
 * was killed (e.g. container restart). State is recovered from the H2 database;
 * the pipeline config is restored from the run's configSnapshotJson.
 *
 * PAUSED_FOR_APPROVAL runs are re-entered: the pipeline thread restarts, re-runs the
 * block's LLM call, and pauses again at the approval gate. This is safe because the
 * previous block output is overwritten only after the block finishes — if the run was
 * paused waiting for approval, the output is already in DB and gets re-saved with the
 * same value.
 */
@Component
public class StartupRunRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRunRecovery.class);

    @Autowired private PipelineRunRepository runRepository;
    @Autowired private PipelineRunner pipelineRunner;
    @Autowired private PipelineConfigLoader configLoader;
    @Autowired private ObjectMapper objectMapper;

    @Value("${workflow.config-dir:./config}")
    private String configDir;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverStuckRuns() {
        List<PipelineRun> stuck = runRepository.findByStatusIn(
            List.of(RunStatus.RUNNING, RunStatus.PAUSED_FOR_APPROVAL));

        if (stuck.isEmpty()) {
            log.info("StartupRunRecovery: no stuck runs found");
            return;
        }

        log.info("StartupRunRecovery: found {} stuck run(s) — resuming", stuck.size());

        for (PipelineRun run : stuck) {
            try {
                // Force-load lazy collections while still in JPA session
                run.getCompletedBlocks().size();
                run.getAutoApprove().size();
                run.getLoopIterations().size();
                run.getOutputs().forEach(o -> o.getBlockId());

                PipelineConfig config = resolveConfig(run);
                if (config == null) {
                    log.warn("StartupRunRecovery: cannot resolve config for run {} (pipeline={}), skipping",
                        run.getId(), run.getPipelineName());
                    continue;
                }

                run.setStatus(RunStatus.RUNNING);
                runRepository.save(run);

                pipelineRunner.resume(config, run.getId().toString());
                log.info("StartupRunRecovery: resumed run {} (pipeline={}, currentBlock={})",
                    run.getId(), run.getPipelineName(), run.getCurrentBlock());

            } catch (Exception e) {
                log.error("StartupRunRecovery: failed to resume run {}: {}", run.getId(), e.getMessage(), e);
            }
        }
    }

    private PipelineConfig resolveConfig(PipelineRun run) {
        if (run.getConfigSnapshotJson() != null && !run.getConfigSnapshotJson().isBlank()) {
            try {
                return objectMapper.readValue(run.getConfigSnapshotJson(), PipelineConfig.class);
            } catch (Exception e) {
                log.warn("StartupRunRecovery: failed to parse snapshot for run {}: {}", run.getId(), e.getMessage());
            }
        }
        String name = run.getPipelineName();
        if (name == null) return null;
        try {
            for (var path : configLoader.listConfigs(Paths.get(configDir))) {
                try {
                    PipelineConfig c = configLoader.load(path);
                    if (name.equals(c.getName())) return c;
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("StartupRunRecovery: failed to scan configs for run {}: {}", run.getId(), e.getMessage());
        }
        return null;
    }
}
