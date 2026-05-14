package com.workflow.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared queries over {@link ToolCallAudit} used by both {@code OrchestratorBlock}
 * (review-mode enrichment) and {@code AgentWithToolsBlock} (loopback context for
 * codegen retries). Kept static so callers don't need to wire it as a Spring bean.
 */
public final class AuditUtils {

    private AuditUtils() {}

    /**
     * Returns the ordered list of file paths the target block touched (via Write/Edit)
     * during its most recent invocation in this run. "Most recent invocation" is
     * detected by walking audit records backward and taking everything from the last
     * {@code iteration=1} marker to the tail — that boundary is how
     * {@code AgentWithToolsBlock} stamps a fresh tool-use loop.
     *
     * <p>Returns empty list (never null) on any failure or when audit is disabled.
     */
    public static List<String> findFilesChangedByLastInvocation(
            ToolCallAuditRepository auditRepository,
            ObjectMapper objectMapper,
            UUID runId,
            String targetBlockId) {
        if (auditRepository == null || runId == null || targetBlockId == null) return List.of();
        List<ToolCallAudit> records;
        try {
            records = auditRepository.findByRunIdAndBlockIdOrderByTimestampAsc(runId, targetBlockId);
        } catch (Exception e) {
            return List.of();
        }
        if (records == null || records.isEmpty()) return List.of();
        int start = 0;
        for (int i = records.size() - 1; i >= 0; i--) {
            Integer it = records.get(i).getIteration();
            if (it != null && it == 1) { start = i; break; }
        }
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (int i = start; i < records.size(); i++) {
            ToolCallAudit r = records.get(i);
            String tool = r.getToolName();
            if (!"Write".equals(tool) && !"Edit".equals(tool)) continue;
            String path = extractFilePathFromToolInput(objectMapper, r.getInputJson());
            if (path != null && !path.isBlank()) files.add(path);
        }
        return new ArrayList<>(files);
    }

    /** Pulls {@code file_path} (Claude Code convention) from a tool-call inputJson. */
    public static String extractFilePathFromToolInput(ObjectMapper objectMapper, String inputJson) {
        if (inputJson == null || inputJson.isBlank()) return null;
        try {
            Map<String, Object> parsed = objectMapper.readValue(inputJson,
                new TypeReference<Map<String, Object>>() {});
            for (String key : List.of("file_path", "path", "filename")) {
                Object v = parsed.get(key);
                if (v instanceof String s && !s.isBlank()) return s;
            }
        } catch (Exception ignore) {}
        return null;
    }
}
