package com.workflow.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.BlockConfig;
import com.workflow.core.BlockOutput;
import com.workflow.core.PipelineRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Final aggregator block. Composes a single structured "what happened in this run"
 * report by reading every prior block's output from {@link PipelineRun#getOutputs()}
 * and pulling the operationally interesting bits.
 *
 * <p>Never blocking: this block always returns a passed status, even if upstream
 * blocks failed. The report makes the failures visible (in {@code flags}); the
 * point is operator clarity, not a second chance to fail the run.
 *
 * <p>Output keys:
 * <pre>
 * pipeline_name, run_id, started_at, completed_at, duration_ms,
 * acceptance_checklist: [...],          # from analysis.acceptance_checklist with ✓/✗
 * test_coverage: { planned, generated, passing },
 * codegen: { files_changed, branch, commit_message },
 * ci: { status, build_time_ms },
 * deploy: { test_env, staging, prod },
 * cost: { total_usd },
 * flags: ["skipped test_gen on fast-path", "escalated to cloud-tier on codegen", ...],
 * final_status: "success" | "with_warnings" | "failed_upstream"
 * </pre>
 *
 * <p>This is Деминговский Act at the run level — operator looks at the report,
 * sees explicit ✓/✗ next to each acceptance criterion, and decides whether
 * the work is done.
 */
