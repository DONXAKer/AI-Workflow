package com.workflow.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

@Component
@ConditionalOnProperty(name = "workflow.mode", havingValue = "cli", matchIfMissing = true)
public class CliApprovalGate implements ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(CliApprovalGate.class);

    @Autowired
    private ObjectMapper objectMapper;

    private ObjectMapper yamlWriter() {
        YAMLFactory factory = YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .build();
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.findAndRegisterModules();
        return mapper;
    }

    @Override
    public ApprovalResult request(String blockId, String blockType, String description,
                                  Map<String, Object> inputData, Map<String, Object> outputData,
                                  List<String> remainingBlockIds) {
        ObjectMapper yaml = yamlWriter();
        Scanner scanner = new Scanner(System.in);

        System.out.println("\n" + "=".repeat(70));
        System.out.println("APPROVAL REQUIRED");
        System.out.println("Block:       " + blockId);
        System.out.println("Type:        " + blockType);
        System.out.println("Description: " + description);
        System.out.println("=".repeat(70));

        System.out.println("\n--- INPUT DATA ---");
        try {
            System.out.println(yaml.writeValueAsString(inputData));
        } catch (Exception e) {
            System.out.println(inputData.toString());
        }

        System.out.println("\n--- OUTPUT DATA ---");
        try {
            System.out.println(yaml.writeValueAsString(outputData));
        } catch (Exception e) {
            System.out.println(outputData.toString());
        }

        while (true) {
            System.out.println("\n[A]pprove  [E]dit  [R]eject  [S]kip future approvals  [J]ump to block");
            System.out.print("Choice: ");
            System.out.flush();

            String choice = "";
            if (scanner.hasNextLine()) {
                choice = scanner.nextLine().trim().toUpperCase();
            }

            switch (choice) {
                case "A" -> {
                    return ApprovalResult.builder()
                        .status("APPROVED")
                        .output(outputData)
                        .skipFuture(false)
                        .build();
                }
                case "E" -> {
                    Map<String, Object> editedOutput = editOutput(outputData, scanner);
                    return ApprovalResult.builder()
                        .status("EDITED")
                        .output(editedOutput)
                        .skipFuture(false)
                        .build();
                }
                case "R" -> {
                    System.out.println("Pipeline rejected by user.");
                    throw new PipelineRejectedException("Rejected by user at block: " + blockId);
                }
                case "S" -> {
                    System.out.println("Approving and skipping future approvals.");
                    return ApprovalResult.builder()
                        .status("APPROVED")
                        .output(outputData)
                        .skipFuture(true)
                        .build();
                }
                case "J" -> {
                    handleJump(blockId, remainingBlockIds, outputData, scanner);
                    // handleJump throws JumpToBlockException - this line is unreachable
                    return ApprovalResult.builder().status("APPROVED").output(outputData).build();
                }
                default -> System.out.println("Invalid choice. Please enter A, E, R, S, or J.");
            }
        }
    }

    private Map<String, Object> editOutput(Map<String, Object> outputData, Scanner scanner) {
        File tempFile = null;
        try {
            tempFile = Files.createTempFile("workflow-edit-", ".json").toFile();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile, outputData);

            String editor = System.getenv("EDITOR");
            if (editor == null || editor.isBlank()) {
                editor = "vi";
            }

            ProcessBuilder pb = new ProcessBuilder(editor, tempFile.getAbsolutePath());
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                System.out.println("Editor exited with code " + exitCode + ", using original output.");
                return outputData;
            }

            String content = Files.readString(tempFile.toPath());
            @SuppressWarnings("unchecked")
            Map<String, Object> edited = objectMapper.readValue(content, Map.class);
            return edited;

        } catch (Exception e) {
            log.warn("Error during edit: {}", e.getMessage());
            System.out.println("Edit failed: " + e.getMessage() + ". Using original output.");
            return outputData;
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    private void handleJump(String currentBlockId, List<String> remainingBlockIds,
                             Map<String, Object> currentOutput, Scanner scanner) {
        if (remainingBlockIds.isEmpty()) {
            System.out.println("No remaining blocks to jump to.");
            return;
        }

        System.out.println("\nRemaining blocks:");
        for (int i = 0; i < remainingBlockIds.size(); i++) {
            System.out.printf("  %d. %s%n", i + 1, remainingBlockIds.get(i));
        }

        System.out.print("Enter block number or name to jump to: ");
        System.out.flush();

        String input = "";
        if (scanner.hasNextLine()) {
            input = scanner.nextLine().trim();
        }

        String targetBlockId = null;
        try {
            int index = Integer.parseInt(input) - 1;
            if (index >= 0 && index < remainingBlockIds.size()) {
                targetBlockId = remainingBlockIds.get(index);
            } else {
                System.out.println("Invalid block number.");
                return;
            }
        } catch (NumberFormatException e) {
            if (remainingBlockIds.contains(input)) {
                targetBlockId = input;
            } else {
                System.out.println("Block '" + input + "' not found in remaining blocks.");
                return;
            }
        }

        // Determine skipped blocks (blocks between current and target)
        Map<String, Map<String, Object>> injectedOutputs = new HashMap<>();

        // Ask if user wants to inject data for skipped blocks
        int targetIndex = remainingBlockIds.indexOf(targetBlockId);
        List<String> skippedBlocks = remainingBlockIds.subList(0, targetIndex);

        if (!skippedBlocks.isEmpty()) {
            System.out.print("Inject data for skipped blocks? [y/N]: ");
            System.out.flush();
            String yesNo = "";
            if (scanner.hasNextLine()) {
                yesNo = scanner.nextLine().trim().toLowerCase();
            }

            if ("y".equals(yesNo) || "yes".equals(yesNo)) {
                for (String skippedBlock : skippedBlocks) {
                    System.out.printf("Enter JSON for block '%s' (or press Enter for empty): ", skippedBlock);
                    System.out.flush();
                    String json = "";
                    if (scanner.hasNextLine()) {
                        json = scanner.nextLine().trim();
                    }
                    if (!json.isBlank()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> blockData = objectMapper.readValue(json, Map.class);
                            injectedOutputs.put(skippedBlock, blockData);
                        } catch (IOException e) {
                            System.out.println("Invalid JSON for block '" + skippedBlock + "', using empty.");
                            injectedOutputs.put(skippedBlock, new HashMap<>());
                        }
                    } else {
                        injectedOutputs.put(skippedBlock, new HashMap<>());
                    }
                }
            }
        }

        throw new JumpToBlockException(targetBlockId, injectedOutputs);
    }
}
