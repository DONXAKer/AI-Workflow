package com.workflow.core;

import com.workflow.blocks.Block;
import com.workflow.config.BlockConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only block registered via component scan when the {@code com.workflow.core}
 * test source set is on the classpath. Not bundled into {@code bootJar} because it
 * lives under {@code src/test} — main classpath is unaffected.
 *
 * <p>Deterministic output driven by whether the runner injected {@code _loopback} on
 * re-entry: first call returns {@code {"value": "bad"}}; any call with a non-empty
 * {@code _loopback} map in the input returns {@code {"value": "good", "retry": true}}.
 * The companion verify block in {@link PipelineRunnerLoopbackIT} checks
 * {@code value == "good"}, so the first pass fails, triggers loopback, and the second
 * pass succeeds — exercising the full
 * {@link PipelineRunner#handleLoopback} path end-to-end.
 */
@Component
public class LoopbackProducerBlock implements Block {

    @Override public String getName() { return "loopback_producer"; }

    @Override public String getDescription() {
        return "Test-only block: returns 'bad' on first call, 'good' when _loopback is present";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object loopback = input.get("_loopback");
        boolean retry = loopback instanceof Map<?, ?> m && !m.isEmpty();
        out.put("value", retry ? "good" : "bad");
        out.put("retry", retry);
        if (retry) {
            @SuppressWarnings("unchecked")
            Map<String, Object> loopbackMap = (Map<String, Object>) loopback;
            out.put("received_loopback_iteration", loopbackMap.get("iteration"));
            Object issues = loopbackMap.get("issues");
            out.put("received_loopback_issues",
                issues instanceof List<?> l ? l : List.of());
        }
        return out;
    }
}