@Component
public class RunReportBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(RunReportBlock.class);

    @Autowired private ObjectMapper objectMapper;

    @Override public String getName() { return "run_report"; }

    @Override public String getDescription() {
        return "Финальный отчёт run-а: ✓/✗ по acceptance_checklist, test coverage, "
                + "CI/deploy/cost. Никогда не блокирует. Деминговский Act на уровне run.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
                "Run Report",
                "report",
                Phase.ANY,
                List.of(),
                false,
                Map.of()
        );
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig blockConfig, PipelineRun run) throws Exception {
        Map<String, Map<String, Object>> outputsByBlockId = collectBlockOutputs(run);
        List<String> flags = new ArrayList<>();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("pipeline_name", run.getPipelineName());
        report.put("run_id", run.getId() != null ? run.getId().toString() : "");
        report.put("started_at", run.getStartedAt() != null ? run.getStartedAt().toString() : "");
        report.put("completed_at", Instant.now().toString());
        report.put("duration_ms", durationMs(run));

        report.put("acceptance_checklist", composeAcceptanceChecklist(outputsByBlockId, flags));
        report.put("test_coverage", composeTestCoverage(outputsByBlockId));
        report.put("codegen", composeCodegenSummary(outputsByBlockId));
        report.put("ci", composeCiSummary(outputsByBlockId));
        report.put("deploy", composeDeploySummary(outputsByBlockId));

        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("total_usd", run.getTotalCostUsd());
        report.put("cost", cost);

        // Detect fast-path skips and escalations from the run's prior outputs.
        collectPathFlags(outputsByBlockId, flags);
        collectEscalationFlags(run, flags);
        report.put("flags", flags);
        report.put("final_status", flags.stream().anyMatch(f -> f.startsWith("failed:")) ? "failed_upstream"
                : flags.isEmpty() ? "success" : "with_warnings");

        log.info("run_report[{}]: pipeline={} duration_ms={} flags={}",
                blockConfig.getId(), run.getPipelineName(), report.get("duration_ms"), flags.size());
        return report;
    }

    // ── composition helpers ────────────────────────────────────────────────────

    private Map<String, Map<String, Object>> collectBlockOutputs(PipelineRun run) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        if (run.getOutputs() == null) return map;
        for (BlockOutput bo : run.getOutputs()) {
            if (bo.getBlockId() == null || bo.getBlockId().startsWith("_")) continue;
            if (bo.getOutputJson() == null || bo.getOutputJson().isBlank()) continue;
            try {
                Map<String, Object> parsed = objectMapper.readValue(bo.getOutputJson(),
                        new TypeReference<Map<String, Object>>() {});
                map.put(bo.getBlockId(), parsed);
            } catch (Exception e) {
                log.debug("run_report: failed to parse output for block {}: {}", bo.getBlockId(), e.getMessage());
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> composeAcceptanceChecklist(
            Map<String, Map<String, Object>> outputs, List<String> flags) {
        // Source of truth: analysis.acceptance_checklist (list of items with id+criterion).
        // Status: derive from agent_verify/tech_lead_gate checklist_status if present;
        // otherwise mark "unknown".
        Object analysisOut = outputs.values().stream()
                .map(m -> m.get("acceptance_checklist"))
                .filter(v -> v instanceof List<?> && !((List<?>) v).isEmpty())
                .findFirst().orElse(null);
        if (!(analysisOut instanceof List<?> rawList)) {
            return new ArrayList<>();
        }

        // Look for the freshest checklist_status in any verify/agent_verify output.
        Map<String, Object> statusById = new LinkedHashMap<>();
        for (Map<String, Object> bo : outputs.values()) {
            Object cs = bo.get("checklist_status");
            if (cs instanceof List<?> csl) {
                for (Object item : csl) {
                    if (item instanceof Map<?, ?> m && m.get("id") != null) {
                        statusById.put(m.get("id").toString(), (Object) m);
                    }
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            String id = m.get("id") != null ? m.get("id").toString() : "ac-" + result.size();
            entry.put("id", id);
            Object crit = m.get("criterion");
            if (crit == null) crit = m.get("text");
            entry.put("criterion", crit != null ? crit.toString() : "");
            Object prio = m.get("priority");
            entry.put("priority", prio != null ? prio.toString() : "important");

            Object statusObj = statusById.get(id);
            if (statusObj instanceof Map<?, ?> sm) {
                boolean passed = sm.get("passed") instanceof Boolean b ? b : false;
                entry.put("status", passed ? "pass" : "fail");
                Object evidence = sm.get("evidence");
                if (evidence != null) entry.put("evidence", evidence.toString());
                if (!passed) flags.add("acceptance not met: " + id);
            } else {
                entry.put("status", "unknown");
            }
            result.add(entry);
        }
        return result;
    }

    private Map<String, Object> composeTestCoverage(Map<String, Map<String, Object>> outputs) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> testPlan = outputs.get("test_planning");
        Map<String, Object> testGen = outputs.get("test_generation");
        if (testPlan != null && testPlan.get("cases") instanceof List<?> cases) {
            out.put("planned", cases.size());
        }
        if (testGen != null) {
            Object generated = testGen.get("tests_generated");
            if (generated instanceof Number n) out.put("generated", n.intValue());
            else if (generated instanceof List<?> l) out.put("generated", l.size());
            Object passing = testGen.get("tests_passing");
            if (passing instanceof Number n) out.put("passing", n.intValue());
        }
        return out;
    }

    private Map<String, Object> composeCodegenSummary(Map<String, Map<String, Object>> outputs) {
        Map<String, Object> codegen = outputs.get("codegen");
        if (codegen == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        if (codegen.get("branch_name") != null) out.put("branch", codegen.get("branch_name").toString());
        if (codegen.get("commit_message") != null) out.put("commit_message", codegen.get("commit_message").toString());
        if (codegen.get("changes") instanceof List<?> c) out.put("files_changed", c.size());
        if (codegen.get("diff_summary") != null) out.put("diff_summary", codegen.get("diff_summary").toString());
        return out;
    }

    private Map<String, Object> composeCiSummary(Map<String, Map<String, Object>> outputs) {
        // CI block ids vary by pipeline — look for common shapes.
        for (String blockId : List.of("ci", "github_actions", "gitlab_ci")) {
            Map<String, Object> ci = outputs.get(blockId);
            if (ci != null) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("block_id", blockId);
                if (ci.get("status") != null) out.put("status", ci.get("status").toString());
                if (ci.get("duration_ms") != null) out.put("duration_ms", ci.get("duration_ms"));
                return out;
            }
        }
        return Map.of();
    }

    private Map<String, Object> composeDeploySummary(Map<String, Map<String, Object>> outputs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String env : List.of("deploy_test", "deploy_staging", "deploy_prod")) {
            Map<String, Object> b = outputs.get(env);
            if (b != null && b.get("status") != null) out.put(env, b.get("status").toString());
        }
        return out;
    }

    private void collectPathFlags(Map<String, Map<String, Object>> outputs, List<String> flags) {
        Map<String, Object> intake = outputs.get("intake_assessment");
        if (intake != null && "fast".equals(intake.get("recommended_path"))) {
            flags.add("fast-path: skipped clarification/test_planning/tech_lead_gate");
        }
        Map<String, Object> preflight = outputs.get("preflight");
        if (preflight != null) {
            Object status = preflight.get("status");
            if ("RED_BLOCKED".equals(status) || "WARNING".equals(status)) {
                flags.add("preflight: " + status);
            }
        }
    }

    private void collectEscalationFlags(PipelineRun run, List<String> flags) {
        String overrides = run.getRuntimeOverridesJson();
        if (overrides == null || overrides.isBlank() || "{}".equals(overrides)) return;
        try {
            Map<String, Object> map = objectMapper.readValue(overrides,
                    new TypeReference<Map<String, Object>>() {});
            if (!map.isEmpty()) {
                flags.add("escalated to cloud-tier on: " + String.join(", ", map.keySet()));
            }
        } catch (Exception ignored) {}
    }

    private long durationMs(PipelineRun run) {
        if (run.getStartedAt() == null) return 0L;
        Instant end = run.getCompletedAt() != null ? run.getCompletedAt() : Instant.now();
        return Duration.between(run.getStartedAt(), end).toMillis();
    }
}
