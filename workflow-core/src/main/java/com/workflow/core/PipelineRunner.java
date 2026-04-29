package com.workflow.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.api.RunWebSocketHandler;
import com.workflow.blocks.Block;
import com.workflow.config.ApprovalMode;
import com.workflow.config.BlockConfig;
import com.workflow.config.GateConfig;
import com.workflow.config.IntegrationsConfig;
import com.workflow.config.OnFailureConfig;
import com.workflow.config.PipelineConfig;
import com.workflow.config.VerifyConfig;
import com.workflow.model.IntegrationConfig;
import com.workflow.model.IntegrationConfigRepository;
import com.workflow.model.IntegrationType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    @Autowired
    private ApprovalGate approvalGate;

    @Autowired
    private IntegrationConfigRepository integrationConfigRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private RunWebSocketHandler wsHandler;

    @Autowired
    private AgentProfileResolver agentProfileResolver;

    @Autowired(required = false)
    private com.workflow.observability.PipelineMetrics metrics;

    @Autowired
    private ProdDeployMutex prodDeployMutex;

    @Autowired
    private com.workflow.project.TechStackPromptEnricher techStackPromptEnricher;

    private Map<String, Block> blockRegistry;

    /** Tracks virtual threads running active pipelines for cancellation */
    private final ConcurrentHashMap<UUID, Thread> runningThreads = new ConcurrentHashMap<>();

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
        PipelineRun pipelineRun = PipelineRun.builder()
            .id(runId)
            .pipelineName(config.getName())
            .requirement(requirement)
            .status(RunStatus.RUNNING)
            .startedAt(java.time.Instant.now())
            .completedBlocks(new java.util.HashSet<>())
            .autoApprove(new java.util.HashSet<>())
            .outputs(new ArrayList<>())
            .build();
        pipelineRun.setDryRun(dryRun);
        pipelineRun.setProjectSlug(com.workflow.project.ProjectContext.get());
        if (runInputs != null && !runInputs.isEmpty()) {
            try { pipelineRun.setRunInputsJson(objectMapper.writeValueAsString(runInputs)); }
            catch (Exception e) { log.warn("Failed to serialize runInputs: {}", e.getMessage()); }
        }
        techStackPromptEnricher.enrich(config, pipelineRun.getProjectSlug());
        captureConfigSnapshot(pipelineRun, config);
        runRepository.save(pipelineRun);

        // Notify global subscribers (e.g. active-runs badge) that a new run has started.
        if (wsHandler != null) wsHandler.sendRunStarted(runId);
        if (metrics != null) metrics.recordRunStarted();

        CompletableFuture<Void> future = new CompletableFuture<>();
        String projectSlug = pipelineRun.getProjectSlug();
        Thread t = Thread.startVirtualThread(() -> {
            com.workflow.project.ProjectContext.set(projectSlug);
            try {
                executeBlocks(config, pipelineRun, requirement, false);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                com.workflow.project.ProjectContext.clear();
                runningThreads.remove(runId);
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
        PipelineRun pipelineRun = PipelineRun.builder()
            .id(runId)
            .pipelineName(config.getName())
            .requirement(requirement)
            .status(RunStatus.RUNNING)
            .startedAt(java.time.Instant.now())
            .completedBlocks(new java.util.HashSet<>())
            .autoApprove(new java.util.HashSet<>())
            .outputs(new ArrayList<>())
            .build();
        pipelineRun.setProjectSlug(com.workflow.project.ProjectContext.get());
        if (runInputs != null && !runInputs.isEmpty()) {
            try { pipelineRun.setRunInputsJson(objectMapper.writeValueAsString(runInputs)); }
            catch (Exception e) { log.warn("Failed to serialize runInputs: {}", e.getMessage()); }
        }
        techStackPromptEnricher.enrich(config, pipelineRun.getProjectSlug());
        captureConfigSnapshot(pipelineRun, config);

        List<BlockConfig> sorted = topologicalSort(config.getPipeline());
        for (BlockConfig blockConfig : sorted) {
            if (blockConfig.getId().equals(fromBlockId)) break;
            pipelineRun.getCompletedBlocks().add(blockConfig.getId());
            // Only persist a BlockOutput when the entry point actually injects real data.
            // Empty pre-entry blocks must NOT appear in the UI as completed.
            Map<String, Object> injected = injectedOutputs != null
                ? injectedOutputs.getOrDefault(blockConfig.getId(), new HashMap<>())
                : new HashMap<>();
            if (!injected.isEmpty()) {
                try {
                    String outputJson = objectMapper.writeValueAsString(injected);
                    BlockOutput blockOutput = BlockOutput.builder()
                        .run(pipelineRun).blockId(blockConfig.getId()).outputJson(outputJson).build();
                    pipelineRun.getOutputs().add(blockOutput);
                } catch (Exception e) {
                    log.warn("Failed to serialize injected output for block {}: {}", blockConfig.getId(), e.getMessage());
                }
            }
        }

        runRepository.save(pipelineRun);

        // Notify global subscribers that a new run has started.
        if (wsHandler != null) wsHandler.sendRunStarted(runId);

        CompletableFuture<Void> future = new CompletableFuture<>();
        String projectSlugFrom = pipelineRun.getProjectSlug();
        Thread t = Thread.startVirtualThread(() -> {
            com.workflow.project.ProjectContext.set(projectSlugFrom);
            try {
                executeBlocks(config, pipelineRun, requirement, true);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                com.workflow.project.ProjectContext.clear();
                runningThreads.remove(runId);
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
        Thread t = Thread.startVirtualThread(() -> {
            com.workflow.project.ProjectContext.set(projectSlugResume);
            try {
                executeBlocks(config, run, run.getRequirement(), true);
            } catch (Exception e) {
                log.error("Error resuming run {}: {}", runId, e.getMessage(), e);
            } finally {
                com.workflow.project.ProjectContext.clear();
                runningThreads.remove(runId);
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

    private List<BlockConfig> topologicalSort(List<BlockConfig> blocks) {
        Map<String, BlockConfig> blockMap = new LinkedHashMap<>();
        for (BlockConfig b : blocks) blockMap.put(b.getId(), b);

        for (BlockConfig b : blocks) {
            if (b.getDependsOn() != null) {
                for (String dep : b.getDependsOn()) {
                    if (!blockMap.containsKey(dep)) {
                        throw new IllegalStateException(
                            "Block '" + b.getId() + "' depends on unknown block '" + dep + "'");
                    }
                }
            }
        }

        List<BlockConfig> sorted = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> inStack = new HashSet<>();

        for (String blockId : blockMap.keySet()) {
            if (!visited.contains(blockId)) dfsVisit(blockId, blockMap, visited, inStack, sorted);
        }
        return sorted;
    }

    private void dfsVisit(String blockId, Map<String, BlockConfig> blockMap,
                           Set<String> visited, Set<String> inStack, List<BlockConfig> sorted) {
        if (inStack.contains(blockId)) throw new IllegalStateException("Cycle detected involving block: " + blockId);
        if (visited.contains(blockId)) return;

        inStack.add(blockId);
        BlockConfig block = blockMap.get(blockId);
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
                    .filter(o -> o.getBlockId().equals(depId)).findFirst();
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
            .filter(o -> o.getBlockId().equals(loopbackKey)).findFirst();
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

    private Map<String, Object> resolveIntegrationConfigs(PipelineConfig pipelineConfig) {
        Map<String, Object> integrationData = new HashMap<>();
        IntegrationsConfig integrations = pipelineConfig.getIntegrations();

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
        });

        return integrationData;
    }

    private Optional<IntegrationConfig> resolveIntegration(String configName, IntegrationType type) {
        String scope = com.workflow.project.ProjectContext.get();
        if (configName != null && !configName.isBlank()) {
            Optional<IntegrationConfig> scoped = integrationConfigRepository.findByNameAndProjectSlug(configName, scope);
            if (scoped.isPresent()) return scoped;
            // Fall back to cross-project lookup for legacy configs without a project slug set.
            return integrationConfigRepository.findByName(configName);
        }
        Optional<IntegrationConfig> scoped =
            integrationConfigRepository.findByTypeAndIsDefaultTrueAndProjectSlug(type, scope);
        if (scoped.isPresent()) return scoped;
        return integrationConfigRepository.findByTypeAndIsDefaultTrue(type);
    }

    /**
     * Evaluates a simple condition expression: $.block_id.field [| length] OPERATOR value
     * Supported operators: ==, !=, >, <, >=, <=
     */
    @SuppressWarnings("unchecked")
    private boolean evaluateCondition(String expr, PipelineRun run) {
        if (expr == null || expr.isBlank()) return true;
        try {
            // Pattern: $.block_id.field [| length] OP value
            Pattern pattern = Pattern.compile(
                "^\\$\\.([\\w]+)\\.([\\w]+)(\\s*\\|\\s*length)?\\s*(==|!=|>=|<=|>|<)\\s*(.+)$");
            Matcher m = pattern.matcher(expr.trim());
            if (!m.matches()) {
                log.warn("Condition expression not parseable: {}", expr);
                return true;
            }

            String blockId = m.group(1);
            String field = m.group(2);
            boolean useLength = m.group(3) != null;
            String op = m.group(4);
            String rawValue = m.group(5).trim().replaceAll("^['\"]|['\"]$", "");

            Optional<BlockOutput> outputOpt = run.getOutputs().stream()
                .filter(o -> o.getBlockId().equals(blockId)).findFirst();
            if (outputOpt.isEmpty()) return true;

            Map<String, Object> blockOutput = objectMapper.readValue(
                outputOpt.get().getOutputJson(), new TypeReference<Map<String, Object>>() {});
            Object fieldVal = blockOutput.get(field);

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
                if (parts.length < 2) continue;
                String blockId = parts[0];
                String field = parts[1];
                Optional<BlockOutput> outputOpt = run.getOutputs().stream()
                    .filter(o -> o.getBlockId().equals(blockId)).findFirst();
                if (outputOpt.isEmpty()) continue;
                Map<String, Object> blockOutput = objectMapper.readValue(
                    outputOpt.get().getOutputJson(), new TypeReference<Map<String, Object>>() {});
                Object val = blockOutput.get(field);
                if (val != null) resolved.put(key, val);
            } catch (Exception e) {
                log.warn("Failed to resolve inject_context ref '{}': {}", ref, e.getMessage());
            }
        }
        return resolved;
    }

    /**
     * Checks whether a CI block output represents a failure.
     */
    private boolean isCiFailure(Map<String, Object> output, OnFailureConfig cfg) {
        if (output == null || cfg == null) return false;
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

        run.getLoopIterations().put(loopKey, iterations + 1);

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
            run.setLoopHistoryJson(objectMapper.writeValueAsString(history));
        } catch (Exception e) {
            log.warn("Failed to update loop history: {}", e.getMessage());
        }

        log.info("Loopback triggered: {} → {} (iteration {}/{})", fromBlockId, targetId, iterations + 1, maxIterations);
        return targetIndex;
    }

    private void executeBlocks(PipelineConfig config, PipelineRun run,
                                String currentRequirement, boolean skipCompleted) {
        List<BlockConfig> sortedBlocks = topologicalSort(config.getPipeline());
        Map<String, Object> integrationConfigs = resolveIntegrationConfigs(config);

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
                saveBlockOutput(run, blockId, skipped);
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
                    saveBlockOutput(run, blockId, skipped);
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
            run.setStatus(RunStatus.RUNNING);
            runRepository.save(run);

            if (wsHandler != null) wsHandler.sendBlockStarted(run.getId(), blockId);
            if (metrics != null) metrics.recordBlockStarted(blockConfig.getBlock());
            long blockStartedAt = System.currentTimeMillis();

            Map<String, Object> inputs = gatherInputs(blockConfig, run, currentRequirement);
            if (run.isDryRun()) inputs.put("_dry_run", true);
            BlockConfig effectiveBlockConfig = blockConfig.withMergedConfig(integrationConfigs);

            // Resolve agent profile into effective agent config and skills
            effectiveBlockConfig.setAgent(agentProfileResolver.resolveAgent(effectiveBlockConfig, config.getDefaults()));
            effectiveBlockConfig.setSkills(agentProfileResolver.resolveSkills(effectiveBlockConfig));

            Block block = blockRegistry.get(blockConfig.getBlock());
            if (block == null) {
                String error = "Unknown block type: " + blockConfig.getBlock();
                log.error(error);
                markFailed(run, error);
                throw new RuntimeException(error);
            }

            boolean prodDeploy = isProdDeployBlock(blockConfig);
            Map<String, Object> output;
            try {
                log.info("Running block: {} ({})", blockId, blockConfig.getBlock());
                if (prodDeploy) prodDeployMutex.acquire(run.getId());
                com.workflow.llm.LlmCallContext.set(run.getId(), blockId);
                try {
                    output = runWithRetry(block, inputs, effectiveBlockConfig, run);
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
                saveBlockOutput(run, blockId, output, inputs);

                run.setStatus(RunStatus.PAUSED_FOR_APPROVAL);
                run.setPausedAt(Instant.now());
                Integer timeoutSeconds = blockConfig.getTimeoutSeconds();
                run.setApprovalTimeoutSeconds(timeoutSeconds);
                run.setApprovalTimeoutAction(blockConfig.getOnTimeout() != null && blockConfig.getOnTimeout().getAction() != null
                    ? blockConfig.getOnTimeout().getAction().getValue()
                    : null);
                runRepository.save(run);

                if (wsHandler != null) wsHandler.sendApprovalRequest(run.getId(), blockId, block.getDescription(), output);

                try {
                    ApprovalResult approvalResult = approvalGate.request(
                        blockId, blockConfig.getBlock(), block.getDescription(), inputs, output, remainingBlockIds);

                    if (approvalResult.isSkipFuture()) run.getAutoApprove().add("*");
                    if (approvalResult.getOutput() != null) output = approvalResult.getOutput();
                    clearApprovalTimeout(run);

                } catch (JumpToBlockException jumpEx) {
                    clearApprovalTimeout(run);
                    saveBlockOutput(run, blockId, output);
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

            saveBlockOutput(run, blockId, output, inputs);
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
                            ? (List<String>) l : List.of();
                        Map<String, Object> extraCtx = resolveInjectContext(
                            verifyConfig.getOnFail().getInjectContext(), run);

                        runRepository.save(run);
                        int newI = handleLoopback(loopKey, targetId, blockId, maxIter,
                            issues, extraCtx, sortedBlocks, i, run);
                        if (newI >= 0) {
                            if (wsHandler != null) wsHandler.sendBlockComplete(run.getId(), blockId, output);
                            i = newI;
                            continue;
                        } else {
                            markFailed(run, "Verify block '" + blockId + "' failed after max iterations");
                            throw new RuntimeException("Verify '" + blockId + "' exceeded max loopback iterations");
                        }
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
                    ? (List<String>) l : List.of();
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
        run.setCompletedAt(Instant.now());
        runRepository.save(run);

        if (wsHandler != null) wsHandler.sendRunComplete(run.getId(), "COMPLETED");
        if (metrics != null) metrics.recordRunComplete("completed");
        log.info("Pipeline run {} completed successfully", run.getId());
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
        runRepository.save(run);
        if (wsHandler != null) wsHandler.sendRunComplete(run.getId(), "FAILED");
        if (metrics != null) metrics.recordRunComplete("failed");
    }

    private void markFailed(PipelineRun run, String summary, Throwable cause) {
        run.setStatus(RunStatus.FAILED);
        run.setError(summary + "\n\n" + formatStackTrace(cause));
        run.setCompletedAt(Instant.now());
        runRepository.save(run);
        if (wsHandler != null) wsHandler.sendRunComplete(run.getId(), "FAILED");
        if (metrics != null) metrics.recordRunComplete("failed");
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
            return block.run(inputs, effectiveConfig, run);
        }
        long backoff = retry.getBackoffMs();
        Exception last = null;
        for (int attempt = 1; attempt <= retry.getMaxAttempts(); attempt++) {
            try {
                return block.run(inputs, effectiveConfig, run);
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

    private void saveBlockOutput(PipelineRun run, String blockId, Map<String, Object> output) {
        saveBlockOutput(run, blockId, output, null);
    }

    private void saveBlockOutput(PipelineRun run, String blockId, Map<String, Object> output, Map<String, Object> inputs) {
        try {
            String outputJson = objectMapper.writeValueAsString(output);
            String inputJson = inputs != null ? objectMapper.writeValueAsString(inputs) : null;
            Optional<BlockOutput> existing = run.getOutputs().stream()
                .filter(o -> o.getBlockId().equals(blockId)).findFirst();
            if (existing.isPresent()) {
                existing.get().setOutputJson(outputJson);
                if (inputJson != null) existing.get().setInputJson(inputJson);
                blockOutputRepository.save(existing.get());
            } else {
                BlockOutput blockOutput = BlockOutput.builder()
                    .run(run).blockId(blockId).outputJson(outputJson).inputJson(inputJson).build();
                blockOutputRepository.save(blockOutput);
                run.getOutputs().add(blockOutput);
            }
        } catch (Exception e) {
            log.error("Failed to save output for block {}: {}", blockId, e.getMessage(), e);
        }
    }
}
