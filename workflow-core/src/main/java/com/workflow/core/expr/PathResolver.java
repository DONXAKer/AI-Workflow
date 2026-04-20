package com.workflow.core.expr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.core.BlockOutput;
import com.workflow.core.PipelineRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves dotted paths of the form {@code block_id.field} or
 * {@code block_id.field.nested.key} against a {@link PipelineRun}'s block outputs.
 *
 * <p>Used as the backing lookup for both {@link StringInterpolator} (YAML string
 * templates like {@code ${impl.file_path}}) and future expression evaluators
 * ({@code $.impl.file_path}). The two syntaxes share this core: a path is a
 * block id followed by a chain of map keys.
 *
 * <p>Missing refs throw {@link PathNotFoundException}. No silent-empty fallback —
 * that's the Phase 1 plan's explicit rule.
 */
@Component
public class PathResolver {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    @Autowired
    public PathResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param path dotted path, e.g. {@code "analysis.estimated_complexity"} or
     *             {@code "impl.tool_calls_made"}. A leading {@code $.} is stripped if
     *             present so callers can pass either form.
     */
    public Object resolve(String path, PipelineRun run) {
        if (path == null || path.isBlank()) {
            throw new PathNotFoundException("path is empty");
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("$.")) trimmed = trimmed.substring(2);

        String[] segments = trimmed.split("\\.");
        if (segments.length < 2) {
            throw new PathNotFoundException(
                "path must be block_id.field[.nested...], got: '" + path + "'");
        }

        String blockId = segments[0];
        Map<String, Object> blockOutput = loadBlockOutput(blockId, run, path);

        Object current = blockOutput;
        for (int i = 1; i < segments.length; i++) {
            String seg = segments[i];
            if (!(current instanceof Map<?, ?> map)) {
                throw new PathNotFoundException(
                    "cannot descend into non-object at segment '" + seg
                        + "' in path '" + path + "' (got: " + typeOf(current) + ")");
            }
            if (!map.containsKey(seg)) {
                throw new PathNotFoundException(
                    "missing key '" + seg + "' in path '" + path
                        + "' — available: " + map.keySet());
            }
            current = map.get(seg);
        }
        return current;
    }

    private Map<String, Object> loadBlockOutput(String blockId, PipelineRun run, String originalPath) {
        List<BlockOutput> outs = run.getOutputs();
        if (outs == null) {
            throw new PathNotFoundException(
                "run has no outputs yet — cannot resolve '" + originalPath + "'");
        }
        Optional<BlockOutput> hit = outs.stream()
            .filter(o -> blockId.equals(o.getBlockId()))
            .findFirst();
        BlockOutput output = hit.orElseThrow(() -> new PathNotFoundException(
            "no output for block '" + blockId + "' — completed blocks: "
                + outs.stream().map(BlockOutput::getBlockId).toList()));
        try {
            return objectMapper.readValue(output.getOutputJson(), MAP_TYPE);
        } catch (Exception e) {
            throw new PathNotFoundException(
                "failed to parse output of block '" + blockId + "': " + e.getMessage());
        }
    }

    private static String typeOf(Object o) {
        if (o == null) return "null";
        return o.getClass().getSimpleName();
    }
}
