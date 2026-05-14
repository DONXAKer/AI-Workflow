package com.workflow.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight clarity assessment of a raw requirement, intended to run between
 * {@code intake} and {@code analysis}. Sits on a cheap-tier model (default
 * {@code fast}) so the cost stays trivial — the whole point is to decide
 * <em>before</em> spending a smart-tier {@code analysis} call whether the task
 * is trivial enough to skip heavy planning or vague enough to require
 * clarification first.
 *
 * <p>Output:
 * <pre>
 * {
 *   "clarity_pct": 0..100,
 *   "clarity_breakdown": [
 *     {"criterion": "acceptance_criteria_explicit", "passed": true, "evidence": "..."},
 *     {"criterion": "scope_clear",                  "passed": true, "evidence": "..."},
 *     {"criterion": "edge_cases_listed",            "passed": false, "evidence": "..."},
 *     {"criterion": "dod_measurable",               "passed": true, "evidence": "..."},
 *     {"criterion": "perf_security_considered",     "passed": false, "evidence": "..."}
 *   ],
 *   "recommended_path": "clarify" | "full" | "fast",
 *   "rationale": "..."
 * }
 * </pre>
 *
 * <p>Bucket thresholds (deliberate, not configurable): <60% → clarify, 60..85% →
 * full, >85% → fast. The {@code recommended_path} field returned by the LLM is
 * overridden by these thresholds — model gets to set the percentage, the bucket
 * is code so we don't get drift between blocks reading
 * {@code recommended_path}.
 */
