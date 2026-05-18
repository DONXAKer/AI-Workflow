package com.workflow.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Detects "stuck" loopback chains where each iteration reports the same issues —
 * the LLM is going in circles and {@code max_iterations} will only delay the
 * inevitable failure while burning tokens. When detected, callers should force-trigger
 * the escalation ladder (cloud → human) instead of running more local-tier iterations.
 *
 * <p>Algorithm: Jaccard similarity over normalized issue strings against the most
 * recent previous iteration for the same {@code (fromBlock, targetBlock)} loop key.
 * Default threshold is 0.8 — empirically distinguishes "same problems re-discovered"
 * from "new problems found" without misfiring on minor wording drift between iterations.
 */
@Service
public class NoProgressDetector {

    private static final Logger log = LoggerFactory.getLogger(NoProgressDetector.class);

    /** Token cap per normalized issue string — keeps the comparison space bounded. */
    static final int MAX_ISSUE_LENGTH = 200;

    /**
     * Returns {@code true} when {@code currentIssues} is at least {@code threshold}
     * similar (Jaccard) to the most recent prior iteration. False on the first iteration
     * (nothing to compare), on empty current issues, and when no prior issues exist
     * for the same loop key.
     */
    public boolean isStuck(List<List<String>> priorIterationIssues, List<String> currentIssues, double threshold) {
        if (priorIterationIssues == null || priorIterationIssues.isEmpty()) return false;
        if (currentIssues == null || currentIssues.isEmpty()) return false;
        List<String> last = priorIterationIssues.get(priorIterationIssues.size() - 1);
        if (last == null || last.isEmpty()) return false;
        double sim = jaccard(normalize(last), normalize(currentIssues));
        log.debug("NoProgress similarity {} (threshold {})", sim, threshold);
        return sim >= threshold;
    }

    /**
     * Extracts the issues list from every prior loopback history entry that matches
     * the given {@code (fromBlock, targetBlock)} pair. Returns an empty list when the
     * JSON is empty / malformed — callers treat that as "no prior data, can't be stuck".
     */
    public List<List<String>> extractPriorIssues(String loopHistoryJson, String fromBlockId,
                                                  String targetBlockId, ObjectMapper objectMapper) {
        if (loopHistoryJson == null || loopHistoryJson.isBlank() || "[]".equals(loopHistoryJson)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> history;
        try {
            history = objectMapper.readValue(loopHistoryJson,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.debug("Loop history JSON unreadable, skipping no-progress check: {}", e.getMessage());
            return Collections.emptyList();
        }
        List<List<String>> result = new ArrayList<>();
        for (Map<String, Object> entry : history) {
            if (entry == null) continue;
            if (!Objects.equals(entry.get("from_block"), fromBlockId)) continue;
            if (!Objects.equals(entry.get("to_block"), targetBlockId)) continue;
            Object rawIssues = entry.get("issues");
            if (!(rawIssues instanceof List<?> list)) continue;
            List<String> strs = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) strs.add(o.toString());
            }
            result.add(strs);
        }
        return result;
    }

    static Set<String> normalize(List<String> issues) {
        Set<String> normalized = new HashSet<>(issues.size());
        for (String s : issues) {
            if (s == null) continue;
            String n = s.toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (n.isEmpty()) continue;
            if (n.length() > MAX_ISSUE_LENGTH) n = n.substring(0, MAX_ISSUE_LENGTH);
            normalized.add(n);
        }
        return normalized;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        if (intersection.isEmpty()) return 0.0;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
