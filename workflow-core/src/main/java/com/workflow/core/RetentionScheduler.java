package com.workflow.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Periodically purges old pipeline runs per retention policy.
 *
 * <p>Defaults (configurable via {@code workflow.retention.*}):
 * <ul>
 *   <li>Completed runs: 90 days</li>
 *   <li>Failed runs: 180 days (longer for debugging)</li>
 *   <li>Runs that reached deploy_prod: 2 years (compliance)</li>
 * </ul>
 *
 * <p>MVP deletes directly; archival to S3 (designed in Q43) is a follow-up.
 */
@Component
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    @Autowired
    private PipelineRunRepository runRepository;

    @Value("${workflow.retention.completed-days:90}")
    private int completedRetentionDays;

    @Value("${workflow.retention.failed-days:180}")
    private int failedRetentionDays;

    @Value("${workflow.retention.prod-days:730}")
    private int prodRetentionDays;

    /** Runs daily at 03:00 UTC. */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @Transactional
    public void purgeOldRuns() {
        Instant now = Instant.now();
        int deleted = 0;
        deleted += purgeByStatusOlderThan(RunStatus.COMPLETED, now.minus(completedRetentionDays, ChronoUnit.DAYS));
        deleted += purgeByStatusOlderThan(RunStatus.FAILED, now.minus(failedRetentionDays, ChronoUnit.DAYS));
        if (deleted > 0) log.info("Retention purge removed {} run(s)", deleted);
    }

    private int purgeByStatusOlderThan(RunStatus status, Instant threshold) {
        List<PipelineRun> candidates = runRepository.findAll().stream()
            .filter(r -> r.getStatus() == status)
            .filter(r -> r.getCompletedAt() != null && r.getCompletedAt().isBefore(threshold))
            .filter(r -> !touchedProd(r))  // keep prod-deploy runs longer
            .toList();
        if (candidates.isEmpty()) return 0;
        runRepository.deleteAll(candidates);
        return candidates.size();
    }

    private boolean touchedProd(PipelineRun run) {
        return run.getCompletedBlocks().contains("deploy_prod")
            || run.getCompletedBlocks().contains("verify_prod");
    }
}
