package com.workflow.preflight;

import com.workflow.blocks.Block;
import com.workflow.config.BlockConfig;
import com.workflow.config.PipelineConfig;
import com.workflow.config.ValidationError;
import com.workflow.config.ValidationResult;
import com.workflow.core.BlockRegistry;
import com.workflow.core.PipelineRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Pre-run environment gate. Gathers {@link Requirement}s from the blocks that will actually run,
 * verifies them in parallel against the live environment, and returns a {@link ValidationResult}
 * in the same envelope shape as {@code PipelineConfigValidator} — so {@code RunController} can reject
 * a start (HTTP 422) before any {@link com.workflow.core.PipelineRun} is created, with a precise list
 * of what is wrong.
 *
 * <p>Verdict: the start is blocked iff there is at least one hard failure. Hard failures from
 * {@code condition:}-gated blocks are downgraded to WARN (the block may not run); reachability and
 * inconclusive probes are always WARN.
 */
@Component
public class RunDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(RunDiagnostics.class);

    /** Overall wall-clock budget across all parallel checks. Backstops a hung probe. */
    private static final long OVERALL_DEADLINE_SECONDS = 5;

    private final BlockRegistry blockRegistry;
    private final RequirementChecker checker;
    private final com.workflow.observability.PipelineMetrics metrics;

    public RunDiagnostics(BlockRegistry blockRegistry,
                          RequirementChecker checker,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          com.workflow.observability.PipelineMetrics metrics) {
        this.blockRegistry = blockRegistry;
        this.checker = checker;
        this.metrics = metrics;
    }

    /** A requirement together with whether every block that declared it was {@code condition:}-gated. */
    private record Gathered(Requirement req, boolean conditional) {}

    /**
     * Run the gate.
     *
     * @param config    the loaded pipeline
     * @param fromBlock entry-point block id ({@code null} = run from the start)
     * @param ctx       run-start environment snapshot
     */
    public ValidationResult diagnose(PipelineConfig config, String fromBlock, PreflightContext ctx) {
        Map<String, Gathered> deduped = gather(config, fromBlock, ctx);
        if (deduped.isEmpty()) return ValidationResult.ok();

        List<Gathered> reqs = new ArrayList<>(deduped.values());
        List<CheckResult> results = runChecks(reqs, ctx);

        List<ValidationError> errors = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            CheckResult result = results.get(i);
            boolean conditional = reqs.get(i).conditional();
            ValidationError err = toError(result, conditional);
            if (err != null) {
                errors.add(err);
                record(err);
            }
        }
        return ValidationResult.of(errors);
    }

    /** Collect, tag and dedupe requirements from the reachable, enabled blocks plus the run-level base. */
    Map<String, Gathered> gather(PipelineConfig config, String fromBlock, PreflightContext ctx) {
        Set<String> before = fromBlock != null
                ? PipelineRunner.blockIdsBefore(config, fromBlock)
                : Set.of();

        List<Gathered> gathered = new ArrayList<>();
        // Base: the run-level LLM provider is always exercised.
        if (ctx.provider() != null) {
            gathered.add(new Gathered(new Requirement.Provider(ctx.provider()), false));
        }

        for (BlockConfig bc : config.getPipeline()) {
            if (!bc.isEnabled()) continue;
            if (before.contains(bc.getId())) continue; // injected / already-completed upstream
            Block block = blockRegistry.get(bc.getBlock());
            if (block == null) continue;

            boolean conditional = bc.getCondition() != null && !bc.getCondition().isBlank();
            List<Requirement> declared;
            try {
                declared = block.preflightRequirements(bc, ctx);
            } catch (Exception e) {
                log.warn("Block {} preflightRequirements threw — skipping its requirements: {}",
                        bc.getId(), e.toString());
                continue;
            }
            if (declared == null) continue;
            for (Requirement r : declared) {
                if (r == null) continue;
                gathered.add(new Gathered(r.withBlockId(bc.getId()), conditional));
            }
        }

        // Dedupe by key; a hard (non-conditional) emitter wins over a conditional one.
        Map<String, Gathered> deduped = new LinkedHashMap<>();
        for (Gathered g : gathered) {
            deduped.merge(g.req().dedupKey(), g,
                    (a, b) -> new Gathered(a.req(), a.conditional() && b.conditional()));
        }
        return deduped;
    }

    /** Run every requirement check on its own virtual thread, bounded by an overall deadline. */
    private List<CheckResult> runChecks(List<Gathered> reqs, PreflightContext ctx) {
        List<CheckResult> results = new ArrayList<>(reqs.size());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<CheckResult>> tasks = new ArrayList<>(reqs.size());
            for (Gathered g : reqs) {
                tasks.add(() -> checker.check(g.req(), ctx));
            }
            List<Future<CheckResult>> futures =
                    pool.invokeAll(tasks, OVERALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                Future<CheckResult> f = futures.get(i);
                Requirement req = reqs.get(i).req();
                try {
                    results.add(f.get());
                } catch (Exception e) {
                    // Cancelled by deadline or threw — inconclusive, never blocks the start.
                    results.add(CheckResult.softFail(req, "check did not complete in time"));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Fail-open: if the diagnostic itself is interrupted, do not block the run.
            for (Gathered g : reqs) {
                results.add(CheckResult.pass(g.req()));
            }
        }
        return results;
    }

    /** Map a raw check outcome to a {@link ValidationError}, applying the conditional downgrade. */
    private ValidationError toError(CheckResult result, boolean conditional) {
        Requirement req = result.requirement();
        switch (result.kind()) {
            case PASS:
                return null;
            case SOFT_FAIL: {
                String code = "PREFLIGHT_" + req.code() + "_UNREACHABLE";
                return ValidationError.warn(code, message(req, result), null, req.blockId());
            }
            case HARD_FAIL: {
                String code = "PREFLIGHT_" + req.code() + "_MISSING";
                String msg = message(req, result);
                // condition:-gated block — the requirement may never actually be needed → WARN.
                return conditional
                        ? ValidationError.warn(code, msg + " (block is conditional)", null, req.blockId())
                        : ValidationError.error(code, msg, null, req.blockId());
            }
            default:
                return null;
        }
    }

    private String message(Requirement req, CheckResult result) {
        String where = req.blockId() != null ? " [block " + req.blockId() + "]" : "";
        String detail = result.detail() != null ? result.detail() : req.label();
        return detail + where;
    }

    private void record(ValidationError err) {
        if (metrics == null) return;
        String reqCode = err.code().replaceFirst("^PREFLIGHT_", "").replaceFirst("_(MISSING|UNREACHABLE)$", "");
        if (err.severity() == com.workflow.config.Severity.ERROR) {
            metrics.recordPreflightBlock(reqCode);
        } else {
            metrics.recordPreflightWarning(reqCode);
        }
    }
}
