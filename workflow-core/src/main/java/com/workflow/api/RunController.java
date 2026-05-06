package com.workflow.api;

import com.workflow.config.InvalidPipelineException;
import com.workflow.config.PipelineConfig;
import com.workflow.config.PipelineConfigLoader;
import com.workflow.config.PipelineConfigValidator;
import com.workflow.config.PipelineConfigWriter;
import com.workflow.config.ValidationResult;
import com.workflow.core.*;
import com.workflow.core.EntryPointResolver.DetectionResult;
import com.workflow.project.ProjectContext;
import com.workflow.tools.ToolCallAudit;
import com.workflow.tools.ToolCallAuditRepository;
import com.workflow.project.ProjectRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class RunController {

    private static final Logger log = LoggerFactory.getLogger(RunController.class);

    @Autowired
    private PipelineRunner pipelineRunner;

    @Autowired
    private PipelineRunRepository pipelineRunRepository;

    @Autowired
    private PipelineConfigLoader pipelineConfigLoader;

    @Autowired
    private PipelineConfigWriter pipelineConfigWriter;

    @Autowired
    private PipelineConfigValidator pipelineConfigValidator;

    @Autowired
    private EntryPointResolver entryPointResolver;

    @Autowired
    private SmartDetectService smartDetectService;

    @Value("${workflow.config-dir:./config}")
    private String configDir;

    @Autowired(required = false)
    private WebSocketApprovalGate webSocketApprovalGate;

    @Autowired
    private RunReturnService runReturnService;

    @Autowired
    private com.workflow.security.audit.AuditService auditService;

    @Autowired
    private com.workflow.core.KillSwitchService killSwitchService;

    @Autowired(required = false)
    private ToolCallAuditRepository toolCallAuditRepository;

    @Autowired(required = false)
    private com.workflow.llm.LlmCallRepository llmCallRepository;

    @Autowired(required = false)
    private com.workflow.tools.BashApprovalGate bashApprovalGate;

    @Autowired(required = false)
    private ProjectRepository projectRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BlockOutputRepository blockOutputRepository;

    @PreAuthorize("hasAnyRole('OPERATOR', 'RELEASE_MANAGER', 'ADMIN')")
    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> startRun(@RequestBody Map<String, Object> request) {
        String configPath = (String) request.get("configPath");
        String requirement = (String) request.getOrDefault("requirement", "");
        String youtrackIssue = (String) request.get("youtrackIssue");
        String branchName = (String) request.get("branchName");
        Object mrIidRaw = request.get("mrIid");
        if (youtrackIssue != null && !youtrackIssue.isBlank() && requirement.isBlank()) {
            requirement = youtrackIssue;
        }
        String fromBlock = (String) request.get("fromBlock");
        String runIdStr = (String) request.get("runId");

        if (configPath == null || configPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "configPath is required"));
        }

        com.workflow.core.KillSwitch ks = killSwitchService.current();
        if (ks.isActive()) {
            auditService.recordFailure("RUN_START", "run", "", Map.of(
                "reason", "kill_switch_active",
                "configPath", configPath));
            return ResponseEntity.status(503).body(Map.of(
                "error", "Kill switch is active — new runs are blocked",
                "reason", ks.getReason() != null ? ks.getReason() : "",
                "activatedBy", ks.getActivatedBy() != null ? ks.getActivatedBy() : ""));
        }

        String entryPointId = (String) request.get("entryPointId");
        boolean dryRun = Boolean.TRUE.equals(request.get("dryRun"));
        boolean autoApproveAll = Boolean.TRUE.equals(request.get("autoApproveAll"));
        UUID runId = runIdStr != null ? UUID.fromString(runIdStr) : UUID.randomUUID();

        // Capture named run inputs (e.g. task_file, build_command) for ${input.key} interpolation.
        @SuppressWarnings("unchecked")
        Map<String, Object> namedInputs = (Map<String, Object>) request.getOrDefault("inputs", new HashMap<>());
        // Signal PipelineRunner to add "*" to autoApprove — skips all manual approval gates.
        if (autoApproveAll) namedInputs.put("_autoApproveAll", true);

        // Provider routing: if the operator did not pin a provider on the run, fall back to
        // the project default. Pipeline blocks then gate on $.input.provider via condition.
        if (!namedInputs.containsKey("provider")) {
            String projectSlug = ProjectContext.get();
            if (projectSlug != null && !projectSlug.isBlank()) {
                projectRepository.findBySlug(projectSlug).ifPresent(p ->
                    namedInputs.put("provider", p.getEffectiveDefaultProvider().name()));
            }
            namedInputs.putIfAbsent("provider", com.workflow.llm.LlmProvider.OPENROUTER.name());
        }

        try {
            PipelineConfig config = pipelineConfigLoader.load(Paths.get(configPath));

            // Pre-run validation gate: invalid configs are rejected before any DB write or
            // run-thread start. The full structured error list is returned so the UI can
            // surface it without parsing free-form text.
            ValidationResult validation = pipelineConfigValidator.validate(config);
            if (!validation.valid()) {
                auditService.recordFailure("RUN_START", "run", "", Map.of(
                    "reason", "invalid_pipeline_config",
                    "configPath", configPath,
                    "errorCount", validation.errors().size()));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "Invalid pipeline config");
                body.put("errors", validation.errors());
                return ResponseEntity.badRequest().body(body);
            }

            // Build userInputs map for the entry point resolver
            Map<String, Object> userInputs = new HashMap<>();
            if (youtrackIssue != null) userInputs.put("youtrackIssue", youtrackIssue);
            if (branchName != null)    userInputs.put("branchName", branchName);
            if (mrIidRaw != null)      userInputs.put("mrIid", mrIidRaw);

            // Resolve named entry point if provided
            if (entryPointId != null && !entryPointId.isBlank() && (fromBlock == null || fromBlock.isBlank())) {
                var ep = config.getEntryPoints().stream()
                    .filter(e -> e.getId().equals(entryPointId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Entry point not found: " + entryPointId));
                fromBlock = ep.getFromBlock();

                // Resolve all injections via EntryPointResolver (handles all source types)
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> explicitInjections =
                    (Map<String, Map<String, Object>>) request.getOrDefault("injectedOutputs", new HashMap<>());
                Map<String, Map<String, Object>> resolved =
                    entryPointResolver.resolveInjections(ep, userInputs, config);
                // Explicit injectedOutputs from request override resolved ones
                resolved.putAll(explicitInjections);

                log.info("Entry point '{}' resolved: fromBlock={}, injections={}", entryPointId, fromBlock, resolved.keySet());
                pipelineRunner.runFrom(config, requirement, fromBlock, resolved, runId, namedInputs);
            } else if (fromBlock != null && !fromBlock.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> injectedOutputs =
                    (Map<String, Map<String, Object>>) request.get("injectedOutputs");
                pipelineRunner.runFrom(config, requirement, fromBlock, injectedOutputs, runId, namedInputs);
            } else {
                pipelineRunner.run(config, requirement, runId, dryRun, namedInputs);
            }

            // Persist entryPointId so the UI can reconstruct restart calls.
            if (entryPointId != null && !entryPointId.isBlank()) {
                pipelineRunRepository.findById(runId).ifPresent(run -> {
                    run.setEntryPointId(entryPointId);
                    pipelineRunRepository.save(run);
                });
            }

            Map<String, Object> response = new HashMap<>();
            response.put("runId", runId.toString());
            response.put("id", runId.toString());
            response.put("status", "RUNNING");

            auditService.record("RUN_START", "run", runId.toString(), Map.of(
                "configPath", configPath,
                "entryPointId", entryPointId != null ? entryPointId : "",
                "fromBlock", fromBlock != null ? fromBlock : ""));

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("Failed to load pipeline config from {}: {}", configPath, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to load config: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to start run: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/runs/stats")
    public ResponseEntity<Map<String, Object>> getRunStats() {
        String projectSlug = com.workflow.project.ProjectContext.get();
        long activeRuns = pipelineRunRepository.countByProjectSlugAndStatusIn(
            projectSlug, List.of(RunStatus.RUNNING, RunStatus.PAUSED_FOR_APPROVAL));
        long awaitingApproval = pipelineRunRepository.countByProjectSlugAndStatusIn(
            projectSlug, List.of(RunStatus.PAUSED_FOR_APPROVAL));

        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();

        // Primary count: runs that have completedAt populated (normal case).
        long completedToday = pipelineRunRepository.countByProjectSlugAndStatusAndCompletedAtAfter(projectSlug, RunStatus.COMPLETED, startOfDay);
        long failedToday    = pipelineRunRepository.countByProjectSlugAndStatusAndCompletedAtAfter(projectSlug, RunStatus.FAILED, startOfDay);

        // Fallback for legacy rows created before completedAt was added: completedAt is NULL
        // but startedAt falls within today.  These runs definitely finished today because
        // a run that is still in progress would have status RUNNING/PAUSED_FOR_APPROVAL.
        completedToday += pipelineRunRepository
            .countByProjectSlugAndStatusAndCompletedAtIsNullAndStartedAtAfter(projectSlug, RunStatus.COMPLETED, startOfDay);
        failedToday += pipelineRunRepository
            .countByProjectSlugAndStatusAndCompletedAtIsNullAndStartedAtAfter(projectSlug, RunStatus.FAILED, startOfDay);

        Map<String, Object> stats = new HashMap<>();
        stats.put("activeRuns", activeRuns);
        stats.put("awaitingApproval", awaitingApproval);
        stats.put("completedToday", completedToday);
        stats.put("failedToday", failedToday);
        return ResponseEntity.ok(stats);
    }

    /**
     * GET /api/runs
     *
     * N+1 avoidance strategy:
     *  - No filters: uses a native-SQL summary projection ({@code findAllSummary}) that fetches
     *    only scalar columns + a subquery for blockCount.  Cost: 2 queries regardless of page size.
     *  - Filters active: uses {@code findAll(spec, pageable)} backed by the
     *    {@code PipelineRun.withCompletedBlocks} EntityGraph, which loads completedBlocks in a
     *    single batch query instead of one SELECT per row.  Cost: 3 queries (count + data + batch).
     */
    @Transactional(readOnly = true)
    @GetMapping("/runs")
    public ResponseEntity<Map<String, Object>> listRuns(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String pipelineName,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "false") boolean allProjects) {

        size = Math.min(size, 100);

        // Project scoping is always used unless allProjects=true (global history view).
        boolean hasFilters = true;

        List<Map<String, Object>> content;
        long totalElements;
        int totalPages;
        int pageNumber;
        int pageSize;

        if (!hasFilters) {
            // Fast path: scalar projection, no collection joins.
            Pageable pageable = PageRequest.of(page, size);
            Page<PipelineRunSummary> resultPage = pipelineRunRepository.findAllSummary(pageable);

            content = resultPage.getContent().stream()
                .map(run -> {
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("id", run.getId());
                    dto.put("pipelineName", run.getPipelineName());
                    dto.put("requirement", run.getRequirement());
                    dto.put("status", run.getStatus());
                    dto.put("currentBlock", run.getCurrentBlock());
                    dto.put("error", run.getError());
                    dto.put("startedAt", run.getStartedAt());
                    dto.put("completedAt", run.getCompletedAt());
                    dto.put("blockCount", run.getBlockCount());
                    return dto;
                })
                .collect(Collectors.toList());

            totalElements = resultPage.getTotalElements();
            totalPages    = resultPage.getTotalPages();
            pageNumber    = resultPage.getNumber();
            pageSize      = resultPage.getSize();

        } else {
            // Filtered path: build a Specification, use EntityGraph to batch-load completedBlocks.
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt"));

            String currentProject = com.workflow.project.ProjectContext.get();
            Specification<PipelineRun> spec = (root, query, cb) -> {
                List<Predicate> predicates = new ArrayList<>();
                if (!allProjects) {
                    predicates.add(cb.equal(root.get("projectSlug"), currentProject));
                }

                if (status != null && !status.isEmpty()) {
                    List<RunStatus> statuses = status.stream()
                        .map(s -> { try { return RunStatus.valueOf(s); } catch (Exception e) { return null; } })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                    if (!statuses.isEmpty()) {
                        predicates.add(root.get("status").in(statuses));
                    }
                }

                if (pipelineName != null && !pipelineName.isBlank()) {
                    predicates.add(cb.equal(root.get("pipelineName"), pipelineName));
                }

                if (search != null && !search.isBlank()) {
                    predicates.add(cb.like(cb.lower(root.get("requirement")), "%" + search.toLowerCase() + "%"));
                }

                if (from != null && !from.isBlank()) {
                    try {
                        Instant fromInstant = LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toInstant();
                        predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), fromInstant));
                    } catch (Exception ignored) {}
                }

                if (to != null && !to.isBlank()) {
                    try {
                        Instant toInstant = LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                        predicates.add(cb.lessThan(root.get("startedAt"), toInstant));
                    } catch (Exception ignored) {}
                }

                return cb.and(predicates.toArray(new Predicate[0]));
            };

            Page<PipelineRun> resultPage = pipelineRunRepository.findAll(spec, pageable);

            content = resultPage.getContent().stream()
                .map(run -> {
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("id", run.getId().toString());
                    dto.put("pipelineName", run.getPipelineName());
                    dto.put("requirement", run.getRequirement());
                    dto.put("status", run.getStatus().name());
                    dto.put("currentBlock", run.getCurrentBlock());
                    dto.put("error", run.getError());
                    dto.put("startedAt", run.getStartedAt());
                    dto.put("completedAt", run.getCompletedAt());
                    // completedBlocks is already loaded by the EntityGraph batch query
                    dto.put("blockCount", run.getCompletedBlocks().size());
                    return dto;
                })
                .collect(Collectors.toList());

            totalElements = resultPage.getTotalElements();
            totalPages    = resultPage.getTotalPages();
            pageNumber    = resultPage.getNumber();
            pageSize      = resultPage.getSize();
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", content);
        response.put("totalElements", totalElements);
        response.put("totalPages", totalPages);
        response.put("page", pageNumber);
        response.put("size", pageSize);

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/runs/detect
     *
     * Auto-detects the most appropriate pipeline entry point for the given YouTrack issue
     * by checking for existing subtasks, branches, and open MRs/PRs.
     *
     * Request: {@code {"configPath": "...", "youtrackIssue": "PROJ-42"}}
     * Response: {@code {"suggestedEntryPointId": "branch_exists", "detected": {...}}}
     */
    @PostMapping("/runs/detect")
    public ResponseEntity<Map<String, Object>> detectEntryPoint(@RequestBody Map<String, Object> request) {
        String configPath = (String) request.get("configPath");
        String youtrackIssue = (String) request.get("youtrackIssue");

        if (configPath == null || configPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "configPath is required"));
        }
        if (youtrackIssue == null || youtrackIssue.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "youtrackIssue is required"));
        }

        try {
            PipelineConfig config = pipelineConfigLoader.load(Paths.get(configPath));
            DetectionResult result = entryPointResolver.autoDetect(youtrackIssue, config);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("suggestedEntryPointId", result.suggestedEntryPointId());
            response.put("detected", result.detected());
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to load config: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Detection failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Smart entry-point detection: accepts any freeform input (tracker URL, issue ID,
     * stack trace, free text) and returns a suggested entry point with confidence score.
     *
     * <p>Request: {@code {"rawInput": "...", "configPath": "..." (optional)}}
     * <p>Response: {@code {"suggested": {...}, "explanation": "...", "detectedInputs": {...},
     *              "clarificationQuestion": "..." (optional)}}
     */
    @PostMapping("/runs/smart-detect")
    public ResponseEntity<Map<String, Object>> smartDetect(@RequestBody Map<String, Object> request) {
        String rawInput    = (String) request.get("rawInput");
        String configPath  = (String) request.get("configPath");
        String projectSlug = ProjectContext.get();

        if (rawInput == null || rawInput.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "rawInput is required"));
        }

        try {
            Map<String, Object> result = smartDetectService.detect(rawInput, configPath, projectSlug);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("smart-detect failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> getRun(@PathVariable String runId) {
        try {
            UUID id = UUID.fromString(runId);
            // Use the EntityGraph variant so all three collections are loaded
            // eagerly in a single batch rather than lazily on serialization.
            Optional<PipelineRun> runOpt = pipelineRunRepository.findWithCollectionsById(id);
            if (runOpt.isEmpty()) return ResponseEntity.notFound().build();

            PipelineRun run = runOpt.get();

            // Build chronologically ordered events from BlockOutput timestamps.
            // Internal entries (loopback context, _loopback_*) are excluded.
            List<BlockOutput> orderedOutputs = blockOutputRepository.findByRunIdOrderByStartedAt(id);
            List<Map<String, Object>> events = orderedOutputs.stream()
                .filter(b -> !b.getBlockId().startsWith("_"))
                .map(b -> {
                    Long durationMs = null;
                    if (b.getStartedAt() != null && b.getCompletedAt() != null) {
                        durationMs = b.getCompletedAt().toEpochMilli() - b.getStartedAt().toEpochMilli();
                    }
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("blockId", b.getBlockId());
                    event.put("startedAt", b.getStartedAt());
                    event.put("completedAt", b.getCompletedAt());
                    event.put("durationMs", durationMs);
                    return event;
                })
                .toList();

            Map<String, Object> body = objectMapper.convertValue(run,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            body.put("events", events);

            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PreAuthorize("hasAnyRole('OPERATOR', 'RELEASE_MANAGER', 'ADMIN')")
    @PostMapping("/runs/{runId}/return")
    public ResponseEntity<Map<String, Object>> returnRun(@PathVariable String runId,
                                                         @RequestBody Map<String, Object> request) {
        String targetBlock = (String) request.get("targetBlock");
        String comment = (String) request.get("comment");
        String configPath = (String) request.get("configPath");

        if (targetBlock == null || targetBlock.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "targetBlock is required"));
        }
        if (configPath == null || configPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "configPath is required"));
        }

        try {
            UUID id = UUID.fromString(runId);
            PipelineRun run = runReturnService.returnToBlock(id, configPath, targetBlock, comment);
            auditService.record("RUN_RETURN", "run", runId, Map.of(
                "targetBlock", targetBlock,
                "commentLength", comment != null ? comment.length() : 0));
            return ResponseEntity.ok(Map.of(
                "success", true,
                "runId", runId,
                "targetBlock", targetBlock,
                "status", run.getStatus().name()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to return run {}: {}", runId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('OPERATOR', 'RELEASE_MANAGER', 'ADMIN')")
    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelRun(@PathVariable String runId) {
        try {
            UUID id = UUID.fromString(runId);
            boolean cancelled = pipelineRunner.cancelRun(id);
            if (cancelled) {
                auditService.record("RUN_CANCEL", "run", runId, Map.of());
                return ResponseEntity.ok(Map.of("success", true));
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "Run is not in a cancellable state"));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid run ID"));
        } catch (Exception e) {
            log.error("Failed to cancel run {}: {}", runId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('OPERATOR', 'RELEASE_MANAGER', 'ADMIN')")
    @PostMapping("/runs/{runId}/approval")
    public ResponseEntity<Map<String, Object>> resolveApproval(@PathVariable String runId,
                                                                @RequestBody Map<String, Object> request) {
        if (webSocketApprovalGate == null) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "WebSocket approval gate is not active. Run in GUI mode."));
        }

        String blockId = (String) request.get("blockId");
        String decision = (String) request.get("decision");
        boolean skipFuture = Boolean.TRUE.equals(request.get("skipFuture"));
        String targetBlockId = (String) request.get("targetBlockId");

        @SuppressWarnings("unchecked")
        // null when absent — non-null empty map would overwrite the real block output in PipelineRunner
        Map<String, Object> output = (Map<String, Object>) request.get("output");

        if (blockId == null || decision == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "blockId and decision are required"));
        }

        try {
            ApprovalResult result;

            switch (decision.toUpperCase()) {
                case "APPROVE" -> result = ApprovalResult.builder()
                    .status("APPROVED").output(output).skipFuture(skipFuture).build();
                case "EDIT" -> result = ApprovalResult.builder()
                    .status("EDITED").output(output).skipFuture(skipFuture).build();
                case "SKIP" -> result = ApprovalResult.builder()
                    .status("APPROVED").output(output).skipFuture(true).build();
                case "JUMP" -> result = ApprovalResult.builder()
                    .status("APPROVED").output(output).jumpTarget(targetBlockId).build();
                case "REJECT" -> {
                    webSocketApprovalGate.resolveApproval(blockId,
                        ApprovalResult.builder().status("REJECTED").output(output).build());
                    return ResponseEntity.ok(Map.of("success", true));
                }
                default -> { return ResponseEntity.badRequest().body(Map.of("error", "Unknown decision: " + decision)); }
            }

            boolean resolved = webSocketApprovalGate.resolveApproval(blockId, result);
            if (!resolved) {
                // Pipeline thread died (e.g. server restart) but run is still PAUSED_FOR_APPROVAL.
                // Directly mark the paused block as completed in DB so the resumed thread skips
                // the LLM re-run (and avoids the spurious APPROVAL_REQUEST WebSocket message).
                PipelineRun stuckRun = pipelineRunRepository.findById(UUID.fromString(runId)).orElse(null);
                if (stuckRun != null && stuckRun.getStatus() == RunStatus.PAUSED_FOR_APPROVAL) {
                    try {
                        String pausedBlockId = stuckRun.getCurrentBlock() != null
                            ? stuckRun.getCurrentBlock() : blockId;

                        // If EDIT: update the persisted block output with the user's edits
                        if ("EDIT".equalsIgnoreCase(decision) && !result.getOutput().isEmpty()) {
                            try {
                                String editedJson = objectMapper.writeValueAsString(result.getOutput());
                                java.util.List<BlockOutput> existing = blockOutputRepository
                                    .findByRunIdAndBlockId(stuckRun.getId(), pausedBlockId);
                                if (!existing.isEmpty()) {
                                    BlockOutput bo = existing.get(existing.size() - 1);
                                    bo.setOutputJson(editedJson);
                                    blockOutputRepository.save(bo);
                                } else {
                                    blockOutputRepository.save(BlockOutput.builder()
                                        .run(stuckRun).blockId(pausedBlockId).outputJson(editedJson).build());
                                }
                            } catch (Exception editEx) {
                                log.warn("Failed to update block output for EDIT decision: {}", editEx.getMessage());
                            }
                        }

                        // Mark paused block as completed — resume() will skip it (skipCompleted=true)
                        stuckRun.getCompletedBlocks().add(pausedBlockId);

                        if (result.isSkipFuture()) {
                            stuckRun.getAutoApprove().add("*");
                        }

                        stuckRun.setStatus(RunStatus.RUNNING);
                        pipelineRunRepository.save(stuckRun);

                        PipelineConfig config = resolveConfigForRun(stuckRun);
                        if (config == null) {
                            return ResponseEntity.internalServerError().body(
                                Map.of("error", "Cannot resolve pipeline config for run " + runId));
                        }
                        pipelineRunner.resume(config, runId);
                        log.info("Resumed stuck run {} after approval for block {} (direct DB completion)", runId, pausedBlockId);
                    } catch (Exception resumeEx) {
                        log.error("Failed to resume stuck run {}: {}", runId, resumeEx.getMessage(), resumeEx);
                        return ResponseEntity.internalServerError().body(
                            Map.of("error", "Run was stuck after restart; resume failed: " + resumeEx.getMessage()));
                    }
                }
            }
            auditService.record("APPROVAL_RESOLVE", "run", runId, Map.of(
                "blockId", blockId,
                "decision", decision,
                "skipFuture", skipFuture,
                "resumed", !resolved));
            return ResponseEntity.ok(Map.of("success", true, "blockId", blockId, "decision", decision, "resumed", !resolved));

        } catch (Exception e) {
            log.error("Failed to resolve approval for block {}: {}", blockId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/pipelines/entry-points")
    public ResponseEntity<?> getEntryPoints(@RequestParam String configPath) {
        try {
            PipelineConfig config = pipelineConfigLoader.load(Paths.get(configPath));
            List<Map<String, Object>> result = config.getEntryPoints().stream()
                .map(ep -> {
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("id", ep.getId());
                    dto.put("name", ep.getName());
                    dto.put("description", ep.getDescription());
                    dto.put("fromBlock", ep.getFromBlock());
                    dto.put("requiresInput", ep.getRequiresInput());
                    dto.put("autoDetect", ep.getAutoDetect());
                    // Expose which user-facing fields this entry point needs
                    dto.put("inputFields", resolveInputFields(ep.getRequiresInput()));
                    return dto;
                })
                .collect(Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to load config: " + e.getMessage()));
        }
    }

    /**
     * Maps the {@code requires_input} YAML value to a list of UI field descriptors.
     * The frontend renders these fields in the "Start run" form.
     */
    private List<Map<String, Object>> resolveInputFields(String requiresInput) {
        if (requiresInput == null) return List.of();
        return switch (requiresInput) {
            case "requirement" -> List.of(
                Map.of("name", "requirement", "label", "Requirement", "type", "textarea", "required", true));
            case "youtrack_issue" -> List.of(
                Map.of("name", "youtrackIssue", "label", "YouTrack Issue ID", "type", "text",
                       "placeholder", "PROJ-42", "required", true));
            case "youtrack_issue_and_branch" -> List.of(
                Map.of("name", "youtrackIssue", "label", "YouTrack Issue ID", "type", "text",
                       "placeholder", "PROJ-42", "required", true),
                Map.of("name", "branchName", "label", "Branch Name", "type", "text",
                       "placeholder", "feature/PROJ-42-my-feature", "required", true));
            case "youtrack_issue_and_mr" -> List.of(
                Map.of("name", "youtrackIssue", "label", "YouTrack Issue ID", "type", "text",
                       "placeholder", "PROJ-42", "required", true),
                Map.of("name", "mrIid", "label", "MR / PR Number", "type", "number",
                       "placeholder", "87", "required", true));
            case "task_file" -> List.of(
                Map.of("name", "task_file", "label", "Путь к файлу задачи (.md)", "type", "text",
                       "placeholder", "/projects/my-project/tasks/FEAT-001-my-task.md", "required", true));
            case "none" -> List.of();
            default -> List.of(
                Map.of("name", "requirement", "label", "Requirement", "type", "textarea", "required", true));
        };
    }

    @GetMapping("/pipelines")
    public ResponseEntity<List<Map<String, Object>>> listPipelines() {
        // Use current project's configDir if available, fall back to global default
        String effectiveConfigDir = configDir;
        String projectSlug = ProjectContext.get();
        if (projectSlug != null && projectRepository != null) {
            var project = projectRepository.findBySlug(projectSlug);
            if (project.isPresent() && project.get().getConfigDir() != null) {
                effectiveConfigDir = project.get().getConfigDir();
            }
        }
        Path dir = Paths.get(effectiveConfigDir);
        List<Path> configs = pipelineConfigLoader.listConfigs(dir);

        List<Map<String, Object>> result = configs.stream()
            .map(p -> {
                Map<String, Object> info = new HashMap<>();
                info.put("path", p.toString());
                info.put("name", p.getFileName().toString());
                try {
                    PipelineConfig config = pipelineConfigLoader.load(p);
                    info.put("pipelineName", config.getName());
                    info.put("description", config.getDescription());
                } catch (Exception e) {
                    info.put("error", "Failed to load: " + e.getMessage());
                }
                return info;
            })
            .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/pipelines/config
     *
     * <p>Returns the full {@link PipelineConfig} as JSON — Pipeline Editor consumes
     * this and round-trips it back unchanged via PUT.
     */
    @GetMapping("/pipelines/config")
    public ResponseEntity<?> getPipelineConfig(@RequestParam String configPath) {
        try {
            PipelineConfig config = pipelineConfigLoader.loadRaw(Paths.get(configPath));
            return ResponseEntity.ok(config);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to load config: " + e.getMessage()));
        }
    }

    /**
     * PUT /api/pipelines/config
     *
     * <p>Persists the full {@link PipelineConfig} JSON sent by the Pipeline Editor.
     * Validates first; on validation failure returns 400 with {@code {error, errors[]}}
     * matching {@code POST /api/pipelines/validate}'s error envelope so the UI can
     * render both the same way.
     */
    @PreAuthorize("hasAnyRole('OPERATOR', 'RELEASE_MANAGER', 'ADMIN')")
    @PutMapping("/pipelines/config")
    public ResponseEntity<?> savePipelineConfig(@RequestParam String configPath,
                                                 @RequestBody PipelineConfig request) {
        try {
            PipelineConfig saved = pipelineConfigWriter.writeFull(Paths.get(configPath), request);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("saved", true);
            body.put("config", saved);
            return ResponseEntity.ok(body);
        } catch (InvalidPipelineException e) {
            log.warn("Refused to save invalid pipeline config {}: {} errors",
                configPath, e.getResult() != null ? e.getResult().errors().size() : 0);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Invalid pipeline config");
            body.put("errors", e.getResult() != null ? e.getResult().errors() : List.of());
            return ResponseEntity.badRequest().body(body);
        } catch (IOException e) {
            log.error("Failed to save pipeline config {}: {}", configPath, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to save: " + e.getMessage()));
        }
    }

    /**
     * POST /api/pipelines/new
     *
     * <p>Creates a new pipeline YAML in the project's config directory by cloning the
     * built-in {@code feature.yaml} template. Replaces {@code name} and
     * {@code description} with caller-supplied values. Returns the new file path.
     */
    @PreAuthorize("hasAnyRole('OPERATOR', 'RELEASE_MANAGER', 'ADMIN')")
    @PostMapping("/pipelines/new")
    public ResponseEntity<?> createPipeline(@RequestBody Map<String, Object> request) {
        String slug = (String) request.get("slug");
        String displayName = (String) request.get("displayName");
        String description = (String) request.getOrDefault("description", "");

        if (slug == null || slug.isBlank() || !slug.matches("[a-z0-9][a-z0-9-]*")) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "slug must be a non-empty kebab-case string (a-z, 0-9, -)"));
        }
        if (displayName == null || displayName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "displayName is required"));
        }

        // Resolve effective config dir (project's, falling back to global).
        String effectiveConfigDir = configDir;
        String projectSlug = ProjectContext.get();
        if (projectSlug != null && projectRepository != null) {
            var project = projectRepository.findBySlug(projectSlug);
            if (project.isPresent() && project.get().getConfigDir() != null
                && !project.get().getConfigDir().isBlank()) {
                effectiveConfigDir = project.get().getConfigDir();
            }
        }

        Path dir = Paths.get(effectiveConfigDir);
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                "Cannot create config dir " + dir + ": " + e.getMessage()));
        }

        Path target = dir.resolve(slug + ".yaml");
        if (java.nio.file.Files.exists(target)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Pipeline already exists: " + target));
        }

        // Load the built-in feature.yaml template from classpath via a temp file
        // so the writer's loader runs against a real Path (matches the rest of
        // the load surface).
        PipelineConfig template;
        try (java.io.InputStream is = new org.springframework.core.io.ClassPathResource(
                "config/feature.yaml").getInputStream()) {
            byte[] yaml = is.readAllBytes();
            Path temp = java.nio.file.Files.createTempFile("feature-template-", ".yaml");
            java.nio.file.Files.write(temp, yaml);
            template = pipelineConfigLoader.loadRaw(temp);
            try { java.nio.file.Files.deleteIfExists(temp); } catch (IOException ignored) {}
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to load template: " + e.getMessage()));
        }
        template.setName(displayName);
        template.setDescription(description);

        try {
            pipelineConfigWriter.writeFull(target, template);
            return ResponseEntity.ok(Map.of(
                "path", target.toString(),
                "name", target.getFileName().toString(),
                "pipelineName", template.getName()));
        } catch (InvalidPipelineException e) {
            log.error("Built-in template is invalid?! errors: {}",
                e.getResult() != null ? e.getResult().errors() : List.of());
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Internal: built-in template failed validation"));
        } catch (IOException e) {
            log.error("Failed to write new pipeline {}: {}", target, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to write pipeline: " + e.getMessage()));
        }
    }

    /**
     * Explicit validation endpoint — returns the full {@link ValidationResult} for a
     * config without triggering a run. Used by the GUI's "Validate" button and CI pre-flight.
     *
     * <p>Loads via {@code loadRaw} so unexpanded {@code ${VAR}} env tokens in
     * {@code integrations} are preserved (they're not part of the validation surface).
     */
    @PostMapping("/pipelines/validate")
    public ResponseEntity<?> validatePipeline(@RequestParam String configPath) {
        try {
            PipelineConfig config = pipelineConfigLoader.loadRaw(Paths.get(configPath));
            ValidationResult result = pipelineConfigValidator.validate(config);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to load config: " + e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('OPERATOR', 'RELEASE_MANAGER', 'ADMIN')")
    @PostMapping("/runs/{runId}/bash-approval")
    public ResponseEntity<?> resolveBashApproval(@PathVariable UUID runId,
                                                  @RequestBody Map<String, Object> request) {
        if (bashApprovalGate == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Bash approval gate not available"));
        }
        String requestId = (String) request.get("requestId");
        boolean approved = Boolean.TRUE.equals(request.get("approved"));
        boolean allowAll = Boolean.TRUE.equals(request.get("allowAll"));
        String blockId = (String) request.get("blockId");

        if (requestId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "requestId is required"));
        }
        if (allowAll && blockId != null) {
            bashApprovalGate.autoApproveBlock(runId, blockId);
        }
        // allowAll implies approving the current command too
        boolean effectiveApproved = approved || allowAll;
        bashApprovalGate.resolve(requestId, effectiveApproved);
        auditService.record("BASH_APPROVAL", "run", runId.toString(),
            Map.of("requestId", requestId, "approved", effectiveApproved, "allowAll", allowAll));
        return ResponseEntity.ok(Map.of("success", true, "approved", effectiveApproved, "allowAll", allowAll));
    }

    @PreAuthorize("hasAnyRole('OPERATOR', 'RELEASE_MANAGER', 'ADMIN')")
    @GetMapping("/runs/{runId}/tool-calls")
    public ResponseEntity<?> getToolCalls(@PathVariable UUID runId) {
        if (toolCallAuditRepository == null) {
            return ResponseEntity.ok(List.of());
        }
        List<ToolCallAudit> calls = toolCallAuditRepository.findByRunIdOrderByTimestampAsc(runId);
        List<Map<String, Object>> result = calls.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("blockId", c.getBlockId());
            m.put("iteration", c.getIteration());
            m.put("toolName", c.getToolName());
            m.put("inputJson", c.getInputJson());
            m.put("isError", c.isError());
            m.put("durationMs", c.getDurationMs());
            if (c.getOutputText() != null && !c.getOutputText().isBlank()) {
                m.put("outputText", c.getOutputText());
            }
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @PreAuthorize("hasAnyRole('OPERATOR', 'RELEASE_MANAGER', 'ADMIN')")
    @GetMapping("/runs/{runId}/llm-calls")
    public ResponseEntity<?> getLlmCalls(@PathVariable UUID runId) {
        if (llmCallRepository == null) {
            return ResponseEntity.ok(List.of());
        }
        List<com.workflow.llm.LlmCall> calls = llmCallRepository.findByRunIdOrderByTimestampAsc(runId);
        List<Map<String, Object>> result = calls.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("blockId", c.getBlockId());
            m.put("iteration", c.getIteration());
            m.put("model", c.getModel());
            m.put("tokensIn", c.getTokensIn());
            m.put("tokensOut", c.getTokensOut());
            m.put("costUsd", c.getCostUsd());
            m.put("durationMs", c.getDurationMs());
            if (c.getProvider() != null) m.put("provider", c.getProvider().name());
            if (c.getFinishReason() != null) m.put("finishReason", c.getFinishReason());
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Resolves the PipelineConfig for a stuck/paused run.
     * Primary source: configSnapshotJson (saved at run creation).
     * Fallback: scan the config directory for a pipeline whose name matches the run's pipelineName.
     */
    private PipelineConfig resolveConfigForRun(PipelineRun run) {
        // Primary: use snapshot saved at run creation
        if (run.getConfigSnapshotJson() != null && !run.getConfigSnapshotJson().isBlank()) {
            try {
                return objectMapper.readValue(run.getConfigSnapshotJson(), PipelineConfig.class);
            } catch (Exception e) {
                log.warn("Failed to parse configSnapshotJson for run {}: {}", run.getId(), e.getMessage());
            }
        }
        // Fallback: find config file by pipeline name
        String pipelineName = run.getPipelineName();
        if (pipelineName == null) return null;
        try {
            java.nio.file.Path cfgDir = Paths.get(configDir);
            for (java.nio.file.Path p : pipelineConfigLoader.listConfigs(cfgDir)) {
                try {
                    PipelineConfig candidate = pipelineConfigLoader.load(p);
                    if (pipelineName.equals(candidate.getName())) {
                        log.info("Resolved config for stuck run {} via name match: {}", run.getId(), p);
                        return candidate;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("Failed to scan configs for run {}: {}", run.getId(), e.getMessage());
        }
        return null;
    }
}
