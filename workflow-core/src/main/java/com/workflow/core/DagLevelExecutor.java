package com.workflow.core;

import com.workflow.config.BlockConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Level-based parallel execution scheduler for pipeline DAGs.
 *
 * <p>Walks {@code depends_on} to partition blocks into level layers (Kahn's algorithm):
 * level 0 = blocks with no unsatisfied deps, level 1 = blocks whose deps are all in
 * level 0 ∪ alreadyCompleted, and so on. Within a level, blocks are independent and
 * can be executed in parallel.
 *
 * <p>Current integration status: <b>compute-only</b>. {@link PipelineRunner} still
 * runs blocks sequentially via its main while-loop. Wiring this scheduler is a future
 * change gated behind {@code workflow.parallel-execution.enabled} because parallel
 * execution interacts non-trivially with:
 * <ul>
 *   <li>{@link com.workflow.core.ApprovalGate} — sequential by design (operator pauses
 *       are a serializing point)</li>
 *   <li>Loopback / escalation — resetting completed-blocks must be coordinated with
 *       in-flight parallel work to avoid lost-update races on {@link PipelineRun}'s
 *       mutable state</li>
 *   <li>WebSocket events — current handlers assume single-block-at-a-time semantics
 *       per run; parallel events need ordering guarantees the UI doesn't currently expect</li>
 *   <li>{@link com.workflow.core.BlockOutput} writes share a transactional context</li>
 * </ul>
 *
 * <p>Until those concerns are resolved per-block, this class is exposed for tests and
 * for downstream tooling (validators, run-visualization) that benefits from level
 * detection independently of execution. Operators can read levels from
 * {@code GET /api/pipelines/{path}/levels} (future endpoint) to spot accidentally-serial
 * topologies in their YAML.
 */
@Service
public class DagLevelExecutor {

    private static final Logger log = LoggerFactory.getLogger(DagLevelExecutor.class);

    @Value("${workflow.parallel-execution.enabled:false}")
    private boolean parallelExecutionEnabled;

    /**
     * Computes execution levels for a topologically valid block list. A block goes in
     * the smallest level greater than the maximum of its dependencies' levels.
     * {@code alreadyCompleted} models a partial run (resume case) — blocks in this set
     * count as already-satisfied dependencies even when absent from {@code sortedBlocks}.
     *
     * @return list of level groups in execution order; each group is independent within
     *         itself. Returns an empty list when {@code sortedBlocks} is null/empty.
     */
    public List<List<BlockConfig>> computeLevels(List<BlockConfig> sortedBlocks,
                                                   Set<String> alreadyCompleted) {
        if (sortedBlocks == null || sortedBlocks.isEmpty()) return List.of();
        Set<String> completed = alreadyCompleted == null ? Set.of() : alreadyCompleted;

        Map<String, BlockConfig> byId = new HashMap<>();
        for (BlockConfig b : sortedBlocks) byId.put(b.getId(), b);

        Map<String, Integer> levelOf = new HashMap<>();
        for (BlockConfig b : sortedBlocks) {
            int lvl = 0;
            List<String> deps = b.getDependsOn() != null ? b.getDependsOn() : List.of();
            for (String dep : deps) {
                if (completed.contains(dep)) continue;
                if (!byId.containsKey(dep)) {
                    // Dependency outside this run window (e.g. injected output) — treat as level 0.
                    continue;
                }
                Integer depLvl = levelOf.get(dep);
                if (depLvl == null) {
                    // Forward reference — caller passed an unsorted list; degrade to sequential
                    // by giving up on level detection.
                    log.warn("DagLevelExecutor.computeLevels: forward reference {}→{}, blocks not topologically sorted",
                        b.getId(), dep);
                    return wrapEachAsLevel(sortedBlocks);
                }
                lvl = Math.max(lvl, depLvl + 1);
            }
            levelOf.put(b.getId(), lvl);
        }

        // Group by level number while preserving the input order inside each level.
        int maxLevel = levelOf.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        List<List<BlockConfig>> levels = new ArrayList<>(Math.max(0, maxLevel + 1));
        for (int i = 0; i <= maxLevel; i++) levels.add(new ArrayList<>());
        for (BlockConfig b : sortedBlocks) {
            levels.get(levelOf.get(b.getId())).add(b);
        }
        return levels;
    }

    /**
     * True when every block in {@code level} is safe to run concurrently with its
     * peers under the current PipelineRunner contract:
     * <ul>
     *   <li>No approval gates (those need serial operator attention)</li>
     *   <li>No loopback / on_failure targets that point at peer blocks</li>
     *   <li>No verify configuration with on_fail.action=loopback (single in-flight retry)</li>
     * </ul>
     *
     * <p>When this returns false, the caller should run the level sequentially.
     */
    public boolean canParallelizeLevel(List<BlockConfig> level) {
        if (level == null || level.size() < 2) return false;
        Set<String> levelIds = new HashSet<>();
        for (BlockConfig b : level) levelIds.add(b.getId());

        for (BlockConfig b : level) {
            // Opt-in gate: parallel execution only fires when every block at the level
            // explicitly declares parallel:true. This prevents accidental concurrency for
            // pipelines that rely on side-effect ordering between independent siblings.
            if (!b.isParallel()) return false;

            if (b.isApproval() || b.getApprovalMode() != null
                    && !"auto".equalsIgnoreCase(b.getApprovalMode().name())) {
                return false;
            }
            if (b.getVerify() != null && b.getVerify().getOnFail() != null) {
                String target = b.getVerify().getOnFail().getTarget();
                if (target != null && levelIds.contains(target)) return false;
            }
            if (b.getOnFailure() != null) {
                String target = b.getOnFailure().getTarget();
                if (target != null && levelIds.contains(target)) return false;
            }
            // Required gates block concurrent firing — they're evaluated against shared
            // mutable run state which we don't want to race over.
            if (b.getRequiredGates() != null && !b.getRequiredGates().isEmpty()) return false;
        }
        return true;
    }

    /** Whether the feature flag {@code workflow.parallel-execution.enabled} is on. */
    public boolean isEnabled() { return parallelExecutionEnabled; }

    /** Used by tests to inject the flag value without booting a Spring context. */
    void setParallelExecutionEnabled(boolean enabled) { this.parallelExecutionEnabled = enabled; }

    private List<List<BlockConfig>> wrapEachAsLevel(List<BlockConfig> blocks) {
        List<List<BlockConfig>> result = new ArrayList<>(blocks.size());
        for (BlockConfig b : blocks) result.add(List.of(b));
        return Collections.unmodifiableList(result);
    }

    /** Distinct unique IDs across the level list (helper for tests / introspection). */
    public Set<String> idsAcrossLevels(List<List<BlockConfig>> levels) {
        Set<String> ids = new LinkedHashSet<>();
        for (List<BlockConfig> lvl : levels) {
            for (BlockConfig b : lvl) ids.add(b.getId());
        }
        return ids;
    }
}
