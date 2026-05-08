package com.workflow.core.expr;

import com.workflow.core.PipelineRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code ${block_id.field}} placeholders in YAML string values using
 * {@link PathResolver}. Lists and maps are rendered via {@code toString()} — callers
 * that need JSON-encoded values should resolve the path directly.
 *
 * <p>A second form is supported: {@code ${input.key}} where {@code input} is a reserved
 * prefix meaning "the block's input map" rather than another block's output. This lets
 * a block template reference its own injected inputs without juggling two interpolator
 * APIs.
 *
 * <p>Missing paths bubble up as {@link PathNotFoundException} — no silent-empty.
 */
@Component
public class StringInterpolator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final String INPUT_PREFIX = "input.";

    private final PathResolver pathResolver;

    @Autowired(required = false)
    private PromptContextExecutor promptContextExecutor;

    @Autowired
    public StringInterpolator(PathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    /**
     * Interpolate against block outputs only. Use when there is no per-block input map
     * in scope.
     */
    public String interpolate(String template, PipelineRun run) {
        return interpolate(template, run, Map.of());
    }

    /**
     * Expand {@code ${sh: command}} placeholders by running each command in
     * {@code workingDir}, then resolve {@code ${block.field}} and {@code ${input.key}}
     * in the resulting string.
     *
     * <p>{@code extraAllow} is merged with the global allowlist from
     * {@link com.workflow.config.PromptContextConfig}. Pass {@code null} or empty to
     * use only the global list.
     */
    public String interpolate(String template, PipelineRun run, Map<String, Object> input,
                              Path workingDir, List<String> extraAllow) {
        if (template == null) return null;
        String expanded = promptContextExecutor != null
            ? promptContextExecutor.expand(template, workingDir, extraAllow)
            : template;
        return interpolate(expanded, run, input);
    }

    /**
     * Interpolate against block outputs with an additional {@code input.*} namespace
     * for the current block's input map.
     */
    public String interpolate(String template, PipelineRun run, Map<String, Object> input) {
        if (template == null) return null;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1).trim();
            Object value = resolveRef(ref, run, input);
            m.appendReplacement(sb, Matcher.quoteReplacement(stringify(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Object resolveRef(String ref, PipelineRun run, Map<String, Object> input) {
        if (ref.startsWith(INPUT_PREFIX)) {
            String key = ref.substring(INPUT_PREFIX.length());
            return resolveInputPath(key, input, ref);
        }
        return pathResolver.resolve(ref, run);
    }

    @SuppressWarnings("unchecked")
    private Object resolveInputPath(String key, Map<String, Object> input, String originalRef) {
        if (input == null || input.isEmpty()) {
            throw new PathNotFoundException(
                "input map is empty — cannot resolve '${" + originalRef + "}'");
        }
        String[] parts = key.split("\\.");
        Object current = input;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) {
                throw new PathNotFoundException(
                    "cannot descend into non-object at '" + part
                        + "' in '${" + originalRef + "}'");
            }
            if (!map.containsKey(part)) {
                throw new PathNotFoundException(
                    "missing input key '" + part + "' in '${" + originalRef
                        + "}' — available: " + map.keySet());
            }
            current = map.get(part);
        }
        return current;
    }

    private static String stringify(Object value) {
        return value == null ? "" : value.toString();
    }
}
