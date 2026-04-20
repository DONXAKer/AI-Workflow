package com.workflow.cli;

import com.workflow.config.BlockConfig;
import com.workflow.config.PipelineConfig;
import com.workflow.config.PipelineConfigLoader;
import com.workflow.core.PipelineRun;
import com.workflow.core.PipelineRunRepository;
import com.workflow.core.PipelineRunner;
import com.workflow.core.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@ShellComponent
public class WorkflowCommands {

    private static final Logger log = LoggerFactory.getLogger(WorkflowCommands.class);
    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // ANSI codes
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String CYAN = "\u001B[36m";

    @Autowired
    private PipelineRunner pipelineRunner;

    @Autowired
    private PipelineRunRepository pipelineRunRepository;

    @Autowired
    private PipelineConfigLoader pipelineConfigLoader;

    @ShellMethod(value = "Run a pipeline", key = "run")
    public String run(
        @ShellOption(help = "Path to pipeline YAML config") String configPath,
        @ShellOption(defaultValue = "", help = "Requirement or task description") String requirement,
        @ShellOption(defaultValue = "", help = "YouTrack issue ID to link") String youtrackIssue,
        @ShellOption(defaultValue = "", help = "Start from this block ID") String fromBlock,
        @ShellOption(defaultValue = "", help = "Existing run ID to resume") String runId
    ) {
        try {
            PipelineConfig config = pipelineConfigLoader.load(Paths.get(configPath));

            // Prompt for requirement if not provided
            String effectiveRequirement = requirement;
            if (effectiveRequirement.isBlank() && youtrackIssue.isBlank()) {
                System.out.print("Enter requirement: ");
                System.out.flush();
                if (System.console() != null) {
                    effectiveRequirement = System.console().readLine();
                } else {
                    java.util.Scanner scanner = new java.util.Scanner(System.in);
                    if (scanner.hasNextLine()) {
                        effectiveRequirement = scanner.nextLine();
                    }
                }
            }

            // If YouTrack issue provided, inject it into config
            if (!youtrackIssue.isBlank()) {
                injectYouTrackIssue(config, youtrackIssue);
                if (effectiveRequirement.isBlank()) {
                    effectiveRequirement = "Process YouTrack issue: " + youtrackIssue;
                }
            }

            UUID runUuid = runId.isBlank() ? UUID.randomUUID() : UUID.fromString(runId);

            System.out.println(BOLD + CYAN + "\nStarting pipeline: " + config.getName() + RESET);
            System.out.println("Run ID: " + runUuid);
            System.out.println("Requirement: " + effectiveRequirement);
            System.out.println();

            CompletableFuture<Void> future;
            if (!fromBlock.isBlank()) {
                future = pipelineRunner.runFrom(config, effectiveRequirement, fromBlock, null, runUuid);
            } else {
                future = pipelineRunner.run(config, effectiveRequirement, runUuid);
            }

            // Wait for completion (blocking in CLI mode)
            future.get();

            return GREEN + BOLD + "\nPipeline completed successfully." + RESET + "\nRun ID: " + runUuid;

        } catch (Exception e) {
            log.error("Run failed: {}", e.getMessage(), e);
            return RED + "Pipeline failed: " + e.getMessage() + RESET;
        }
    }

    @ShellMethod(value = "Resume a paused run", key = "resume")
    public String resume(
        @ShellOption(help = "Path to pipeline YAML config") String configPath,
        @ShellOption(help = "Run ID to resume") String runId
    ) {
        try {
            PipelineConfig config = pipelineConfigLoader.load(Paths.get(configPath));
            PipelineRun run = pipelineRunner.resume(config, runId);

            return CYAN + "Resuming run " + runId + " (status: " + run.getStatus() + ")" + RESET;

        } catch (Exception e) {
            log.error("Resume failed: {}", e.getMessage(), e);
            return RED + "Resume failed: " + e.getMessage() + RESET;
        }
    }

