package com.workflow.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class KillSwitchService {

    private static final Logger log = LoggerFactory.getLogger(KillSwitchService.class);

    @Autowired
    private KillSwitchRepository killSwitchRepository;

    @Autowired
    private PipelineRunRepository runRepository;

    @Autowired
    private PipelineRunner pipelineRunner;

    public KillSwitch current() {
        String scope = com.workflow.project.ProjectContext.get();
        return killSwitchRepository.findByProjectSlug(scope).orElseGet(() -> {
            KillSwitch empty = new KillSwitch();
            empty.setProjectSlug(scope);
            empty.setActive(false);
            return empty;
        });
    }

    @Transactional
    public KillSwitch activate(String reason, String actor, boolean cancelActive) {
        String scope = com.workflow.project.ProjectContext.get();
        KillSwitch ks = killSwitchRepository.findByProjectSlug(scope).orElseGet(KillSwitch::new);
        ks.setProjectSlug(scope);
        ks.setActive(true);
        ks.setReason(reason);
        ks.setActivatedBy(actor);
        ks.setActivatedAt(Instant.now());
        killSwitchRepository.save(ks);
        log.warn("Kill switch ACTIVATED for project {} by {} — reason: {} (cancelActive={})",
            scope, actor, reason, cancelActive);

        if (cancelActive) cancelActiveRunsForProject(scope);
        return ks;
    }

    @Transactional
    public KillSwitch deactivate(String actor) {
        String scope = com.workflow.project.ProjectContext.get();
        KillSwitch ks = killSwitchRepository.findByProjectSlug(scope).orElseGet(KillSwitch::new);
        ks.setProjectSlug(scope);
        ks.setActive(false);
        ks.setReason(null);
        ks.setActivatedBy(null);
        ks.setActivatedAt(null);
        killSwitchRepository.save(ks);
        log.info("Kill switch DEACTIVATED for project {} by {}", scope, actor);
        return ks;
    }

    private void cancelActiveRunsForProject(String scope) {
        List<PipelineRun> active = runRepository.findAll().stream()
            .filter(r -> scope.equals(r.getProjectSlug()))
            .filter(r -> r.getStatus() == RunStatus.RUNNING || r.getStatus() == RunStatus.PAUSED_FOR_APPROVAL)
            .toList();
        for (PipelineRun run : active) {
            try {
                pipelineRunner.cancelRun(run.getId());
            } catch (Exception e) {
                log.error("Failed to cancel run {} during kill-switch activation: {}", run.getId(), e.getMessage());
            }
        }
        log.warn("Kill switch cancelled {} active run(s) in project {}", active.size(), scope);
    }
}
