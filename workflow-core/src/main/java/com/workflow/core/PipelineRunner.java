package com.workflow.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.api.RunWebSocketHandler;
import com.workflow.blocks.Block;
import com.workflow.config.ApprovalMode;
import com.workflow.config.BlockConfig;
import com.workflow.config.GateConfig;
import com.workflow.config.IntegrationsConfig;
import com.workflow.config.InvalidPipelineException;
import com.workflow.config.OnFailureConfig;
import com.workflow.config.PipelineConfig;
import com.workflow.config.PipelineConfigValidator;
import com.workflow.config.ValidationResult;
import com.workflow.config.VerifyConfig;
import com.workflow.model.IntegrationConfig;
import com.workflow.model.IntegrationConfigRepository;
import com.workflow.model.IntegrationType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    @Autowired
    private List<Block> allBlocks;

    @Autowired
    private PipelineRunRepository runRepository;

    @Autowired
    private BlockOutputRepository blockOutputRepository;

    @Autowired(required = false)
    private BlockCacheService blockCacheService;

    @Autowired(required = false)
    private com.workflow.llm.LlmCallRepository llmCallRepository;

    @Autowired
    private ApprovalGate approvalGate;

    @Autowired
    private IntegrationConfigRepository integrationConfigRepository;

    @Autowired
    private com.workflow.integrations.IntegrationResolver integrationResolver;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private com.workflow.project.ProjectRepository projectRepository;

    @Autowired(required = false)
    private RunWebSocketHandler wsHandler;

    @Autowired(required = false)
    private com.workflow.notifications.NotificationChannelRegistry notificationChannelRegistry;

    @Autowired(required = false)
    private com.workflow.notifications.NotificationRateLimiter notificationRateLimiter;

    @Autowired
    private AgentProfileResolver agentProfileResolver;

    @Autowired(required = false)
    private com.workflow.observability.PipelineMetrics metrics;

    @Autowired
    private ProdDeployMutex prodDeployMutex;

    @Autowired
    private com.workflow.project.TechStackPromptEnricher techStackPromptEnricher;

    @Autowired
    private PipelineConfigValidator pipelineConfigValidator;

    @Autowired
    private EscalationService escalationService;

    @Autowired
    private NoProgressDetector noProgressDetector;

    @Autowired(required = false)
    private DagLevelExecutor dagLevelExecutor;

    @Autowired(required = false)
    private com.workflow.tools.BashApprovalGate bashApprovalGate;

    @Autowired
    private EscalationProperties escalationProperties;

    private Map<String, Block> blockRegistry;

    @Autowired(required = false)
    private com.workflow.knowledge.ProjectIndexService projectIndexService;

    @Autowired(required = false)
    private com.workflow.workspace.WorkspaceProvisioner workspaceProvisioner;

    /** Tracks virtual threads running active pipelines for cancellation */
    private final ConcurrentHashMap<UUID, Thread> runningThreads = new ConcurrentHashMap<>();

    /**
     * Opt-in (default OFF) enforcement of {@code block.timeout_seconds} on the block's own
     * execution, not just the approval gate. Gated behind a property for one release because
     * cancelling a running block interrupts a worker thread that may hold an MCP/CLI subprocess
     * or an in-flight HTTP call — blocks must honour interruption for this to be clean.
     * Flip {@code workflow.runtime.block-timeout-enabled=true} once validated in staging.
     */
    @Value("${workflow.runtime.block-timeout-enabled:false}")
    private boolean blockTimeoutEnabled;

    /**
     * Tears down a run's repo sandbox once it reaches a terminal state. Skipped for
     * PAUSED runs — a resume needs the workspace intact. Crashed / cancelled runs that
     * never hit this path are swept later by retention.
     */
    private void cleanupWorkspaceIfTerminal(UUID runId) {
        if (workspaceProvisioner == null) return;
        try {
            runRepository.findById(runId).ifPresent(run -> {
                if (run.getWorkspaceDir() != null
                        && (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.FAILED)) {
                    workspaceProvisioner.cleanup(run);
                }
            });
        } catch (Exception e) {
            log.warn("Workspace cleanup failed for run {}: {}", runId, e.getMessage());
        }
    }

    @PostConstruct
    public void buildRegistry() {
        blockRegistry = new HashMap<>();
        for (Block block : allBlocks) {
            blockRegistry.put(block.getName(), block);
        }
        log.info("Registered {} blocks: {}", blockRegistry.size(), blockRegistry.keySet());
    }

    public CompletableFuture<Void> run(PipelineConfig config, String requirement, UUID runId) {
        return run(config, requirement, runId, false, null);
    }

    public CompletableFuture<Void> run(PipelineConfig config, String requirement, UUID runId, boolean dryRun) {
        return run(config, requirement, runId, dryRun, null);
    }

    public CompletableFuture<Void> run(PipelineConfig config, String requirement, UUID runId,
                                        boolean dryRun, Map<String, Object> runInputs) {
        // Capture caller's tenant from the POST-handler thread BEFORE we spawn the
        // run-thread — Spring Data JPA's save() takes the merge() path for our
        // pre-id'd PipelineRun (UUID set by RunController) so @PrePersist never
        // fires and would never populate accountId on its own.
        Long callerAccountId = com.workflow.account.TenantContext.get();
        PipelineRun pipelineRun = PipelineRun.builder()
            .id(runId)
            .pipelineName(config.getName())
            .requirement(requirement)
            .status(RunStatus.RUNNING)
            .startedAt(java.time.Instant.now())
            .completedBlocks(new java.util.LinkedHashSet<>())
            .autoApprove(new java.util.LinkedHashSet<>())
            .outputs(new ArrayList<>())
            .build();
        pipelineRun.setDryRun(dryRun);
        pipelineRun.setProjectSlug(com.workflow.project.ProjectContext.get());
        if (callerAccountId != null) pipelineRun.setAccountId(callerAccountId);
        if (runInputs != null && runInputs.get("_workspaceDir") instanceof String wd0 && !wd0.isBlank()) {
            pipelineRun.setWorkspaceDir(wd0);
        }
        if (runInputs != null && !runInputs.isEmpty()) {
            try { pipelineRun.setRunInputsJson(objectMapper.writeValueAsString(runInputs)); }
            catch (Exception e) { log.warn("Failed to serialize runInputs: {}", e.getMessage()); }
            if (Boolean.TRUE.equals(runInputs.get("_autoApproveAll"))) {
                pipelineRun.getAutoApprove().add("*");
                if (bashApprovalGate != null) bashApprovalGate.autoApproveRun(runId);
                log.info("Auto-approve-all enabled for run {}", runId);
            }
        }
        techStackPromptEnricher.enrich(config, pipelineRun.getProjectSlug());
        captureConfigSnapshot(pipelineRun, config);
        runRepository.save(pipelineRun);

        // Notify global subscribers (e.g. active-runs badge) that a new run has started.
        if (wsHandler != null) wsHandler.sendRunStarted(runId);
        if (metrics != null) metrics.recordRunStarted();

        CompletableFuture<Void> future = new CompletableFuture<>();
        String projectSlug = pipelineRun.getProjectSlug();
        Long accountId = pipelineRun.getAccountId();
        Thread t = Thread.startVirtualThread(() -> {
            com.workflow.project.ProjectContext.set(projectSlug);
            com.workflow.account.TenantContext.set(accountId);
            try {
                executeBlocks(config, pipelineRun, requirement, false);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                com.workflow.project.ProjectContext.clear();
                com.workflow.account.TenantContext.clear();
                runningThreads.remove(runId);
                cleanupWorkspaceIfTerminal(runId);
            }
        });
        runningThreads.put(runId, t);
        return future;
    }

    public CompletableFuture<Void> runFrom(PipelineConfig config, String requirement,
                                            String fromBlockId, Map<String, Map<String, Object>> injectedOutputs,
                                            UUID runId) {
        return runFrom(config, requirement, fromBlockId, injectedOutputs, runId, null);
    }

    public CompletableFuture<Void> runFrom(PipelineConfig config, String requirement,
                                            String fromBlockId, Map<String, Map<String, Object>> injectedOutputs,
                                            UUID runId, Map<String, Object> runInputs) {
        return runFrom(config, requirement, fromBlockId, injectedOutputs, runId, runInputs, null);
    }

    /** Extended overload that preserves original block timestamps for retry runs. */
    public CompletableFuture<Void> runFrom(PipelineConfig config, String requirement,
                                            String fromBlockId, Map<String, Map<String, Object>> injectedOutputs,
                                            UUID runId, Map<String, Object> runInputs,
                                            Map<String, Instant[]> blockTimestamps) {
        // See run() above for why accountId has to be set explicitly here rather
        // than relying on @PrePersist (merge-path skips lifecycle callbacks).
        Long callerAccountId = com.workflow.account.TenantContext.get();
        PipelineRun pipelineRun = PipelineRun.builder()
            .id(runId)
            .pipelineName(config.getName())
            .requirement(requirement)
            .status(RunStatus.RUNNING)
            .startedAt(java.time.Instant.now())
            .completedBlocks(new java.util.LinkedHashSet<>())
            .autoApprove(new java.util.LinkedHashSet<>())
            .outputs(new ArrayList<>())
            .build();
        pipelineRun.setProjectSlug(com.workflow.project.ProjectContext.get());
        if (callerAccountId != null) pipelineRun.setAccountId(callerAccountId);
        if (runInputs != null && runInputs.get("_workspaceDir") instanceof String wd1 && !wd1.isBlank()) {
            pipelineRun.setWorkspaceDir(wd1);
        }
        if (runInputs != null && !runInputs.isEmpty()) {
            try { pipelineRun.setRunInputsJson(objectMapper.writeValueAsString(runInputs)); }
            catch (Exception e) { log.warn("Failed to serialize runInputs: {}", e.getMessage()); }
            if (Boolean.TRUE.equals(runInputs.get("_autoApproveAll"))) {
                pipelineRun.getAutoApprove().add("*");
                if (bashApprovalGate != null) bashApprovalGate.autoApproveRun(runId);
                log.info("Auto-approve-all enabled for run {}", runId);
            }
        }
        techStackPromptEnricher.enrich(config, pipelineRun.getProjectSlug());
        captureConfigSnapshot(pipelineRun, config);

        List<BlockConfig> sorted = topologicalSort(config.getPipeline());
        boolean isRetry = fromBlockId != null;
        Instant preEntryNow = Instant.now();
        for (BlockConfig blockConfig : sorted) {
            if (blockConfig.getId().equals(fromBlockId)) break;
            pipelineRun.getCompletedBlocks().add(blockConfig.getId());
            // Always persist a BlockOutput for every pre-entry block, even when the injection is
            // empty (source=empty). This ensures PathResolver can find the block in run.getOutputs()
            // and ${block.field} interpolations resolve to "" instead of throwing PathNotFoundException.
            Map<String, Object> injected = injectedOutputs != null
                ? injectedOutputs.getOrDefault(blockConfig.getId(), new HashMap<>())
                : new HashMap<>();
            // Mark pre-entry blocks as reused so the UI can distinguish them from
            // condition-skipped blocks (_skipped) and show "Inherited" instead of "Skipped".
            if (isRetry) {
                injected = new java.util.LinkedHashMap<>(injected);
                injected.remove("_skipped");
                injected.put("_reused", true);
            }
            try {
                String outputJson = objectMapper.writeValueAsString(injected);
                Instant[] ts = blockTimestamps != null ? blockTimestamps.get(blockConfig.getId()) : null;
                Instant bStart = (ts != null && ts.length > 0 && ts[0] != null) ? ts[0] : preEntryNow;
                Instant bEnd   = (ts != null && ts.length > 1 && ts[1] != null) ? ts[1] : preEntryNow;
                BlockOutput blockOutput = BlockOutput.builder()
                    .run(pipelineRun).blockId(blockConfig.getId()).outputJson(outputJson)
                    .startedAt(bStart).completedAt(bEnd).build();
                pipelineRun.getOutputs().add(blockOutput);
            } catch (Exception e) {
                log.warn("Failed to serialize injected output for block {}: {}", blockConfig.getId(), e.getMessage());
            }
        }

        runRepository.save(pipelineRun);

        // Notify global subscribers that a new run has started.
        if (wsHandler != null) wsHandler.sendRunStarted(runId);

        CompletableFuture<Void> future = new CompletableFuture<>();
        String projectSlugFrom = pipelineRun.getProjectSlug();
        Long accountIdFrom = pipelineRun.getAccountId();
        Thread t = Thread.startVirtualThread(() -> {
            com.workflow.project.ProjectContext.set(projectSlugFrom);
            com.workflow.account.TenantContext.set(accountIdFrom);
            try {
                executeBlocks(config, pipelineRun, requirement, true);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                com.workflow.project.ProjectContext.clear();
                com.workflow.account.TenantContext.clear();
                runningThreads.remove(runId);
                cleanupWorkspaceIfTerminal(runId);
            }
        });
        runningThreads.put(runId, t);
        return future;
    }

    @Transactional
    public PipelineRun resume(PipelineConfig config, String runIdStr) {
        UUID runId = UUID.fromString(runIdStr);
        PipelineRun run = runRepository.findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runIdStr));

        if (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.FAILED) {
            log.warn("Run {} is already in terminal state: {}", runId, run.getStatus());
            return run;
        }

        // Force-initialize lazy collections while still inside the JPA session so
        // the virtual thread (which has no Hibernate session) can access them freely.
        run.getCompletedBlocks().size();
        run.getAutoApprove().size();
        run.getLoopIterations().size(); // must be loaded so loopback limits survive restart
        run.getOutputs().forEach(o -> o.getBlockId()); // touch each element

        run.setStatus(RunStatus.RUNNING);
        runRepository.save(run);

        String projectSlugResume = run.getProjectSlug();
        Long accountIdResume = run.getAccountId();
        Thread t = Thread.startVirtualThread(() -> {
            com.workflow.project.ProjectContext.set(projectSlugResume);
            com.workflow.account.TenantContext.set(accountIdResume);
            try {
                executeBlocks(config, run, run.getRequirement(), true);
            } catch (Exception e) {
                log.error("Error resuming run {}: {}", runId, e.getMessage(), e);
            } finally {
                com.workflow.project.ProjectContext.clear();
                com.workflow.account.TenantContext.clear();
                runningThreads.remove(runId);
                cleanupWorkspaceIfTerminal(runId);
            }
        });
        runningThreads.put(runId, t);

        return run;
    }

    /**
     * Cancel a running pipeline. Interrupts the execution thread (preferred path) or
     * marks the run FAILED directly when no live thread is found (fallback path).
     *
     * <p>Race condition note (TOCTOU): there is a window between
     * {@code runningThreads.get(runId)} returning null and the subsequent DB read where
     * the execution thread may have just removed itself from the map (in its {@code finally}
     * block) but not yet persisted the COMPLETED status.  The status guard below
     * ({@code RUNNING || PAUSED_FOR_APPROVAL}) prevents overwriting a legitimately
     * COMPLETED run in the common case.  The residual window is tiny (a few ms at most)
     * and is further narrowed by the fact that {@code executeBlocks} always writes
     * COMPLETED to the DB <em>before</em> the {@code finally} block removes the thread.
     * A fully atomic fix would require optimistic locking ({@code @Version}) or a
     * conditional UPDATE — acceptable if cancel contention becomes a real concern.
     *
     * @return true if the run was cancelled (thread interrupted or DB status updated)
     */
    public boolean cancelRun(UUID runId) {
        Thread t = runningThreads.get(runId);
        if (t != null) {
            t.interrupt();
            return true;
        }

        // Fallback: no live thread found — the run may have just completed or may be
        // stuck in a state where the thread was never tracked (e.g. server restart).
        // Only transition to FAILED if the run is still in an active state; never
        // overwrite a terminal status (COMPLETED, FAILED).
        return runRepository.findById(runId).map(run -> {
            RunStatus currentStatus = run.getStatus();
            if (currentStatus == RunStatus.RUNNING || currentStatus == RunStatus.PAUSED_FOR_APPROVAL) {
                run.setStatus(RunStatus.FAILED);
                run.setError("Cancelled by user");
                run.setCompletedAt(Instant.now());
                runRepository.save(run);
                if (wsHandler != null) wsHandler.sendRunComplete(runId, "FAILED");
                return true;
            }
            // Run is already in a terminal state — nothing to do.
            return false;
        }).orElse(false);
    }

    /** Returns IDs of all blocks that appear strictly before {@code fromBlockId} in topological order. */
    public java.util.Set<String> getBlockIdsBefore(PipelineConfig config, String fromBlockId) {
        return blockIdsBefore(config, fromBlockId);
    }

    /**
     * Static variant of {@link #getBlockIdsBefore} — IDs strictly before {@code fromBlockId} in
     * topological order. Exposed statically so collaborators that only need the reachable-set
     * computation (e.g. {@link com.workflow.preflight.RunDiagnostics}) don't have to depend on the
     * heavyweight {@code PipelineRunner} bean.
     */
    public static java.util.Set<String> blockIdsBefore(PipelineConfig config, String fromBlockId) {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        if (fromBlockId == null) return result;
        for (BlockConfig bc : topologicalSortValidated(config.getPipeline())) {
            if (bc.getId().equals(fromBlockId)) break;
            result.add(bc.getId());
        }
        return result;
    }

    /**
     * Validates the pipeline config and returns the topological order of its blocks.
     *
     * <p>Validation runs first via {@link PipelineConfigValidator} — on failure, an
     * {@link InvalidPipelineException} carrying the full {@link ValidationResult} is thrown.
     * The graph traversal below is then a pure utility: structural rules (unknown deps,
     * cycles) are guaranteed not to occur because the validator already caught them.
     */
    List<BlockConfig> topologicalSort(List<BlockConfig> blocks) {
        if (pipelineConfigValidator != null) {
            PipelineConfig wrapper = new PipelineConfig();
            wrapper.setPipeline(blocks);
            ValidationResult result = pipelineConfigValidator.validate(wrapper);
            if (!result.valid()) {
                throw new InvalidPipelineException(result);
            }
        }
        return topologicalSortValidated(blocks);
    }

    /**
     * Pure topological sort — assumes the input has already been validated.
     * Defense-in-depth IllegalStateException if invariants somehow break.
     */
    static List<BlockConfig> topologicalSortValidated(List<BlockConfig> blocks) {
        Map<String, BlockConfig> blockMap = new LinkedHashMap<>();
        for (BlockConfig b : blocks) blockMap.put(b.getId(), b);

        List<BlockConfig> sorted = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> inStack = new HashSet<>();

        for (String blockId : blockMap.keySet()) {
            if (!visited.contains(blockId)) dfsVisit(blockId, blockMap, visited, inStack, sorted);
        }
        return sorted;
    }

    private static void dfsVisit(String blockId, Map<String, BlockConfig> blockMap,
                                 Set<String> visited, Set<String> inStack, List<BlockConfig> sorted) {
        if (inStack.contains(blockId)) throw new IllegalStateException("Cycle detected involving block: " + blockId);
        if (visited.contains(blockId)) return;

        inStack.add(blockId);
        BlockConfig block = blockMap.get(blockId);
        if (block == null) throw new IllegalStateException("Unknown block in dependency graph: " + blockId);
        if (block.getDependsOn() != null) {
            for (String dep : block.getDependsOn()) dfsVisit(dep, blockMap, visited, inStack, sorted);
        }
        inStack.remove(blockId);
        visited.add(blockId);
        sorted.add(block);
    }

    private Map<String, Object> gatherInputs(BlockConfig blockConfig, PipelineRun run, String requirement) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("requirement", requirement);

        // Merge named run inputs (e.g. task_file, build_command) so ${input.key} templates resolve.
        if (run.getRunInputsJson() != null && !run.getRunInputsJson().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> runInputs = objectMapper.readValue(
                    run.getRunInputsJson(), new TypeReference<Map<String, Object>>() {});
                inputs.putAll(runInputs);
            } catch (Exception e) {
                log.warn("Failed to deserialize runInputsJson: {}", e.getMessage());
            }
        }

        if (blockConfig.getDependsOn() != null) {
            for (String depId : blockConfig.getDependsOn()) {
                Optional<BlockOutput> depOutput = run.getOutputs().stream()
                    .filter(o -> o.getBlockId().equals(depId)).reduce((a, b) -> b);
                if (depOutput.isPresent()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> depData = objectMapper.readValue(
                            depOutput.get().getOutputJson(), new TypeReference<Map<String, Object>>() {});
                        inputs.put(depId, depData);
                    } catch (Exception e) {
                        log.warn("Failed to deserialize output for dep block {}: {}", depId, e.getMessage());
                        inputs.put(depId, new HashMap<>());
                    }
                } else {
                    log.warn("No output found for dependency block: {}", depId);
                    inputs.put(depId, new HashMap<>());
                }
            }
        }

        // Inject loopback context if this block was the target of a loopback
        String loopbackKey = "_loopback_" + blockConfig.getId();
        Optional<BlockOutput> loopbackOutput = run.getOutputs().stream()
            .filter(o -> o.getBlockId().equals(loopbackKey)).reduce((a, b) -> b);
        if (loopbackOutput.isPresent()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> loopbackData = objectMapper.readValue(
                    loopbackOutput.get().getOutputJson(), new TypeReference<Map<String, Object>>() {});
                inputs.put("_loopback", loopbackData);
            } catch (Exception e) {
                log.warn("Failed to deserialize loopback context for block {}: {}", blockConfig.getId(), e.getMessage());
            }
        }

        return inputs;
    }

    private Map<String, Object> resolveIntegrationConfigs(PipelineConfig pipelineConfig, PipelineRun run) {
        Map<String, Object> integrationData = new HashMap<>();
        IntegrationsConfig integrations = pipelineConfig.getIntegrations();

        // Inject default tracker provider from project settings
        if (projectRepository != null && run != null && run.getProjectSlug() != null) {
            projectRepository.findBySlug(run.getProjectSlug()).ifPresent(project -> {
                String tracker = project.getEffectiveDefaultTrackerProvider();
                integrationData.put("_default_tracker_provider", tracker);
            });
        }

        Optional<IntegrationConfig> ytConfig = resolveIntegration(
            integrations != null ? integrations.getYoutrack() : null, IntegrationType.YOUTRACK);
        ytConfig.ifPresent(cfg -> {
            Map<String, Object> ytData = new HashMap<>();
            ytData.put("baseUrl", cfg.getBaseUrl());
            ytData.put("token", cfg.getToken());
            ytData.put("project", cfg.getProject());
            integrationData.put("_youtrack_config", ytData);
        });

        Optional<IntegrationConfig> glConfig = resolveIntegration(
            integrations != null ? integrations.getGitlab() : null, IntegrationType.GITLAB);
        glConfig.ifPresent(cfg -> {
            Map<String, Object> glData = new HashMap<>();
            glData.put("url", cfg.getBaseUrl());
            glData.put("token", cfg.getToken());
            glData.put("project_id", cfg.getProject());
            integrationData.put("_gitlab_config", glData);
        });

        Optional<IntegrationConfig> ghConfig = resolveIntegration(
            integrations != null ? integrations.getGithub() : null, IntegrationType.GITHUB);
        ghConfig.ifPresent(cfg -> {
            Map<String, Object> ghData = new HashMap<>();
            ghData.put("token", cfg.getToken());
            ghData.put("owner", cfg.getOwner());
            ghData.put("repo", cfg.getRepo());
            ghData.put("url", cfg.getBaseUrl() != null ? cfg.getBaseUrl() : "https://api.github.com");
            integrationData.put("_github_config", ghData);
            // GitHub issues adapter uses same credentials — expose under tracker key too
            ghData.put("baseUrl", ghData.get("url"));
            integrationData.put("_github_issues_config", new HashMap<>(ghData));
        });

        // GitLab issues uses project_id (numeric); alias it for the tracker adapter
        glConfig.ifPresent(cfg -> {
            Map<String, Object> glIssuesData = new HashMap<>();
            glIssuesData.put("baseUrl", cfg.getBaseUrl());
            glIssuesData.put("token", cfg.getToken());
            glIssuesData.put("projectId", cfg.getProject());
            integrationData.put("_gitlab_issues_config", glIssuesData);
        });

        return integrationData;
    }

    private Optional<IntegrationConfig> resolveIntegration(String configName, IntegrationType type) {
        // Delegates to the shared resolver so the pre-run diagnostic
        // (com.workflow.preflight) and the runtime read the identical cascade.
        return integrationResolver.resolve(configName, type);
    }

    /**
     * Evaluates a simple condition expression: $.block_id.field [| length] OPERATOR value
     * Multiple clauses can be AND-joined with {@code &&} (no precedence, no {@code ||}, no parens).
     * The reserved {@code input} namespace resolves against {@code PipelineRun.runInputsJson}
     * (set at run start), so {@code $.input.provider == 'CLAUDE_CODE_CLI'} works for routing
     * decisions taken before any block has run.
     * Supported operators: ==, !=, >, <, >=, <=
     */
    /**
     * Resolves the LLM provider for this run. Resolution order:
     * 1. {@code provider} field in run inputs (per-run override)
     * 2. {@link com.workflow.project.Project#getEffectiveDefaultProvider()} for the run's project
     * 3. {@code null} → {@link com.workflow.llm.LlmClient} falls back to its model-name-based routing
     */
    @SuppressWarnings("unchecked")
    private com.workflow.llm.LlmProvider resolveRunProvider(PipelineRun run) {
        String inputsJson = run.getRunInputsJson();
        if (inputsJson != null && !inputsJson.isBlank()) {
            try {
                Map<String, Object> inputs = objectMapper.readValue(
                    inputsJson, new TypeReference<Map<String, Object>>() {});
                Object raw = inputs.get("provider");
                if (raw != null) {
                    return com.workflow.llm.LlmProvider.valueOf(raw.toString().trim().toUpperCase());
                }
            } catch (Exception ignore) {
                // fall through to project-level default
            }
        }
        if (projectRepository != null && run.getProjectSlug() != null) {
            try {
                return projectRepository.findBySlug(run.getProjectSlug())
                    .map(com.workflow.project.Project::getEffectiveDefaultProvider)
                    .orElse(null);
            } catch (Exception ignore) {
                // ignore, fall back to null
            }
        }
        return null;
    }

    private boolean evaluateCondition(String expr, PipelineRun run) {
        if (expr == null || expr.isBlank()) return true;
        // Precedence: && binds tighter than ||. Split on top-level || first,
        // then each disjunct is an AND-chain of clauses.
        if (expr.contains("||")) {
            for (String disjunct : expr.split("\\|\\|")) {
                if (evaluateCondition(disjunct.trim(), run)) return true;
            }
            return false;
        }
        if (expr.contains("&&")) {
            for (String clause : expr.split("&&")) {
                if (!evaluateClause(clause.trim(), run)) return false;
            }
            return true;
        }
        return evaluateClause(expr.trim(), run);
    }

    @SuppressWarnings("unchecked")
    private boolean evaluateClause(String expr, PipelineRun run) {
        if (expr == null || expr.isBlank()) return true;
        try {
            // Pattern: $.block_id.field [| length] OP value
            Pattern pattern = Pattern.compile(
                "^\\$\\.([\\w]+)\\.([\\w]+)(\\s*\\|\\s*length)?\\s*(==|!=|>=|<=|>|<)\\s*(.+)$");
            Matcher m = pattern.matcher(expr.trim());
            if (!m.matches()) {
                // Unparseable condition: throw so the block is failed with a clear message
                // rather than silently treating the guard as "always pass". A malformed
                // condition (e.g. missing spaces around the operator) would otherwise disable
                // the check entirely without any operator-visible signal.
                throw new IllegalArgumentException(
                    "Condition expression could not be parsed: '" + expr + "'. "
                    + "Expected format: $.blockId.field [| length] OP value  "
                    + "(OP is ==, !=, >, <, >=, <=; string values may be quoted)");
            }

            String blockId = m.group(1);
            String field = m.group(2);
            boolean useLength = m.group(3) != null;
            String op = m.group(4);
            String rawValue = m.group(5).trim().replaceAll("^['\"]|['\"]$", "");

            Object fieldVal;
            if ("input".equals(blockId)) {
                String inputsJson = run.getRunInputsJson();
                if (inputsJson == null || inputsJson.isBlank()) return false;
                Map<String, Object> inputs = objectMapper.readValue(
                    inputsJson, new TypeReference<Map<String, Object>>() {});
                fieldVal = inputs.get(field);
            } else {
                Optional<BlockOutput> outputOpt = run.getOutputs().stream()
                    .filter(o -> o.getBlockId().equals(blockId)).reduce((a, b) -> b);
                if (outputOpt.isEmpty()) return true;

                Map<String, Object> blockOutput = objectMapper.readValue(
                    outputOpt.get().getOutputJson(), new TypeReference<Map<String, Object>>() {});
                fieldVal = blockOutput.get(field);
            }

            if (useLength) {
                int len = fieldVal instanceof List<?> l ? l.size()
                    : fieldVal instanceof String s ? s.length() : 0;
                fieldVal = len;
            }

            if (fieldVal == null) return false;

            // Try numeric comparison first
            try {
                double numFieldVal = fieldVal instanceof Number n ? n.doubleValue()
                    : Double.parseDouble(fieldVal.toString());
                double numRawVal = Double.parseDouble(rawValue);
                return switch (op) {
                    case "==" -> numFieldVal == numRawVal;
                    case "!=" -> numFieldVal != numRawVal;
                    case ">"  -> numFieldVal > numRawVal;
                    case "<"  -> numFieldVal < numRawVal;
                    case ">=" -> numFieldVal >= numRawVal;
                    case "<=" -> numFieldVal <= numRawVal;
                    default -> true;
                };
            } catch (NumberFormatException ignored) {
                // String comparison
                String strFieldVal = fieldVal.toString();
                return switch (op) {
                    case "==" -> strFieldVal.equals(rawValue);
                    case "!=" -> !strFieldVal.equals(rawValue);
                    default -> true;
                };
            }
        } catch (IllegalArgumentException e) {
            // Syntax error — re-throw so the block fails visibly instead of running unconditionally.
            throw e;
        } catch (Exception e) {
            log.warn("Error evaluating condition '{}': {}", expr, e.getMessage());
            return true;
        }
    }

    /**
     * Evaluates all required gates. Returns the list of gates that failed (empty if all passed).
     * Unlike {@link #evaluateCondition}, gates are strict: a missing referenced block output
     * counts as a failure so preconditions cannot be silently bypassed.
     */
    private List<GateConfig> evaluateGates(List<GateConfig> gates, PipelineRun run) {
        List<GateConfig> failed = new ArrayList<>();
        if (gates == null || gates.isEmpty()) return failed;
        Pattern refPattern = Pattern.compile("\\$\\.([\\w]+)\\.");
        for (GateConfig gate : gates) {
            String expr = gate.getExpr();
            if (expr == null || expr.isBlank()) {
                log.warn("Gate '{}' has no expression — treating as failed", gate.displayName());
                failed.add(gate);
                continue;
            }
            Matcher m = refPattern.matcher(expr);
            boolean refsMissing = false;
            while (m.find()) {
                String refBlock = m.group(1);
                boolean exists = run.getOutputs().stream().anyMatch(o -> o.getBlockId().equals(refBlock));
                if (!exists) {
                    log.warn("Gate '{}' references block '{}' with no output — treating as failed",
                        gate.displayName(), refBlock);
                    refsMissing = true;
                    break;
                }
            }
            if (refsMissing) { failed.add(gate); continue; }
            if (!evaluateCondition(expr, run)) failed.add(gate);
        }
        return failed;
    }

    /**
     * Resolves inject_context map {"key": "$.block_id.field"} into actual values from run outputs.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveInjectContext(Map<String, String> injectMap, PipelineRun run) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (injectMap == null) return resolved;

        for (Map.Entry<String, String> entry : injectMap.entrySet()) {
            String key = entry.getKey();
            String ref = entry.getValue().trim();
            if (!ref.startsWith("$.")) {
                resolved.put(key, ref);
                continue;
            }
            try {
                String[] parts = ref.substring(2).split("\\.", 2);
                if (parts.length < 2) {
                    log.warn("inject_context ref '{}' (key '{}') is malformed (expected $.block.field) — skipping",
                        ref, key);
                    continue;
                }
                String blockId = parts[0];
                String field = parts[1];
                Optional<BlockOutput> outputOpt = run.getOutputs().stream()
                    .filter(o -> o.getBlockId().equals(blockId)).reduce((a, b) -> b);
                if (outputOpt.isEmpty()) {
                    // Lenient by design: a missing loopback-feedback field must not crash the run.
                    // Surface it as a WARN so operators can spot a typo'd ref (config-time
                    // validation catches most; this is the runtime backstop).
                    log.warn("inject_context ref '{}' (key '{}'): no output for block '{}' yet — skipping",
                        ref, key, blockId);
                    continue;
                }
                Map<String, Object> blockOutput = objectMapper.readValue(
                    outputOpt.get().getOutputJson(), new TypeReference<Map<String, Object>>() {});
                Object val = blockOutput.get(field);
                if (val != null) {
                    resolved.put(key, val);
                } else {
                    log.warn("inject_context ref '{}' (key '{}'): field '{}' absent in block '{}' output — skipping",
                        ref, key, field, blockId);
                }
            } catch (Exception e) {
                log.warn("Failed to resolve inject_context ref '{}': {}", ref, e.getMessage());
            }
        }
        return resolved;
    }

    /**
     * Checks whether a block output represents a failure that should trigger on_failure.
     * Two detection paths:
     * <ul>
     *   <li>CI-style: {@code output.status} or {@code output.overall_status} matches one
     *       of {@code on_failure.failed_statuses} (gitlab_ci / github_actions outputs).</li>
     *   <li>Shell-style: {@code output.success == false} — lets shell_exec blocks self-trigger
     *       loopback on non-zero exit, replacing the verify_build / verify_tests / etc.
     *       wrapper pattern with a single block that owns its own retry.</li>
     * </ul>
     */
    private boolean isCiFailure(Map<String, Object> output, OnFailureConfig cfg) {
        if (output == null || cfg == null) return false;
        // Shell-style: explicit success=false is unambiguous, no failed_statuses needed.
        Object success = output.get("success");
        if (Boolean.FALSE.equals(success)) return true;
        // CI-style: check named status against allowed failure tokens.
        String status = null;
        for (String key : new String[]{"status", "overall_status"}) {
            Object val = output.get(key);
            if (val instanceof String s) { status = s; break; }
        }
        if (status == null) return false;
        List<String> failedStatuses = cfg.getFailedStatuses();
        return failedStatuses != null && failedStatuses.contains(status.toLowerCase());
    }

    /**
     * Handles a loopback: increments counter, resets completed blocks, stores loopback context.
     * Returns the new loop index (target block index), or -1 if max iterations exceeded.
     */
    private int handleLoopback(String loopKey, String targetId, String fromBlockId,
                                int maxIterations, List<String> issues, Map<String, Object> extraContext,
                                List<BlockConfig> sortedBlocks, int currentI, PipelineRun run) {
        int iterations = run.getLoopIterations().getOrDefault(loopKey, 0);
        if (iterations >= maxIterations) {
            log.warn("Loopback '{}' exceeded max iterations ({}), stopping", loopKey, maxIterations);
            return -1;
        }

        // No-progress detector: when this iteration's issues repeat the previous one's
        // (Jaccard ≥ threshold), force-stop the local-tier loop so the caller can fall
        // through to the escalation ladder (cloud → human) without burning the remaining
        // budget on identical retries.
        if (iterations >= 1 && noProgressDetector != null) {
            double threshold = escalationProperties != null
                    ? escalationProperties.getNoProgressThreshold() : 0.8;
            List<List<String>> prior = noProgressDetector.extractPriorIssues(
                    run.getLoopHistoryJson(), fromBlockId, targetId, objectMapper);
            if (noProgressDetector.isStuck(prior, issues, threshold)) {
                log.warn("Loopback '{}' detected no-progress (jaccard ≥ {}, iteration {}), short-circuiting to escalation",
                        loopKey, threshold, iterations + 1);
                appendNoProgressHistoryEntry(run, fromBlockId, targetId, iterations + 1, issues, threshold);
                if (metrics != null) metrics.recordNoProgressDetection(fromBlockId);
                return -1;
            }
        }

        // Global remediation envelope: bounds local loopback + escalation cloud-retry combined,
        // so a single failing block can't multiply into ≈8 chain re-runs. The escalation
        // cloud-retry re-enters this method, so the check here covers both paths.
        int maxTotal = escalationProperties != null ? escalationProperties.getMaxTotalIterations() : 0;
        if (maxTotal > 0 && run.getTotalRemediationIterations() >= maxTotal) {
            log.warn("Loopback '{}' hit global remediation cap ({} iterations), stopping", loopKey, maxTotal);
            return -1;
        }

        run.getLoopIterations().put(loopKey, iterations + 1);
        run.incrementTotalRemediationIterations();
        if (metrics != null) metrics.recordLoopbackIteration(fromBlockId, targetId);

        // Find target index
        int targetIndex = -1;
        for (int j = 0; j < sortedBlocks.size(); j++) {
            if (sortedBlocks.get(j).getId().equals(targetId)) { targetIndex = j; break; }
        }
        if (targetIndex < 0) {
            log.error("Loopback target '{}' not found in pipeline", targetId);
            return -1;
        }

        // Remove completed state for blocks from target to current (inclusive)
        for (int j = targetIndex; j <= currentI; j++) {
            run.getCompletedBlocks().remove(sortedBlocks.get(j).getId());
        }

        // Store loopback context for the target block
        Map<String, Object> loopbackContext = new LinkedHashMap<>();
        loopbackContext.put("issues", new ArrayList<>(issues));
        loopbackContext.put("iteration", iterations + 1);
        loopbackContext.putAll(extraContext);
        saveBlockOutput(run, "_loopback_" + targetId, loopbackContext);

        // Append to loop history JSON
        try {
            String histJson = run.getLoopHistoryJson() != null ? run.getLoopHistoryJson() : "[]";
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = objectMapper.readValue(histJson,
                new TypeReference<List<Map<String, Object>>>() {});
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.put("from_block", fromBlockId);
            entry.put("to_block", targetId);
            entry.put("iteration", iterations + 1);
            entry.put("issues", issues);
            history.add(entry);
            run.setLoopHistoryJson(objectMapper.writeValueAsString(capHistory(history)));
        } catch (Exception e) {
            log.warn("Failed to update loop history: {}", e.getMessage());
        }

        log.info("Loopback triggered: {} → {} (iteration {}/{})", fromBlockId, targetId, iterations + 1, maxIterations);
        return targetIndex;
    }

    /**
     * Records a stuck-loop marker in {@code loop_history_json} so the UI can surface
     * why the chain short-circuited and to keep an audit trail for cost-postmortems.
     */
    private void appendNoProgressHistoryEntry(PipelineRun run, String fromBlockId, String targetId,
                                                int iteration, List<String> issues, double threshold) {
        try {
            String histJson = run.getLoopHistoryJson() != null ? run.getLoopHistoryJson() : "[]";
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = objectMapper.readValue(histJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.put("from_block", fromBlockId);
            entry.put("to_block", targetId);
            entry.put("iteration", iteration);
            entry.put("issues", issues);
            entry.put("source", "no_progress");
            entry.put("detected_no_progress", true);
            entry.put("threshold", threshold);
            history.add(entry);
            run.setLoopHistoryJson(objectMapper.writeValueAsString(capHistory(history)));
        } catch (Exception e) {
            log.warn("Failed to append no-progress history entry: {}", e.getMessage());
        }
    }

    /**
     * Caps {@code loop_history_json} to the most recent {@link #LOOP_HISTORY_MAX} entries so it
     * can't grow unbounded on pathological runs. The no-progress detector only inspects recent
     * iterations (per-loop counts are tiny vs. the cap), so trimming the head is safe.
     */
    private static final int LOOP_HISTORY_MAX = 50;

    private List<Map<String, Object>> capHistory(List<Map<String, Object>> history) {
        if (history.size() <= LOOP_HISTORY_MAX) return history;
        return new ArrayList<>(history.subList(history.size() - LOOP_HISTORY_MAX, history.size()));
    }

    /**
     * Try to escalate after a loopback exhausted max_iterations. Consults
     * {@link EscalationService} to walk the ladder (cloud → human). Returns:
     * <ul>
     *   <li>{@code >= 0}: new index to continue execution from (cloud retry succeeded
     *       via a fresh handleLoopback, or human approve advanced past the failing block)</li>
     *   <li>{@code -1}: ladder exhausted or human rejected — caller should fail
     *       the run with its original error message</li>
     * </ul>
     */
    private int tryEscalateAfterLoopback(
            PipelineRun run, String failingBlockId, String targetId,
            com.workflow.config.EscalationConfig blockEscalation, int maxIter,
            Map<String, Object> currentOutput, Map<String, Object> blockInputs,
            List<String> issues, Map<String, Object> extraCtx,
            List<BlockConfig> sortedBlocks, int currentIdx, Instant blockStart) {

        List<com.workflow.config.EscalationStep> ladder = escalationService.resolveLadder(run, blockEscalation);
        if (ladder.isEmpty()) return -1;

        Map<String, Object> failureContext = new LinkedHashMap<>();
        failureContext.put("issues", issues);
        if (currentOutput != null && currentOutput.get("score") != null) {
            failureContext.put("verify_score", currentOutput.get("score"));
        }

        EscalationDecision decision = escalationService.attemptEscalation(
                run, failingBlockId, targetId, ladder, failureContext);

        if (decision instanceof EscalationDecision.RetryWithCloud) {
            if (metrics != null) metrics.recordEscalationStep("cloud");
            // Allow exactly one more retry under the cloud tier — set counter to maxIter-1
            // so handleLoopback permits one iteration and then stops.
            // Resetting to 0 here was the bug: it allowed a full maxIter extra cycle.
            String loopKey = "loopback:" + failingBlockId + ":" + targetId;
            run.getLoopIterations().put(loopKey, Math.max(0, maxIter - 1));
            runRepository.save(run);
            int newI = handleLoopback(loopKey, targetId, failingBlockId, maxIter,
                    issues, extraCtx, sortedBlocks, currentIdx, run);
            if (newI >= 0) {
                log.info("Escalation cloud-tier: run={} failingBlock={} retrying from idx {}",
                        run.getId(), failingBlockId, newI);
                return newI;
            }
            return -1;
        }

        if (decision instanceof EscalationDecision.PauseForHuman pause) {
            if (metrics != null) metrics.recordEscalationStep("human");
            // Save current (failed) output for the gate UI before pausing.
            saveBlockOutput(run, failingBlockId, currentOutput, blockInputs, blockStart, Instant.now());
            run.setStatus(RunStatus.PAUSED_FOR_APPROVAL);
            run.setPausedAt(Instant.now());
            long timeoutS = pause.step().timeoutSeconds();
            run.setApprovalTimeoutSeconds((int) Math.min((long) Integer.MAX_VALUE, timeoutS));
            run.setApprovalTimeoutAction("fail");
            runRepository.save(run);

            List<String> remainingIds = sortedBlocks.subList(currentIdx + 1, sortedBlocks.size())
                    .stream().map(BlockConfig::getId).toList();

            String desc = "Escalation: block '" + failingBlockId + "' exhausted retries (incl. cloud-tier). "
                    + "Approve to accept the current output (override failure) or reject to fail the run.";

            Map<String, Object> displayOutput = new LinkedHashMap<>();
            if (currentOutput != null) displayOutput.putAll(currentOutput);
            displayOutput.put("_escalation_bundle", pause.bundle());

            // Honour run-level _autoApproveAll for the escalation human gate too:
            // for E2E and unattended runs the operator has explicitly opted into
            // auto-approving every gate, including the final escalation safety net.
            if (run.getAutoApprove() != null && run.getAutoApprove().contains("*")) {
                log.info("Escalation human gate auto-approved (run-level _autoApproveAll) for run={} block={}",
                        run.getId(), failingBlockId);
                Map<String, Object> overrideOut = new LinkedHashMap<>(
                        currentOutput != null ? currentOutput : Map.of());
                overrideOut.put("passed", true);
                overrideOut.put("escalation_override", true);
                overrideOut.put("escalation_step", "human");
                overrideOut.put("override_reason", "auto-approved (_autoApproveAll)");
                saveBlockOutput(run, failingBlockId, overrideOut, blockInputs, blockStart, Instant.now());
                if (!run.getCompletedBlocks().contains(failingBlockId)) {
                    run.getCompletedBlocks().add(failingBlockId);
                }
                clearApprovalTimeout(run);
                run.setStatus(RunStatus.RUNNING);
                runRepository.save(run);
                if (wsHandler != null) wsHandler.sendBlockComplete(run.getId(), failingBlockId, overrideOut);
                return currentIdx + 1;
            }

            if (wsHandler != null) wsHandler.sendApprovalRequest(run.getId(), failingBlockId, desc, displayOutput);
            broadcastApprovalNotification(run, failingBlockId, desc);

            try {
                ApprovalResult ar = approvalGate.request(failingBlockId, "escalation_human", desc,
                        blockInputs != null ? blockInputs : Map.of(), displayOutput, remainingIds);
                clearApprovalTimeout(run);
                if (ar != null && "APPROVED".equals(ar.getStatus())) {
                    Map<String, Object> overrideOut = new LinkedHashMap<>(
                            currentOutput != null ? currentOutput : Map.of());
                    overrideOut.put("passed", true);
                    overrideOut.put("escalation_override", true);
                    overrideOut.put("escalation_step", "human");
                    if (ar.getOutput() != null && ar.getOutput().get("reason") != null) {
                        overrideOut.put("override_reason", ar.getOutput().get("reason"));
                    }
                    saveBlockOutput(run, failingBlockId, overrideOut, blockInputs, blockStart, Instant.now());
                    if (!run.getCompletedBlocks().contains(failingBlockId)) {
                        run.getCompletedBlocks().add(failingBlockId);
                    }
                    run.setStatus(RunStatus.RUNNING);
                    runRepository.save(run);
                    if (wsHandler != null) wsHandler.sendBlockComplete(run.getId(), failingBlockId, overrideOut);
                    log.info("Escalation human gate: run={} failingBlock={} approved → continuing past idx {}",
                            run.getId(), failingBlockId, currentIdx);
                    return currentIdx + 1;
                }
            } catch (Exception e) {
                log.warn("Human escalation approval failed for '{}': {}", failingBlockId, e.getMessage());
            }
            clearApprovalTimeout(run);
            return -1;
        }

        // Exhausted — fall through to original failure.
        return -1;
    }

    /**
     * Opt-in pre-execution of {@code parallel: true} DAG levels via virtual threads.
     * Returns a map {@code blockId → output} consumed by the main loop in place of
     * {@code runWithRetry}. Per-block ceremony (cache writes, approval, verify-loopback,
     * audit, WS events) stays on the canonical sequential path — this method only
     * captures the {@code block.run()} result concurrently.
     *
     * <p>Sync-safety:
     * <ul>
     *   <li>Each virtual thread sets its own {@link com.workflow.project.ProjectContext}
     *       and {@link com.workflow.llm.LlmCallContext} — no cross-thread leakage</li>
     *   <li>{@link PipelineRun} mutable state ({@code completedBlocks}, {@code outputs})
     *       is read-only in this pass; writes happen later in the main loop</li>
     *   <li>{@code canParallelizeLevel} rejects levels with approval / loopback peers,
     *       so we never block in parallel on operator input</li>
     * </ul>
     */
    private Map<String, Map<String, Object>> preExecuteParallelLevels(
            PipelineConfig config, PipelineRun run, List<BlockConfig> sortedBlocks,
            String currentRequirement, Map<String, Object> integrationConfigs) {

        Map<String, Map<String, Object>> results = new ConcurrentHashMap<>();
        if (noProgressDetector == null || dagLevelExecutor == null) return results;
        if (!dagLevelExecutor.isEnabled()) return results;
        // Cheap pre-check: skip the whole pass when no block opts in.
        boolean anyParallel = sortedBlocks.stream().anyMatch(BlockConfig::isParallel);
        if (!anyParallel) return results;

        List<List<BlockConfig>> levels = dagLevelExecutor.computeLevels(sortedBlocks, run.getCompletedBlocks());
        String projectSlug = run.getProjectSlug();
        Long accountId = run.getAccountId();

        for (List<BlockConfig> level : levels) {
            if (!dagLevelExecutor.canParallelizeLevel(level)) continue;

            // Filter out anything that the sequential loop will skip anyway. Doing this
            // before forking keeps virtual-thread fan-out tight.
            List<BlockConfig> firable = new ArrayList<>(level.size());
            for (BlockConfig bc : level) {
                String bid = bc.getId();
                if (run.getCompletedBlocks().contains(bid)) continue;
                if (!bc.isEnabled()) continue;
                if (bc.getCondition() != null && !bc.getCondition().isBlank()
                        && !evaluateCondition(bc.getCondition(), run)) {
                    continue;
                }
                firable.add(bc);
            }
            if (firable.size() < 2) continue;

            log.info("Parallel pre-execution: level of {} blocks ({})", firable.size(),
                firable.stream().map(BlockConfig::getId).toList());

            List<CompletableFuture<Void>> futures = new ArrayList<>(firable.size());
            for (BlockConfig bc : firable) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                Thread.startVirtualThread(() -> {
                    com.workflow.project.ProjectContext.set(projectSlug);
                    com.workflow.account.TenantContext.set(accountId);
                    try {
                        BlockConfig effective = bc.withMergedConfig(integrationConfigs);
                        effective.setAgent(agentProfileResolver.resolveAgent(effective, config.getDefaults()));
                        effective.setSkills(agentProfileResolver.resolveSkills(effective));
                        effective = escalationService.applyRuntimeOverride(effective, run);

                        Block block = blockRegistry.get(bc.getBlock());
                        if (block == null) {
                            log.warn("Parallel pre-exec: unknown block type '{}' for {} — main loop will fail",
                                bc.getBlock(), bc.getId());
                            future.complete(null);
                            return;
                        }

                        Map<String, Object> inputs = gatherInputs(bc, run, currentRequirement);
                        if (run.isDryRun()) inputs.put("_dry_run", true);

                        com.workflow.llm.LlmCallContext.set(run.getId(), bc.getId(),
                            escalationService.effectiveProvider(run, bc.getId(), resolveRunProvider(run)));
                        try {
                            Map<String, Object> output = runWithRetry(block, inputs, effective, run);
                            results.put(bc.getId(), output);
                        } finally {
                            com.workflow.llm.LlmCallContext.clear();
                        }
                    } catch (Exception e) {
                        // Don't propagate — the main loop will see no pre-computed output for
                        // this block and re-run it sequentially, which gives us the standard
                        // error handling path (markFailed + audit) instead of an opaque
                        // CompletableFuture failure.
                        log.warn("Parallel pre-exec of block {} failed; main loop will retry sequentially: {}",
                            bc.getId(), e.getMessage());
                    } finally {
                        com.workflow.project.ProjectContext.clear();
                        com.workflow.account.TenantContext.clear();
                        future.complete(null);
                    }
                });
                futures.add(future);
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        log.info("Parallel pre-execution complete: {} block(s) pre-computed", results.size());
        return results;
    }

    private void executeBlocks(PipelineConfig config, PipelineRun run,
                                String currentRequirement, boolean skipCompleted) {
        List<BlockConfig> sortedBlocks = topologicalSort(config.getPipeline());
        Map<String, Object> integrationConfigs = resolveIntegrationConfigs(config, run);

        // Pre-execute opt-in parallel levels. Result map is read+removed by the main loop
        // when a block has been pre-computed concurrently, so the per-block ceremony
        // (cache, approval, verify, audit) still happens serially in the canonical loop.
        Map<String, Map<String, Object>> preComputedOutputs =
            preExecuteParallelLevels(config, run, sortedBlocks, currentRequirement, integrationConfigs);

        int i = 0;
        while (i < sortedBlocks.size()) {
            // Check for interruption (cancellation)
            if (Thread.currentThread().isInterrupted()) {
                handleCancellation(run);
                return;
            }

            BlockConfig blockConfig = sortedBlocks.get(i);
            String blockId = blockConfig.getId();

            if (skipCompleted && run.getCompletedBlocks().contains(blockId)) {
                log.debug("Skipping already-completed block: {}", blockId);
                i++;
                continue;
            }

            // Disabled block — skip entirely (operator toggled it off)
            if (!blockConfig.isEnabled()) {
                log.info("Skipping disabled block: {}", blockId);
                Map<String, Object> skipped = new HashMap<>();
                skipped.put("_skipped", true);
                skipped.put("reason", "disabled");
                Instant skipInstant = Instant.now();
                saveBlockOutput(run, blockId, skipped, null, skipInstant, skipInstant);
                if (!run.getCompletedBlocks().contains(blockId)) run.getCompletedBlocks().add(blockId);
                runRepository.save(run);
                if (wsHandler != null) wsHandler.sendBlockSkipped(run.getId(), blockId, "disabled");
                i++;
                continue;
            }

            // Condition check — skip block if condition evaluates to false
            if (blockConfig.getCondition() != null && !blockConfig.getCondition().isBlank()) {
                if (!evaluateCondition(blockConfig.getCondition(), run)) {
                    log.info("Skipping block '{}' — condition not met: {}", blockId, blockConfig.getCondition());
                    Map<String, Object> skipped = new HashMap<>();
                    skipped.put("_skipped", true);
                    skipped.put("reason", "condition: " + blockConfig.getCondition());
                    Instant skipInstant = Instant.now();
                    saveBlockOutput(run, blockId, skipped, null, skipInstant, skipInstant);
                    if (!run.getCompletedBlocks().contains(blockId)) run.getCompletedBlocks().add(blockId);
                    runRepository.save(run);
                    i++;
                    continue;
                }
            }

            // Required gates — all must evaluate to true, otherwise fail the run
            List<GateConfig> failedGates = evaluateGates(blockConfig.getRequiredGates(), run);
            if (!failedGates.isEmpty()) {
                String names = failedGates.stream().map(GateConfig::displayName).collect(Collectors.joining(", "));
                String error = "Block '" + blockId + "' blocked: required gate(s) failed: " + names;
                log.error(error);
                markFailed(run, error);
                throw new RuntimeException(error);
            }

            run.setCurrentBlock(blockId);
            run.setCurrentOperation("Запущен блок: " + blockId);
            run.setLastActivityAt(java.time.Instant.now());
            run.setStatus(RunStatus.RUNNING);
            runRepository.save(run);

            if (wsHandler != null) wsHandler.sendBlockStarted(run.getId(), blockId);
            if (metrics != null) metrics.recordBlockStarted(blockConfig.getBlock());
            Instant blockStart = Instant.now();
            long blockStartedAt = System.currentTimeMillis();

            Map<String, Object> inputs = gatherInputs(blockConfig, run, currentRequirement);
            if (run.isDryRun()) inputs.put("_dry_run", true);
            BlockConfig effectiveBlockConfig = blockConfig.withMergedConfig(integrationConfigs);

            // Resolve agent profile into effective agent config and skills
            effectiveBlockConfig.setAgent(agentProfileResolver.resolveAgent(effectiveBlockConfig, config.getDefaults()));
            effectiveBlockConfig.setSkills(agentProfileResolver.resolveSkills(effectiveBlockConfig));

            // Apply any pending runtime override from a prior cloud-tier escalation.
            // The override (model + provider) was written into run.runtimeOverridesJson by
            // EscalationService.attemptEscalation when the verify-target hit max_iterations.
            effectiveBlockConfig = escalationService.applyRuntimeOverride(effectiveBlockConfig, run);

            Block block = blockRegistry.get(blockConfig.getBlock());
            if (block == null) {
                String error = "Unknown block type: " + blockConfig.getBlock();
                log.error(error);
                markFailed(run, error);
                throw new RuntimeException(error);
            }

            boolean prodDeploy = isProdDeployBlock(blockConfig);
            Map<String, Object> output = null;
            // Block-output cache: reuse prior runs' outputs for deterministic blocks (analysis,
            // task_md_input, orchestrator mode=plan) when the fingerprint matches. Loopback
            // iterations always re-execute — the whole point of loopback is fresh evaluation.
            boolean inLoopback = run.getLoopIterations().getOrDefault(blockId, 0) > 0;
            boolean blockCacheable = blockCacheService != null && block.isCacheable(effectiveBlockConfig);
            String cacheKey = null;
            String cacheScope = null;
            Long cacheHitSourceId = null;
            boolean cacheHit = false;
            boolean operatorEdited = false;
            if (blockCacheable && !inLoopback) {
                cacheKey = blockCacheService.computeKey(effectiveBlockConfig, inputs, run);
                cacheScope = blockCacheService.scopeOf(run, effectiveBlockConfig);
                java.util.Optional<BlockOutput> hit = blockCacheService.lookup(cacheScope, cacheKey);
                if (hit.isPresent()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cachedOut = objectMapper.readValue(
                            hit.get().getOutputJson(), Map.class);
                        cachedOut.put("_cached", true);
                        cachedOut.put("_cacheSourceRunId", hit.get().getRun().getId().toString());
                        output = cachedOut;
                        cacheHit = true;
                        cacheHitSourceId = hit.get().getId();
                        log.info("Block {} cache HIT (source run {}, key {})",
                            blockId, hit.get().getRun().getId(), cacheKey != null ? cacheKey.substring(0, 12) : "?");
                    } catch (Exception e) {
                        log.warn("Block {} cache hit but failed to deserialize output: {}",
                            blockId, e.getMessage());
                    }
                }
            }
            // Fast path: parallel pre-execution already produced this block's output.
            Map<String, Object> preComputed = preComputedOutputs.remove(blockId);
            if (preComputed != null && !cacheHit) {
                log.info("Block {} ({}) using pre-computed parallel output", blockId, blockConfig.getBlock());
                output = preComputed;
                try {
                    OutputValidator.validate(output, effectiveBlockConfig.getValidateOutput(), blockId);
                } catch (OutputValidationException ve) {
                    log.error("Block {} pre-computed output failed validation: {}", blockId, ve.getMessage());
                    markFailed(run, "Block " + blockId + " failed: " + ve.getMessage(), ve);
                    throw new RuntimeException("Block validation failed: " + blockId, ve);
                }
            } else if (!cacheHit) try {
                log.info("Running block: {} ({})", blockId, blockConfig.getBlock());
                if (prodDeploy) prodDeployMutex.acquire(run.getId());
                com.workflow.llm.LlmCallContext.set(run.getId(), blockId,
                    escalationService.effectiveProvider(run, blockId, resolveRunProvider(run)));
                try {
                    output = runWithRetry(block, inputs, effectiveBlockConfig, run);
                    OutputValidator.validate(output, effectiveBlockConfig.getValidateOutput(), blockId);
                } finally {
                    com.workflow.llm.LlmCallContext.clear();
                    if (prodDeploy) prodDeployMutex.release(run.getId());
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                handleCancellation(run);
                return;
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted() || e.getCause() instanceof InterruptedException) {
                    handleCancellation(run);
                    return;
                }
                log.error("Block {} failed: {}", blockId, e.getMessage(), e);
                markFailed(run, "Block " + blockId + " failed: " + e.getMessage(), e);
                throw new RuntimeException("Block execution failed: " + blockId, e);
            }

            ApprovalMode approvalMode = blockConfig.getEffectiveApprovalMode();
            boolean autoApproved = run.getAutoApprove().contains(blockId)
                || run.getAutoApprove().contains("*");
            boolean needsApproval = approvalMode == ApprovalMode.MANUAL && !autoApproved;
            boolean notifyOnly = approvalMode == ApprovalMode.AUTO_NOTIFY && !autoApproved;

            if (needsApproval) {
                List<String> remainingBlockIds = sortedBlocks.subList(i + 1, sortedBlocks.size())
                    .stream().map(BlockConfig::getId).collect(Collectors.toList());

                // Persist block output before pausing so the API can serve it to late-joining clients
                saveBlockOutput(run, blockId, output, inputs, blockStart, Instant.now());

                run.setStatus(RunStatus.PAUSED_FOR_APPROVAL);
                run.setPausedAt(Instant.now());
                Integer timeoutSeconds = blockConfig.getTimeoutSeconds();
                run.setApprovalTimeoutSeconds(timeoutSeconds);
                run.setApprovalTimeoutAction(blockConfig.getOnTimeout() != null && blockConfig.getOnTimeout().getAction() != null
                    ? blockConfig.getOnTimeout().getAction().getValue()
                    : null);
                runRepository.save(run);

                if (wsHandler != null) wsHandler.sendApprovalRequest(run.getId(), blockId, block.getDescription(), output);
                broadcastApprovalNotification(run, blockId, block.getDescription());

                try {
                    ApprovalResult approvalResult = approvalGate.request(
                        blockId, blockConfig.getBlock(), block.getDescription(), inputs, output, remainingBlockIds);

                    if (approvalResult.isSkipFuture()) run.getAutoApprove().add("*");
                    if (approvalResult.getOutput() != null) {
                        output = approvalResult.getOutput();
                        operatorEdited = true;
                    }
                    clearApprovalTimeout(run);

                } catch (JumpToBlockException jumpEx) {
                    clearApprovalTimeout(run);
                    saveBlockOutput(run, blockId, output, null, blockStart, Instant.now());
                    if (!run.getCompletedBlocks().contains(blockId)) run.getCompletedBlocks().add(blockId);

                    for (Map.Entry<String, Map<String, Object>> entry : jumpEx.getInjectedOutputs().entrySet()) {
                        saveBlockOutput(run, entry.getKey(), entry.getValue());
                        if (!run.getCompletedBlocks().contains(entry.getKey())) run.getCompletedBlocks().add(entry.getKey());
                    }
                    runRepository.save(run);

                    String targetBlockId = jumpEx.getTargetBlockId();
                    int targetIndex = -1;
                    for (int j = 0; j < sortedBlocks.size(); j++) {
                        if (sortedBlocks.get(j).getId().equals(targetBlockId)) { targetIndex = j; break; }
                    }
                    if (targetIndex < 0) throw new RuntimeException("Jump target block not found: " + targetBlockId);
                    i = targetIndex;
                    continue;

                } catch (PipelineRejectedException rejectEx) {
                    markFailed(run, "Pipeline rejected: " + rejectEx.getMessage(), rejectEx);
                    throw rejectEx;
                }
            }

            // Inject per-block LLM summary (models used, tokens, cost) so operators see at a
            // glance how expensive each block was. Pulls live data from LlmCall audit rows.
            if (!cacheHit) {
                injectLlmSummary(output, run.getId(), blockId);
            }

            // Persist cache metadata only on the final save: operator-edited outputs and cache
            // hits must not become future cache sources. cacheKey/scope are stored for both
            // cache-source rows and cache-hit copies so admin UI can trace provenance.
            boolean finalCacheable = blockCacheable && !cacheHit && !operatorEdited;
            saveBlockOutput(run, blockId, output, inputs, blockStart, Instant.now(),
                cacheKey, cacheScope, finalCacheable, cacheHitSourceId);
            if (!run.getCompletedBlocks().contains(blockId)) run.getCompletedBlocks().add(blockId);
            if (output.containsKey("requirement") && output.get("requirement") instanceof String newReq) {
                currentRequirement = newReq;
            }

            if (notifyOnly && wsHandler != null) {
                wsHandler.sendAutoNotify(run.getId(), blockId, block.getDescription(), output);
            }

            // --- Verify / orchestrator loopback check ---
            if ("verify".equals(blockConfig.getBlock()) || "orchestrator".equals(blockConfig.getBlock())) {
                Boolean passed = output.get("passed") instanceof Boolean b ? b : true;
                if (!passed) {
                    // Escalate = architectural/blocking problem; skip loopback, fail immediately
                    String action = output.get("action") instanceof String s ? s : "";
                    if ("escalate".equals(action)) {
                        String issues = output.get("issues") instanceof String s ? s : "escalated";
                        markFailed(run, "Orchestrator '" + blockId + "' escalated: " + issues);
                        throw new RuntimeException(
                            "Orchestrator '" + blockId + "' escalated (blocking issue, manual intervention required): " + issues);
                    }

                    VerifyConfig verifyConfig = blockConfig.getVerify();
                    if (verifyConfig != null && verifyConfig.getOnFail() != null
                            && "loopback".equals(verifyConfig.getOnFail().getAction())) {
                        String targetId = verifyConfig.getOnFail().getTarget();
                        int maxIter = verifyConfig.getOnFail().getMaxIterations();
                        String loopKey = "loopback:" + blockId + ":" + targetId;

                        @SuppressWarnings("unchecked")
                        List<String> issues = output.get("issues") instanceof List<?> l
                            ? (List<String>) l
                            : output.get("issues") instanceof String s && !s.isBlank()
                                ? List.of(s) : List.of();
                        Map<String, Object> extraCtx = resolveInjectContext(
                            verifyConfig.getOnFail().getInjectContext(), run);
                        // Hardcode-promote reviewer's structured verdict into _loopback so the
                        // downstream codegen block (agent_with_tools) can render the same per-id
                        // table as the reviewer uses, without depending on per-pipeline YAML
                        // inject_context. See OrchestratorBlock review-mode (PR1+PR2).
                        if (output.get("checklist_status") instanceof List<?> cs && !cs.isEmpty()) {
                            extraCtx.put("checklist_status", cs);
                        }
                        if (output.get("regressions") instanceof List<?> rg && !rg.isEmpty()) {
                            extraCtx.put("regressions", rg);
                        }

                        runRepository.save(run);
                        int newI = handleLoopback(loopKey, targetId, blockId, maxIter,
                            issues, extraCtx, sortedBlocks, i, run);
                        if (newI >= 0) {
                            if (wsHandler != null) wsHandler.sendBlockComplete(run.getId(), blockId, output);
                            i = newI;
                            continue;
                        }
                        // Max iterations exhausted on local model — try escalation ladder.
                        int escI = tryEscalateAfterLoopback(run, blockId, targetId,
                                verifyConfig.getOnFail().getEscalation(), maxIter,
                                output, inputs, issues, extraCtx, sortedBlocks, i, blockStart);
                        if (escI >= 0) {
                            if (wsHandler != null) wsHandler.sendBlockComplete(run.getId(), blockId, output);
                            i = escI;
                            continue;
                        }
                        markFailed(run, "Verify block '" + blockId + "' failed after max iterations");
                        throw new RuntimeException("Verify '" + blockId + "' exceeded max loopback iterations");
                    }
                }
            }

            // --- agent_verify loopback + manual override gate (Phase E / H3) ---
            // agent_verify uses block-level on_failure (not verify.on_fail). When the
            // tool-using verifier exceeds max_iterations, instead of hard-failing the
            // run we surface an approval gate so the operator can mark FAIL items as
            // PASS overrides (per smart-checklist design). Records manual_overrides
            // in the block output for audit.
            if ("agent_verify".equals(blockConfig.getBlock())) {
                Boolean verifyPassed = output.get("passed") instanceof Boolean b ? b : true;
                if (!verifyPassed) {
                    OnFailureConfig vFail = blockConfig.getOnFailure();
                    if (vFail != null && "loopback".equals(vFail.getAction())) {
                        String targetId = vFail.getTarget();
                        int maxIter = vFail.getMaxIterations();
                        String loopKey = "loopback:" + blockId + ":" + targetId;

                        @SuppressWarnings("unchecked")
                        List<String> issues = output.get("issues") instanceof List<?> l
                            ? (List<String>) l
                            : output.get("issues") instanceof String s && !s.isBlank()
                                ? List.of(s) : List.of();
                        Map<String, Object> extraCtx = resolveInjectContext(vFail.getInjectContext(), run);

                        runRepository.save(run);
                        int newI = handleLoopback(loopKey, targetId, blockId, maxIter,
                            issues, extraCtx, sortedBlocks, i, run);
                        if (newI >= 0) {
                            if (wsHandler != null) wsHandler.sendBlockComplete(run.getId(), blockId, output);
                            i = newI;
                            continue;
                        }
                        // Try cloud/human escalation BEFORE the manual override gate — cloud
                        // is cheaper than asking a human; human-step then plays the same role
                        // as the existing override gate (just with a richer bundle).
                        int escI = tryEscalateAfterLoopback(run, blockId, targetId,
                                vFail.getEscalation(), maxIter,
                                output, inputs, issues, extraCtx, sortedBlocks, i, blockStart);
                        if (escI >= 0) {
                            if (wsHandler != null) wsHandler.sendBlockComplete(run.getId(), blockId, output);
                            i = escI;
                            continue;
                        }
                        // Max iterations exceeded — H3 manual override gate.
                        List<String> remainingIds = sortedBlocks.subList(i + 1, sortedBlocks.size())
                            .stream().map(BlockConfig::getId).toList();
                        String desc = "Agent verify '" + blockId + "' exceeded max iterations. "
                            + "Approve to override failed items as PASS (records manual_overrides), "
                            + "or reject to fail the run.";
                        if (wsHandler != null) wsHandler.sendApprovalRequest(run.getId(), blockId, desc, output);
                        try {
                            ApprovalResult ar = approvalGate.request(blockId, "agent_verify", desc,
                                inputs, output, remainingIds);
                            if (ar != null && "APPROVED".equals(ar.getStatus())) {
                                Map<String, Object> overrideOut = new LinkedHashMap<>(output);
                                overrideOut.put("passed", true);
                                Object overrides = ar.getOutput() != null
                                    ? ar.getOutput().get("manual_overrides") : null;
                                overrideOut.put("manual_overrides",
                                    overrides != null ? overrides : "all_failed_items_overridden");
                                overrideOut.put("override_reason",
                                    ar.getOutput() != null ? ar.getOutput().get("reason") : null);
                                saveBlockOutput(run, blockId, overrideOut, null, blockStart, Instant.now());
                                if (!run.getCompletedBlocks().contains(blockId)) {
                                    run.getCompletedBlocks().add(blockId);
                                }
                                runRepository.save(run);
                                if (wsHandler != null) wsHandler.sendBlockComplete(run.getId(), blockId, overrideOut);
                                log.warn("agent_verify '{}' completed via manual override after max iterations", blockId);
                                i++;
                                continue;
                            }
                        } catch (Exception e) {
                            log.warn("Manual override approval failed for '{}': {}", blockId, e.getMessage());
                        }
                        markFailed(run, "Agent verify '" + blockId + "' failed after max iterations and no manual override");
                        throw new RuntimeException("Agent verify '" + blockId + "' exhausted iterations");
                    }
                }
            }

            // --- on_failure loopback check for CI blocks ---
            OnFailureConfig onFailure = blockConfig.getOnFailure();
            if (onFailure != null && "loopback".equals(onFailure.getAction()) && isCiFailure(output, onFailure)) {
                String targetId = onFailure.getTarget();
                int maxIter = onFailure.getMaxIterations();
                String loopKey = "loopback:" + blockId + ":" + targetId;

                @SuppressWarnings("unchecked")
                List<String> issues = output.get("issues") instanceof List<?> l
                    ? (List<String>) l
                    : output.get("issues") instanceof String s && !s.isBlank()
                        ? List.of(s) : List.of();
                Map<String, Object> extraCtx = resolveInjectContext(onFailure.getInjectContext(), run);
                // Pass CI stages as extra context if present
                if (output.containsKey("stages")) extraCtx.put("ci_stages", output.get("stages"));

                runRepository.save(run);
                int newI = handleLoopback(loopKey, targetId, blockId, maxIter,
                    issues, extraCtx, sortedBlocks, i, run);
                if (newI >= 0) {
                    if (wsHandler != null) wsHandler.sendBlockComplete(run.getId(), blockId, output);
                    i = newI;
                    continue;
                }
                // Max iterations exhausted on local model — try escalation ladder.
                int escI = tryEscalateAfterLoopback(run, blockId, targetId,
                        onFailure.getEscalation(), maxIter,
                        output, inputs, issues, extraCtx, sortedBlocks, i, blockStart);
                if (escI >= 0) {
                    if (wsHandler != null) wsHandler.sendBlockComplete(run.getId(), blockId, output);
                    i = escI;
                    continue;
                }
                // If max exceeded and action is warn/skip — fall through; otherwise fail
                if (!"warn".equals(onFailure.getAction()) && !"skip".equals(onFailure.getAction())) {
                    markFailed(run, "CI block '" + blockId + "' failed after max loopback iterations");
                    throw new RuntimeException("CI block '" + blockId + "' exceeded max loopback iterations");
                }
            }

            runRepository.save(run);

            if (wsHandler != null) wsHandler.sendBlockComplete(run.getId(), blockId, output);
            if (metrics != null) metrics.recordBlockCompleted(blockConfig.getBlock(),
                java.time.Duration.ofMillis(System.currentTimeMillis() - blockStartedAt));
            log.info("Completed block: {}", blockId);
            i++;
        }

        // All blocks done
        run.setStatus(RunStatus.COMPLETED);
        run.setCurrentBlock(null);
        run.setCurrentOperation(null);
        run.setLastActivityAt(Instant.now());
        run.setCompletedAt(Instant.now());
        finalizeCost(run);
        runRepository.save(run);

        if (wsHandler != null) wsHandler.sendRunComplete(run.getId(), "COMPLETED");
        if (metrics != null) metrics.recordRunComplete("completed");
        log.info("Pipeline run {} completed successfully", run.getId());

        // Re-index files that the pipeline just modified so the next run starts
        // with an up-to-date semantic index. Fire-and-forget; failures here must
        // not affect the operator's view of the completed run.
        scheduleProjectReindexDelta(run);
    }

    /**
     * After a successful run, if the project working dir is a git repo and the latest
     * commit touched files we know about, ask {@link com.workflow.knowledge.ProjectIndexService}
     * to re-embed only those files. {@code @Async} on the service method keeps this
     * off the run-completion thread.
     */
    private void scheduleProjectReindexDelta(PipelineRun run) {
        if (projectIndexService == null || !projectIndexService.isAvailable()) return;
        String slug = run.getProjectSlug();
        if (slug == null || slug.isBlank()) return;
        try {
            java.util.List<String> changed = changedFilesSinceParent(run);
            if (changed.isEmpty()) return;
            projectIndexService.reindexDeltaAsync(slug, changed);
        } catch (Exception e) {
            log.debug("Post-run reindex delta skipped: {}", e.getMessage());
        }
    }

    /**
     * Runs {@code git diff --name-only HEAD~1 HEAD} in the project's working dir to
     * list paths the run's commit modified. Returns an empty list when the project
     * isn't a git repo or git isn't available.
     */
    private java.util.List<String> changedFilesSinceParent(PipelineRun run) {
        try {
            // Look up the project's working dir from the same lookup ProjectIndexService uses.
            String slug = run.getProjectSlug();
            com.workflow.project.Project p = projectIndexService == null ? null
                : findProjectBySlug(slug);
            String workingDir = p == null ? null : p.getWorkingDir();
            if (workingDir == null || workingDir.isBlank()) return java.util.List.of();
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only", "HEAD~1", "HEAD");
            pb.directory(new java.io.File(workingDir));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return java.util.List.of();
            }
            String out = new String(proc.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            java.util.List<String> paths = new java.util.ArrayList<>();
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty()) paths.add(t);
            }
            return paths;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    private com.workflow.project.Project findProjectBySlug(String slug) {
        if (slug == null) return null;
        try {
            return projectRepository == null ? null
                : projectRepository.findBySlug(slug).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void handleCancellation(PipelineRun run) {
        log.info("Run {} cancelled", run.getId());
        run.setStatus(RunStatus.FAILED);
        run.setError("Cancelled by user");
        run.setCompletedAt(Instant.now());
        runRepository.save(run);
        if (wsHandler != null) wsHandler.sendRunComplete(run.getId(), "FAILED");
    }

    private void markFailed(PipelineRun run, String error) {
        run.setStatus(RunStatus.FAILED);
        run.setError(error);
        run.setCompletedAt(Instant.now());
        finalizeCost(run);
        runRepository.save(run);
        if (wsHandler != null) wsHandler.sendRunComplete(run.getId(), "FAILED");
        if (metrics != null) metrics.recordRunComplete("failed");
        broadcastFailureNotification(run, run.getCurrentBlock(), error);
    }

    private void markFailed(PipelineRun run, String summary, Throwable cause) {
        run.setStatus(RunStatus.FAILED);
        run.setError(summary + "\n\n" + formatStackTrace(cause));
        run.setCompletedAt(Instant.now());
        finalizeCost(run);
        runRepository.save(run);
        if (wsHandler != null) wsHandler.sendRunComplete(run.getId(), "FAILED");
        if (metrics != null) metrics.recordRunComplete("failed");
        broadcastFailureNotification(run, run.getCurrentBlock(), summary);
    }

    private void broadcastFailureNotification(PipelineRun run, String blockId, String errorSummary) {
        if (notificationChannelRegistry == null) return;
        if (notificationRateLimiter != null
                && !notificationRateLimiter.shouldSend(
                        run.getId() != null ? run.getId().toString() : null, blockId)) {
            return;
        }
        try {
            String truncated = errorSummary != null && errorSummary.length() > 300
                    ? errorSummary.substring(0, 300) + "…" : errorSummary;
            String runLink = "http://localhost:5120/runs/" + run.getId();
            String body = (blockId != null ? "Block **" + blockId + "** failed.\n" : "")
                + (truncated != null ? truncated + "\n" : "")
                + "Run: " + run.getId();
            var msg = new com.workflow.notifications.NotificationMessage(
                com.workflow.notifications.NotificationMessage.Severity.HIGH,
                "Pipeline failed — " + (run.getRequirement() != null
                    ? run.getRequirement().substring(0, Math.min(60, run.getRequirement().length()))
                    : run.getId()),
                body, runLink,
                java.util.Map.of("runId", run.getId() != null ? run.getId().toString() : "",
                                 "blockId", blockId != null ? blockId : "")
            );
            if (notificationChannelRegistry.supports("ui")) {
                notificationChannelRegistry.get("ui").send(msg, java.util.Map.of());
            }
            String slackWebhook = System.getenv("SLACK_WEBHOOK_URL");
            if (slackWebhook != null && !slackWebhook.isBlank()
                    && notificationChannelRegistry.supports("slack")) {
                notificationChannelRegistry.get("slack").send(msg, java.util.Map.of("webhookUrl", slackWebhook));
            }
            String tgToken = System.getenv("TELEGRAM_BOT_TOKEN");
            String tgChatId = System.getenv("TELEGRAM_CHAT_ID");
            if (tgToken != null && !tgToken.isBlank() && tgChatId != null && !tgChatId.isBlank()
                    && notificationChannelRegistry.supports("telegram")) {
                notificationChannelRegistry.get("telegram").send(msg,
                    java.util.Map.of("botToken", tgToken, "chatId", tgChatId));
            }
        } catch (Exception e) {
            log.warn("Failed to broadcast failure notification for run={} block={}: {}",
                run.getId(), blockId, e.getMessage());
        }
    }

    /**
     * Snapshot {@link PipelineRun#getTotalCostUsd()} at terminal state. Reads the
     * sum of every {@link com.workflow.llm.LlmCall#getCostUsd()} attached to this
     * run via {@link com.workflow.llm.LlmCallRepository#sumCostByRunId(java.util.UUID)}.
     * Robust to repository being absent in tests.
     */
    private void finalizeCost(PipelineRun run) {
        if (llmCallRepository == null || run == null || run.getId() == null) return;
        try {
            double total = llmCallRepository.sumCostByRunId(run.getId());
            run.setTotalCostUsd(total);
        } catch (Exception e) {
            log.debug("finalizeCost failed for run {}: {}", run.getId(), e.getMessage());
        }
    }

    private static String formatStackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        // Walk the full cause chain
        Throwable current = t;
        boolean first = true;
        while (current != null) {
            if (!first) pw.println("\nCaused by:");
            current.printStackTrace(pw);
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
            first = false;
        }
        return sw.toString();
    }

    private void clearApprovalTimeout(PipelineRun run) {
        run.setPausedAt(null);
        run.setApprovalTimeoutSeconds(null);
        run.setApprovalTimeoutAction(null);
    }

    /**
     * Runs the block with optional retry on transient failures (non-Interrupted exceptions).
     * Backoff grows exponentially up to {@code max_backoff_ms}. Per-block retry config wins
     * over no retry; blocks that need fail-fast semantics simply omit {@code retry:}.
     */
    private Map<String, Object> runWithRetry(Block block, Map<String, Object> inputs,
                                              com.workflow.config.BlockConfig effectiveConfig,
                                              PipelineRun run) throws Exception {
        com.workflow.config.RetryConfig retry = effectiveConfig.getRetry();
        if (retry == null || retry.getMaxAttempts() <= 1) {
            return runBlock(block, inputs, effectiveConfig, run);
        }
        long backoff = retry.getBackoffMs();
        Exception last = null;
        for (int attempt = 1; attempt <= retry.getMaxAttempts(); attempt++) {
            try {
                return runBlock(block, inputs, effectiveConfig, run);
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception e) {
                last = e;
                if (attempt == retry.getMaxAttempts()) break;
                log.warn("Block '{}' attempt {}/{} failed: {} — retrying in {}ms",
                    effectiveConfig.getId(), attempt, retry.getMaxAttempts(), e.getMessage(), backoff);
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, retry.getMaxBackoffMs());
            }
        }
        throw last != null ? last : new RuntimeException("Retry exhausted with no exception captured");
    }

    /**
     * Executes a single block attempt, optionally bounded by {@code block.timeout_seconds}.
     * When the runtime block-timeout is disabled (default) or no positive timeout is set, runs
     * inline on the caller thread — zero behavioural change. When enabled, runs on a daemon
     * worker with the caller's {@link com.workflow.project.ProjectContext} /
     * {@link com.workflow.account.TenantContext} / {@link com.workflow.llm.LlmCallContext}
     * propagated, and a {@link TimeoutException}-derived failure (retryable like any other) on
     * overrun. {@link InterruptedException} thrown by the block is unwrapped and re-propagated
     * so cancellation still short-circuits the retry loop.
     */
    private Map<String, Object> runBlock(Block block, Map<String, Object> inputs,
                                         com.workflow.config.BlockConfig effectiveConfig,
                                         PipelineRun run) throws Exception {
        Integer timeout = effectiveConfig.getTimeoutSeconds();
        if (!blockTimeoutEnabled || timeout == null || timeout <= 0) {
            return block.run(inputs, effectiveConfig, run);
        }

        // Capture caller-thread context so the worker sees the same run/project/tenant/provider.
        String projectSlug = com.workflow.project.ProjectContext.get();
        Long accountId = com.workflow.account.TenantContext.get();
        com.workflow.llm.LlmCallContext.Context llmCtx =
            com.workflow.llm.LlmCallContext.current().orElse(null);

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "block-timeout-" + effectiveConfig.getId());
            t.setDaemon(true);
            return t;
        });
        Future<Map<String, Object>> future = exec.submit(() -> {
            com.workflow.project.ProjectContext.set(projectSlug);
            com.workflow.account.TenantContext.set(accountId);
            if (llmCtx != null) {
                com.workflow.llm.LlmCallContext.set(llmCtx.runId(), llmCtx.blockId(), llmCtx.preferredProvider());
            }
            try {
                return block.run(inputs, effectiveConfig, run);
            } finally {
                com.workflow.llm.LlmCallContext.clear();
                com.workflow.project.ProjectContext.clear();
                com.workflow.account.TenantContext.clear();
            }
        });
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);  // interrupt the worker; blocks honouring interruption stop here
            throw new RuntimeException("Block '" + effectiveConfig.getId() + "' timed out after "
                + timeout + "s", te);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof InterruptedException ie) throw ie;
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause != null ? cause : ee);
        } finally {
            exec.shutdownNow();
        }
    }

    private void broadcastApprovalNotification(PipelineRun run, String blockId, String description) {
        if (notificationChannelRegistry == null) return;
        try {
            String runLink = "http://localhost:5120/runs/" + run.getId();
            String body = "Block **" + blockId + "** is waiting for approval.\n" +
                (description != null ? description + "\n" : "") +
                "Run: " + run.getId();
            var msg = new com.workflow.notifications.NotificationMessage(
                com.workflow.notifications.NotificationMessage.Severity.HIGH,
                "Approval required — pipeline paused",
                body,
                runLink,
                Map.of("runId", run.getId().toString(), "blockId", blockId)
            );

            // UI channel — no extra config needed
            if (notificationChannelRegistry.supports("ui")) {
                notificationChannelRegistry.get("ui").send(msg, Map.of());
            }

            // Slack — driven by SLACK_WEBHOOK_URL env var
            String slackWebhook = System.getenv("SLACK_WEBHOOK_URL");
            if (slackWebhook != null && !slackWebhook.isBlank() && notificationChannelRegistry.supports("slack")) {
                notificationChannelRegistry.get("slack").send(msg, Map.of("webhookUrl", slackWebhook));
            }

            // Telegram — driven by TELEGRAM_BOT_TOKEN + TELEGRAM_CHAT_ID env vars
            String tgToken = System.getenv("TELEGRAM_BOT_TOKEN");
            String tgChatId = System.getenv("TELEGRAM_CHAT_ID");
            if (tgToken != null && !tgToken.isBlank() && tgChatId != null && !tgChatId.isBlank()
                    && notificationChannelRegistry.supports("telegram")) {
                notificationChannelRegistry.get("telegram").send(msg,
                    Map.of("botToken", tgToken, "chatId", tgChatId));
            }
        } catch (Exception e) {
            log.warn("Failed to broadcast approval notification for run={} block={}: {}", run.getId(), blockId, e.getMessage());
        }
    }

    private boolean isProdDeployBlock(BlockConfig blockConfig) {
        if ("deploy".equals(blockConfig.getBlock())) {
            Object env = blockConfig.getConfig().get("environment");
            if ("prod".equals(env) || "production".equals(env)) return true;
        }
        // Legacy block IDs that clearly target prod.
        return "deploy_prod".equals(blockConfig.getId()) || "prod_deploy".equals(blockConfig.getId());
    }

    private void captureConfigSnapshot(PipelineRun run, PipelineConfig config) {
        try {
            run.setConfigSnapshotJson(objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            log.warn("Failed to snapshot pipeline config for run {}: {}", run.getId(), e.getMessage());
        }
    }

    /**
     * Aggregates LlmCall audit rows for the given (run, block) and stamps a {@code _llm} summary
     * object into the block output. UI reads this to show per-block LLM usage and cost.
     */
    private void injectLlmSummary(Map<String, Object> output, UUID runId, String blockId) {
        if (llmCallRepository == null) {
            log.warn("injectLlmSummary({}): llmCallRepository is null — skipping", blockId);
            return;
        }
        if (output == null) {
            log.warn("injectLlmSummary({}): output is null — skipping", blockId);
            return;
        }
        try {
            java.util.List<com.workflow.llm.LlmCall> calls = llmCallRepository.findByRunIdAndBlockId(runId, blockId);
            log.info("injectLlmSummary({}): found {} LlmCall rows", blockId, calls == null ? 0 : calls.size());
            if (calls == null || calls.isEmpty()) return;
            java.util.Map<String, long[]> perModel = new java.util.LinkedHashMap<>(); // model -> [calls, tokensIn, tokensOut]
            long totalCalls = 0;
            long totalIn = 0;
            long totalOut = 0;
            double totalCost = 0.0;
            for (com.workflow.llm.LlmCall c : calls) {
                String key = c.getModel() != null ? c.getModel() : "unknown";
                long[] agg = perModel.computeIfAbsent(key, k -> new long[]{0, 0, 0});
                agg[0]++;
                agg[1] += c.getTokensIn();
                agg[2] += c.getTokensOut();
                totalIn  += c.getTokensIn();
                totalOut += c.getTokensOut();
                totalCost += c.getCostUsd();
                totalCalls++;
            }
            java.util.List<java.util.Map<String, Object>> modelsList = new java.util.ArrayList<>();
            for (Map.Entry<String, long[]> e : perModel.entrySet()) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("model", e.getKey());
                m.put("calls", e.getValue()[0]);
                m.put("tokens_in", e.getValue()[1]);
                m.put("tokens_out", e.getValue()[2]);
                modelsList.add(m);
            }
            java.util.Map<String, Object> summary = new java.util.LinkedHashMap<>();
            summary.put("calls", totalCalls);
            summary.put("tokens_in", totalIn);
            summary.put("tokens_out", totalOut);
            summary.put("cost_usd", Math.round(totalCost * 100000.0) / 100000.0);
            summary.put("models", modelsList);
            try {
                output.put("_llm", summary);
                log.info("injectLlmSummary({}): wrote _llm summary (calls={}, tok={}↑/{}↓, ${})",
                    blockId, totalCalls, totalIn, totalOut, totalCost);
            } catch (UnsupportedOperationException uoe) {
                log.warn("injectLlmSummary({}): output map is immutable — can't inject _llm", blockId);
            }
        } catch (Exception e) {
            log.warn("Failed to inject LLM summary for block {}: {}", blockId, e.getMessage());
        }
    }

    private void saveBlockOutput(PipelineRun run, String blockId, Map<String, Object> output) {
        saveBlockOutput(run, blockId, output, null, null, null);
    }

    private void saveBlockOutput(PipelineRun run, String blockId, Map<String, Object> output,
                                  Map<String, Object> inputs) {
        saveBlockOutput(run, blockId, output, inputs, null, null);
    }

    private void saveBlockOutput(PipelineRun run, String blockId, Map<String, Object> output,
                                  Instant startedAt, Instant completedAt) {
        saveBlockOutput(run, blockId, output, null, startedAt, completedAt);
    }

    private void saveBlockOutput(PipelineRun run, String blockId, Map<String, Object> output,
                                  Map<String, Object> inputs, Instant startedAt, Instant completedAt) {
        saveBlockOutput(run, blockId, output, inputs, startedAt, completedAt, null, null, null, null);
    }

    private void saveBlockOutput(PipelineRun run, String blockId, Map<String, Object> output,
                                  Map<String, Object> inputs, Instant startedAt, Instant completedAt,
                                  String cacheKey, String cacheScope, Boolean cacheable, Long sourceOutputId) {
        try {
            String outputJson = objectMapper.writeValueAsString(output);
            String inputJson = inputs != null ? objectMapper.writeValueAsString(inputs) : null;
            // Internal bookkeeping keys (_loopback_*, etc.) are always overwritten — there is
            // only ever one per blockId and they carry no user-visible history.
            if (blockId.startsWith("_")) {
                Optional<BlockOutput> existing = run.getOutputs().stream()
                    .filter(o -> o.getBlockId().equals(blockId)).reduce((a, b) -> b);
                if (existing.isPresent()) {
                    BlockOutput bo = existing.get();
                    bo.setOutputJson(outputJson);
                    if (inputJson != null) bo.setInputJson(inputJson);
                    if (startedAt != null && bo.getStartedAt() == null) bo.setStartedAt(startedAt);
                    if (completedAt != null) bo.setCompletedAt(completedAt);
                    blockOutputRepository.save(bo);
                    return;
                }
            }
            // For regular blocks always create a new row so every loopback iteration is preserved.
            int nextIteration = (int) run.getOutputs().stream()
                .filter(o -> o.getBlockId().equals(blockId)).count();
            BlockOutput blockOutput = BlockOutput.builder()
                .run(run).blockId(blockId).outputJson(outputJson).inputJson(inputJson)
                .startedAt(startedAt).completedAt(completedAt).iteration(nextIteration).build();
            blockOutput.setCacheKey(cacheKey);
            blockOutput.setCacheScope(cacheScope);
            blockOutput.setCacheable(cacheable);
            blockOutput.setSourceOutputId(sourceOutputId);
            blockOutputRepository.save(blockOutput);
            run.getOutputs().add(blockOutput);
        } catch (Exception e) {
            log.error("Failed to save output for block {}: {}", blockId, e.getMessage(), e);
        }
    }
}