    @ShellMethod(value = "Show run status", key = "status")
    public String status(
        @ShellOption(defaultValue = "", help = "Path to pipeline YAML config") String configPath,
        @ShellOption(help = "Run ID to check") String runId
    ) {
        try {
            UUID uuid = UUID.fromString(runId);
            return pipelineRunRepository.findById(uuid)
                .map(this::formatRunStatus)
                .orElse(RED + "Run not found: " + runId + RESET);
        } catch (IllegalArgumentException e) {
            return RED + "Invalid run ID: " + runId + RESET;
        }
    }

    @ShellMethod(value = "List all runs for a pipeline", key = "runs")
    public String runs(
        @ShellOption(help = "Path to pipeline YAML config") String configPath
    ) {
        try {
            PipelineConfig config = pipelineConfigLoader.load(Paths.get(configPath));
            List<PipelineRun> allRuns = pipelineRunRepository
                .findByPipelineNameOrderByStartedAtDesc(config.getName());

            if (allRuns.isEmpty()) {
                return YELLOW + "No runs found for pipeline: " + config.getName() + RESET;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(BOLD).append(CYAN)
              .append(String.format("%-36s  %-20s  %-10s  %-20s%n",
                  "RUN ID", "PIPELINE", "STATUS", "STARTED AT"))
              .append(RESET);
            sb.append("-".repeat(90)).append("\n");

            for (PipelineRun run : allRuns) {
                String statusColor = statusColor(run.getStatus());
                sb.append(String.format("%-36s  %-20s  %s%-10s%s  %-20s%n",
                    run.getId(),
                    truncate(run.getPipelineName(), 20),
                    statusColor,
                    run.getStatus(),
                    RESET,
                    run.getStartedAt() != null ? DATETIME_FMT.format(run.getStartedAt()) : "N/A"
                ));
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("Runs listing failed: {}", e.getMessage(), e);
            return RED + "Failed to list runs: " + e.getMessage() + RESET;
        }
    }

    private void injectYouTrackIssue(PipelineConfig config, String youtrackIssue) {
        // Find or create a youtrack_input block config entry
        for (BlockConfig blockConfig : config.getPipeline()) {
            if ("youtrack_input".equals(blockConfig.getBlock()) ||
                blockConfig.getId() != null && blockConfig.getId().contains("youtrack")) {
                blockConfig.getConfig().put("issue_id", youtrackIssue);
                return;
            }
        }

        // No specific block found - inject into the first block's config
        if (!config.getPipeline().isEmpty()) {
            config.getPipeline().get(0).getConfig().put("youtrack_issue", youtrackIssue);
        }
    }

    private String formatRunStatus(PipelineRun run) {
        StringBuilder sb = new StringBuilder();
        sb.append(BOLD).append("Run Status").append(RESET).append("\n");
        sb.append("=".repeat(50)).append("\n");
        sb.append(String.format("  ID:       %s%n", run.getId()));
        sb.append(String.format("  Pipeline: %s%n", run.getPipelineName()));
        sb.append(String.format("  Status:   %s%s%s%n", statusColor(run.getStatus()), run.getStatus(), RESET));
        sb.append(String.format("  Started:  %s%n",
            run.getStartedAt() != null ? DATETIME_FMT.format(run.getStartedAt()) : "N/A"));

        if (run.getCurrentBlock() != null) {
            sb.append(String.format("  Current:  %s%n", run.getCurrentBlock()));
        }

        if (run.getError() != null) {
            sb.append(String.format("  Error:    %s%s%s%n", RED, run.getError(), RESET));
        }

        if (run.getCompletedBlocks() != null && !run.getCompletedBlocks().isEmpty()) {
            sb.append("\n").append(BOLD).append("Completed Blocks:").append(RESET).append("\n");
            for (String block : run.getCompletedBlocks()) {
                sb.append("  ").append(GREEN).append("✓").append(RESET).append(" ").append(block).append("\n");
            }
        }

        return sb.toString();
    }

    private String statusColor(RunStatus status) {
        if (status == null) return RESET;
        return switch (status) {
            case COMPLETED -> GREEN;
            case RUNNING -> CYAN;
            case PAUSED_FOR_APPROVAL -> YELLOW;
            case FAILED -> RED;
            default -> RESET;
        };
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
    }
}