@Component
public class IntakeAssessmentBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(IntakeAssessmentBlock.class);

    static final int CLARIFY_THRESHOLD = 60;
    static final int FAST_THRESHOLD = 85;

    static final String PATH_CLARIFY = "clarify";
    static final String PATH_FULL = "full";
    static final String PATH_FAST = "fast";

    private static final String SYSTEM_PROMPT = """
            Ты product engineer, который оценивает понятность бизнес-требования \
            ДО передачи в analysis/clarification. Цель — за один cheap-tier вызов \
            понять: задача очевидна (можно сразу кодить), нормальна (полный flow), \
            или туманна (нужны вопросы).

            Оцени requirement по 5 критериям (каждый булев — passed: true/false):

            1. **acceptance_criteria_explicit** — есть ли в requirement измеримые \
               acceptance criteria (что именно должно работать)?
            2. **scope_clear** — понятно ли, какие модули/файлы/слой затрагиваются \
               (UI/API/data layer, какие фичи)?
            3. **edge_cases_listed** — упомянуты ли граничные случаи (пустой ввод, \
               огромный объём, конкурентность, ошибки)?
            4. **dod_measurable** — definition-of-done выражен в проверяемых терминах \
               (а не "сделать хорошо")?
            5. **perf_security_considered** — упомянуты ли производительность/security \
               implications (если задача в hot path или касается данных)?

            Для каждого критерия выставь passed (true/false) и одно предложение \
            evidence (что в тексте requirement подтверждает решение).

            Затем посчитай clarity_pct = количество пройденных критериев * 20 \
            (0/20/40/60/80/100).

            Верни СТРОГО валидный JSON по схеме:
            {
              "clarity_pct": <int 0..100>,
              "clarity_breakdown": [
                {"criterion": "<name>", "passed": <bool>, "evidence": "<one sentence>"},
                ... (ровно 5 элементов в том же порядке, что критерии выше)
              ],
              "recommended_path": "clarify" | "full" | "fast",
              "rationale": "<2-3 предложения, почему именно этот path>"
            }

            Никаких markdown-обёрток, никаких комментариев, только JSON.
            """;

    private static final String USER_TEMPLATE = """
            Requirement (raw text):

            ---
            {raw_text}
            ---

            Оцени clarity по 5 критериям выше и верни JSON.
            """;

    @Autowired private LlmClient llmClient;
    @Autowired private ObjectMapper objectMapper;

    @Override public String getName() { return "intake_assessment"; }

    @Override public String getDescription() {
        return "Лёгкая оценка clarity требования (5 критериев, cheap-tier). "
                + "Возвращает clarity_pct + recommended_path (clarify/full/fast) для "
                + "skip-логики на последующих блоках.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
                "Intake Assessment",
                "analysis",
                Phase.ANY,
                List.of(),  // no config fields — block is fully parametrized by agent.model/temperature
                false,
                Map.of()
        );
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        String requirement = readRequirement(input, run);
        if (requirement == null || requirement.isBlank()) {
            log.warn("intake_assessment[{}]: empty requirement; defaulting to clarify path", config.getId());
            return defaultResponse(PATH_CLARIFY, 0, "empty requirement");
        }

        String model = "fast";
        int maxTokens = 1024;
        double temperature = 0.3;
        if (config.getAgent() != null) {
            String overrideModel = config.getAgent().getEffectiveModel();
            if (overrideModel != null && !overrideModel.isBlank()) model = overrideModel;
            maxTokens = config.getAgent().getMaxTokensOrDefault();
            Double explicitTemp = config.getAgent().getTemperature();
            if (explicitTemp != null && explicitTemp != 1.0) temperature = explicitTemp;
        }

        String userMessage = USER_TEMPLATE.replace("{raw_text}", requirement);
        String systemPrompt = SYSTEM_PROMPT;
        if (config.getAgent() != null && config.getAgent().getSystemPrompt() != null
                && !config.getAgent().getSystemPrompt().isBlank()) {
            systemPrompt = config.getAgent().getSystemPrompt() + "\n\n" + SYSTEM_PROMPT;
        }

        String response = llmClient.complete(model, systemPrompt, userMessage, maxTokens, temperature);

        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("intake_assessment[{}]: failed to parse LLM JSON, defaulting to full path. Raw: {}",
                    config.getId(), abbreviate(response, 200));
            return defaultResponse(PATH_FULL, 60, "LLM response parse error: " + e.getMessage());
        }

        return finalize(parsed);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String readRequirement(Map<String, Object> input, PipelineRun run) {
        if (input != null) {
            Object v = input.get("requirement");
            if (v != null && !v.toString().isBlank()) return v.toString();
            // business_intake produces a "requirement" key but it may come under a nested
            // intake block output; check both shapes.
            Object intake = input.get("intake");
            if (intake instanceof Map<?, ?> m) {
                Object req = m.get("requirement");
                if (req != null && !req.toString().isBlank()) return req.toString();
            }
        }
        if (run != null && run.getRequirement() != null && !run.getRequirement().isBlank()) {
            return run.getRequirement();
        }
        return null;
    }

    /** Normalize whatever the LLM produced, apply bucket overrides, fill defaults. */
    private Map<String, Object> finalize(Map<String, Object> parsed) {
        int clarityPct = clampPct(parsed.get("clarity_pct"));
        String path = bucketFor(clarityPct);  // code-authoritative — overrides LLM's value

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clarity_pct", clarityPct);
        out.put("clarity_breakdown", normalizeBreakdown(parsed.get("clarity_breakdown")));
        out.put("recommended_path", path);
        Object rationale = parsed.get("rationale");
        out.put("rationale", rationale != null ? rationale.toString() : "");
        return out;
    }

    private Map<String, Object> defaultResponse(String path, int pct, String rationale) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clarity_pct", pct);
        out.put("clarity_breakdown", new ArrayList<>());
        out.put("recommended_path", path);
        out.put("rationale", rationale);
        return out;
    }

    static String bucketFor(int clarityPct) {
        if (clarityPct < CLARIFY_THRESHOLD) return PATH_CLARIFY;
        if (clarityPct > FAST_THRESHOLD) return PATH_FAST;
        return PATH_FULL;
    }

    private static int clampPct(Object raw) {
        int v;
        if (raw instanceof Number n) v = n.intValue();
        else if (raw instanceof String s) {
            try { v = Integer.parseInt(s.trim()); } catch (Exception e) { v = 0; }
        } else v = 0;
        return Math.max(0, Math.min(100, v));
    }

    private List<Map<String, Object>> normalizeBreakdown(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    Object crit = m.get("criterion");
                    Object evi = m.get("evidence");
                    entry.put("criterion", crit != null ? crit.toString() : "");
                    entry.put("passed", m.get("passed") instanceof Boolean b ? b : false);
                    entry.put("evidence", evi != null ? evi.toString() : "");
                    out.add(entry);
                }
            }
        }
        return out;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
