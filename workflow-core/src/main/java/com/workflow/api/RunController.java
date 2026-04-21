package com.workflow.api;

import com.workflow.config.PipelineConfig;
import com.workflow.config.PipelineConfigLoader;
import com.workflow.core.*;
import com.workflow.core.EntryPointResolver.DetectionResult;
import com.workflow.project.ProjectContext;
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
        UUID runId = runIdStr != null ? UUID.fromString(runIdStr) : UUID.randomUUID();

        // Capture named run inputs (e.g. task_file, build_command) for ${input.key} interpolation.
        @SuppressWarnings("unchecked")
        Map<String, Object> namedInputs = (Map<String, Object>) request.getOrDefault("inputs", new HashMap<>());

        try {
            PipelineConfig config = pipelineConfigLoader.load(Paths.get(configPath));

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
        long activeRuns = pipelineRunRepository.countByStatusIn(
            List.of(RunStatus.RUNNING, RunStatus.PAUSED_FOR_APPROVAL));
        long awaitingApproval = pipelineRunRepository.countByStatusIn(
            List.of(RunStatus.PAUSED_FOR_APPROVAL));

        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();

        // Primary count: runs that have completedAt populated (normal case).
        long completedToday = pipelineRunRepository.countByStatusAndCompletedAtAfter(RunStatus.COMPLETED, startOfDay);
        long failedToday    = pipelineRunRepository.countByStatusAndCompletedAtAfter(RunStatus.FAILED, startOfDay);

        // Fallback for legacy rows created before completedAt was added: completedAt is NULL
        // but startedAt falls within today.  These runs definitely finished today because
        // a run that is still in progress would have status RUNNING/PAUSED_FOR_APPROVAL.
        completedToday += pipelineRunRepository
            .countByStatusAndCompletedAtIsNullAndStartedAtAfter(RunStatus.COMPLETED, startOfDay);
        failedToday += pipelineRunRepository
            .countByStatusAndCompletedAtIsNullAndStartedAtAfter(RunStatus.FAILED, startOfDay);

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
            @RequestParam(defaultValue = "25") int size) {

        size = Math.min(size, 100);

        // Project scoping is now always mandatory, so the filtered Specification path is
        // always taken — the native-SQL fast path doesn't have a WHERE projectSlug clause.
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
                predicates.add(cb.equal(root.get("projectSlug"), currentProject));

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
    public ResponseEntity<PipelineRun> getRun(@PathVariable String runId) {
        try {
            UUID id = UUID.fromString(runId);
            // Use the EntityGraph variant so all three collections are loaded
            // eagerly in a single batch rather than lazily on serialization.
            return pipelineRunRepository.findWithCollectionsById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
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
        Map<String, Object> output = (Map<String, Object>) request.getOrDefault("output", new HashMap<>());

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

            webSocketApprovalGate.resolveApproval(blockId, result);
            auditService.record("APPROVAL_RESOLVE", "run", runId, Map.of(
                "blockId", blockId,
                "decision", decision,
                "skipFuture", skipFuture));
            return ResponseEntity.ok(Map.of("success", true, "blockId", blockId, "decision", decision));

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
            case "none" -> List.of();
            default -> List.of(
                Map.of("name", "requirement", "label", "Requirement", "type", "textarea", "required", true));
        };
    }

    @GetMapping("/pipelines")
    public ResponseEntity<List<Map<String, Object>>> listPipelines() {
        Path dir = Paths.get(configDir);
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
}
