package com.workflow.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.core.BlockOutput;
import com.workflow.core.BlockOutputRepository;
import com.workflow.core.PipelineRun;
import com.workflow.core.PipelineRunRepository;
import com.workflow.core.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Mines past successful block outputs to seed few-shot examples into new LLM calls.
 *
 * <p>Success criterion (lenient MVP): the run completed successfully and the target
 * block's output was neither edited nor rejected. When the history is empty or too
 * small the retriever returns nothing and the LLM call proceeds unchanged.
 *
 * <p>Richer signals (approved without edit, high verify score, no downstream loopback)
 * will land with more sophisticated run metadata.
 */
@Service
public class ExampleRetriever {

    private static final Logger log = LoggerFactory.getLogger(ExampleRetriever.class);

    @Autowired
    private PipelineRunRepository runRepository;

    @Autowired
    private BlockOutputRepository blockOutputRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Returns up to {@code limit} example input/output pairs for the given block ID
     * across recent COMPLETED runs of the same pipeline.
     */
    public List<Example> retrieve(String pipelineName, String blockId, int limit) {
        List<Example> out = new ArrayList<>();
        if (limit <= 0) return out;

        List<PipelineRun> runs = runRepository.findByPipelineNameOrderByStartedAtDesc(pipelineName);
        for (PipelineRun run : runs) {
            if (out.size() >= limit) break;
            if (run.getStatus() != RunStatus.COMPLETED) continue;
            if (!run.getCompletedBlocks().contains(blockId)) continue;

            Optional<BlockOutput> match = blockOutputRepository.findByRunId(run.getId()).stream()
                .filter(o -> o.getBlockId().equals(blockId))
                .findFirst();
            if (match.isEmpty()) continue;
            try {
                Map<String, Object> output = objectMapper.readValue(
                    match.get().getOutputJson(), new TypeReference<Map<String, Object>>() {});
                if (Boolean.TRUE.equals(output.get("_skipped"))) continue;
                out.add(new Example(run.getRequirement(), output));
            } catch (Exception e) {
                log.debug("Skipping unparseable output for run {}/{}: {}", run.getId(), blockId, e.getMessage());
            }
        }
        return out;
    }

    /**
     * Formats examples as a prompt snippet ready to append to a system or user message.
     * Returns an empty string when no examples are available so callers can concatenate
     * unconditionally.
     */
    public String format(List<Example> examples) {
        if (examples == null || examples.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n\n## Примеры успешных выполнений\n");
        int i = 1;
        for (Example ex : examples) {
            sb.append("\n### Пример ").append(i++).append("\n");
            if (ex.requirement() != null && !ex.requirement().isBlank()) {
                sb.append("**Вход:** ").append(truncate(ex.requirement(), 400)).append("\n\n");
            }
            try {
                String outJson = objectMapper.writeValueAsString(ex.output());
                sb.append("**Выход:**\n```json\n").append(truncate(outJson, 1200)).append("\n```\n");
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    public record Example(String requirement, Map<String, Object> output) {}
}
