package com.workflow.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.BlockConfig;
import com.workflow.config.PipelineConfig;
import com.workflow.config.PipelineConfigLoader;
import com.workflow.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * Handles operator-initiated returns of a run back to a previous block.
 *
 * <p>Flow:
 * <ol>
 *   <li>LLM converts the operator's raw comment into a structured feedback object
 *       ({@code issues}, {@code recommendation}, {@code specific_changes}).</li>
 *   <li>Feedback is stored as {@code _loopback_<target>} block output so the target
 *       block will pick it up as {@code _loopback} input on re-execution.</li>
 *   <li>Completed-block state is reset from the target forward.</li>
 *   <li>Loop history is appended.</li>
 *   <li>The run is resumed from the target via {@link PipelineRunner#resume}.</li>
 * </ol>
 *
 * <p>Only runs in a terminal state (COMPLETED, FAILED) are supported in this
 * iteration — returning a PAUSED_FOR_APPROVAL run requires coordinating with
 * the active approval gate and will be added later.
 */
@Service
public class RunReturnService {

    private static final Logger log = LoggerFactory.getLogger(RunReturnService.class);

    private static final String SYSTEM_PROMPT =
        "Ты — ассистент, который превращает свободный комментарий оператора в структурированный фидбэк " +
        "для блока pipeline. Разбирай проблемы по пунктам, выделяй конкретные изменения. " +
        "Отвечай валидным JSON.";

    private static final String USER_TEMPLATE =
        "## Блок, на который возвращается задача\n\n{target}\n\n" +
        "## Output этого блока (что было сделано)\n\n{output}\n\n" +
        "## Комментарий оператора\n\n{comment}\n\n" +
        "---\n\n" +
        "Преобразуй комментарий в структурированный фидбэк. Ответь ТОЛЬКО JSON:\n\n" +
        "{\n" +
        "  \"issues\": [\"<проблема 1>\", \"<проблема 2>\"],\n" +
        "  \"recommendation\": \"<что конкретно нужно переделать>\",\n" +
        "  \"specific_changes\": [\"<изменение 1>\", \"<...>\"]\n" +
        "}";

    @Autowired
    private PipelineRunRepository runRepository;

    @Autowired
    private BlockOutputRepository blockOutputRepository;

    @Autowired
    private PipelineRunner pipelineRunner;

    @Autowired
    private PipelineConfigLoader pipelineConfigLoader;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ObjectMapper objectMapper;

    public PipelineRun returnToBlock(UUID runId, String configPath, String targetBlockId, String comment)
            throws Exception {
        PipelineRun run = runRepository.findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        RunStatus status = run.getStatus();
        if (status != RunStatus.COMPLETED && status != RunStatus.FAILED) {
            throw new IllegalStateException(
                "Return is only supported from terminal states (COMPLETED, FAILED). Current status: " + status);
        }

        if (!run.getCompletedBlocks().contains(targetBlockId)) {
            throw new IllegalArgumentException(
                "Target block '" + targetBlockId + "' was not completed in this run");
        }

        Path cfgPath = Paths.get(configPath);
        PipelineConfig config = pipelineConfigLoader.load(cfgPath);

        List<BlockConfig> sorted = topologicalSort(config.getPipeline());
        int targetIndex = indexOf(sorted, targetBlockId);
        if (targetIndex < 0) {
            throw new IllegalArgumentException(
                "Target block '" + targetBlockId + "' not found in pipeline config");
        }

        Map<String, Object> targetOutput = readBlockOutput(run, targetBlockId);
        Map<String, Object> feedback = structureFeedback(targetBlockId, targetOutput, comment);

        saveLoopbackOutput(run, targetBlockId, feedback);

        Set<String> toRemove = new HashSet<>();
        for (int i = targetIndex; i < sorted.size(); i++) {
            toRemove.add(sorted.get(i).getId());
        }
        run.getCompletedBlocks().removeAll(toRemove);

        String loopKey = "return:operator:" + targetBlockId;
        int iterations = run.getLoopIterations().getOrDefault(loopKey, 0);
        run.getLoopIterations().put(loopKey, iterations + 1);

        appendHistory(run, targetBlockId, comment, feedback, iterations + 1);

        run.setStatus(RunStatus.RUNNING);
        run.setError(null);
        run.setCompletedAt(null);
        run.setCurrentBlock(targetBlockId);
        runRepository.save(run);

        log.info("Operator return: run={}, target={}, iteration={}", runId, targetBlockId, iterations + 1);
        pipelineRunner.resume(config, runId.toString());
        return run;
    }

    private Map<String, Object> structureFeedback(String targetBlockId, Map<String, Object> targetOutput,
                                                   String comment) {
        String outputStr;
        try {
            outputStr = objectMapper.writeValueAsString(targetOutput);
        } catch (Exception e) {
            outputStr = String.valueOf(targetOutput);
        }

        String userMessage = USER_TEMPLATE
            .replace("{target}", targetBlockId)
            .replace("{output}", outputStr)
            .replace("{comment}", comment != null ? comment : "");

        try {
            String response = llmClient.complete("claude-sonnet-4-6", SYSTEM_PROMPT, userMessage, 2048, 0.3);
            Map<String, Object> parsed = objectMapper.readValue(
                response, new TypeReference<Map<String, Object>>() {});
            parsed.putIfAbsent("issues", new ArrayList<>());
            parsed.putIfAbsent("recommendation", "");
            parsed.putIfAbsent("specific_changes", new ArrayList<>());
            parsed.put("source", "operator_return");
            parsed.put("raw_comment", comment);
            return parsed;
        } catch (Exception e) {
            log.warn("Failed to structure feedback via LLM, falling back to raw: {}", e.getMessage());
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("issues", comment != null ? List.of(comment) : List.of());
            fallback.put("recommendation", comment != null ? comment : "");
            fallback.put("specific_changes", new ArrayList<>());
            fallback.put("source", "operator_return");
            fallback.put("raw_comment", comment);
            fallback.put("_llm_error", e.getMessage());
            return fallback;
        }
    }

    private void saveLoopbackOutput(PipelineRun run, String targetBlockId, Map<String, Object> feedback) {
        try {
            String key = "_loopback_" + targetBlockId;
            String json = objectMapper.writeValueAsString(feedback);
            Optional<BlockOutput> existing = run.getOutputs().stream()
                .filter(o -> o.getBlockId().equals(key)).findFirst();
            if (existing.isPresent()) {
                existing.get().setOutputJson(json);
                blockOutputRepository.save(existing.get());
            } else {
                BlockOutput out = BlockOutput.builder().run(run).blockId(key).outputJson(json).build();
                blockOutputRepository.save(out);
                run.getOutputs().add(out);
            }
        } catch (Exception e) {
            log.error("Failed to save loopback output for {}: {}", targetBlockId, e.getMessage(), e);
            throw new RuntimeException("Failed to persist return feedback", e);
        }
    }

    private void appendHistory(PipelineRun run, String targetBlockId, String comment,
                                Map<String, Object> feedback, int iteration) {
        try {
            String histJson = run.getLoopHistoryJson() != null ? run.getLoopHistoryJson() : "[]";
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = objectMapper.readValue(
                histJson, new TypeReference<List<Map<String, Object>>>() {});
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", Instant.now().toString());
            entry.put("source", "operator_return");
            entry.put("to_block", targetBlockId);
            entry.put("iteration", iteration);
            entry.put("comment", comment);
            entry.put("issues", feedback.get("issues"));
            history.add(entry);
            run.setLoopHistoryJson(objectMapper.writeValueAsString(history));
        } catch (Exception e) {
            log.warn("Failed to append return history: {}", e.getMessage());
        }
    }

    private Map<String, Object> readBlockOutput(PipelineRun run, String blockId) {
        return run.getOutputs().stream()
            .filter(o -> o.getBlockId().equals(blockId))
            .findFirst()
            .map(o -> {
                try {
                    return objectMapper.<Map<String, Object>>readValue(
                        o.getOutputJson(), new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    return new HashMap<String, Object>();
                }
            })
            .orElseGet(HashMap::new);
    }

    private int indexOf(List<BlockConfig> blocks, String id) {
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).getId().equals(id)) return i;
        }
        return -1;
    }

    private List<BlockConfig> topologicalSort(List<BlockConfig> blocks) {
        Map<String, BlockConfig> map = new LinkedHashMap<>();
        for (BlockConfig b : blocks) map.put(b.getId(), b);

        List<BlockConfig> sorted = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String id : map.keySet()) {
            if (!visited.contains(id)) dfs(id, map, visited, inStack, sorted);
        }
        return sorted;
    }

    private void dfs(String id, Map<String, BlockConfig> map, Set<String> visited,
                     Set<String> inStack, List<BlockConfig> out) {
        if (inStack.contains(id)) throw new IllegalStateException("Cycle: " + id);
        if (visited.contains(id)) return;
        inStack.add(id);
        BlockConfig b = map.get(id);
        if (b != null && b.getDependsOn() != null) {
            for (String dep : b.getDependsOn()) dfs(dep, map, visited, inStack, out);
        }
        inStack.remove(id);
        visited.add(id);
        if (b != null) out.add(b);
    }
}
