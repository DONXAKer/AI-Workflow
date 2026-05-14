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
 * Planner role for the tester. Reads the {@code analysis} output (acceptance
 * checklist + affected components + complexity) and {@code context_scan} output
 * (tech stack + conventions) and produces a structured test plan:
 *
 * <pre>
 * {
 *   "strategy": "tdd" | "adaptive" | "none",
 *   "cases": [
 *     {"id": "tc-1", "type": "unit", "target": "UserService.register",
 *      "scenario": "valid email + password", "boundary_origin": null,
 *      "priority": "critical"},
 *     {"id": "tc-2", "type": "unit", "target": "UserService.register",
 *      "scenario": "email exactly 254 chars (RFC 5321 boundary)",
 *      "boundary_origin": "email.length=254", "priority": "critical"},
 *     ...
 *   ],
 *   "coverage_estimate": 0.0..1.0,
 *   "notes": "..."
 * }
 * </pre>
 *
 * <p>Strategy semantics:
 * <ul>
 *   <li>{@code tdd} — behavioral feature/bugfix. Tests written FIRST, must fail
 *       before codegen, must pass after. Downstream pipeline can include a
 *       {@code test_must_fail} guard (deferred to a future PR).</li>
 *   <li>{@code adaptive} — default. Tests written alongside codegen, no
 *       strict order. Most refactor + small changes land here.</li>
 *   <li>{@code none} — pure rename / style fix / no behavioral change. No
 *       test_gen needed.</li>
 * </ul>
 *
 * <p>BVA (Boundary Value Analysis) is enforced via the prompt: every numeric
 * or string-length parameter must produce dedicated boundary test cases with
 * {@code boundary_origin} pointing to the parameter. {@code agent_verify}
 * downstream can then check that boundary cases exist for each parameter in
 * the analysis output.
 */
@Component
public class TestPlanningBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(TestPlanningBlock.class);

    private static final String SYSTEM_PROMPT = """
            Ты senior QA / test architect. Тебе даны analysis output \
            (acceptance criteria, affected_components, complexity) и context_scan \
            (tech stack, conventions). Составь test plan для предстоящего codegen.

            Шаги:

            1. Определи **strategy**:
               - `tdd` — задача меняет поведение (новая фича, баг-фикс). Тесты пишутся \
                 ДО codegen, должны падать до codegen и проходить после.
               - `adaptive` — рефакторинг / style fix / структурное изменение без \
                 behavioural delta. Тесты — параллельно с codegen.
               - `none` — pure rename / typo / docs. Тесты не нужны.

            2. Для каждого пункта acceptance_checklist составь test cases:
               - **type**: unit | integration | e2e | visual
               - **target**: "<Class.method>" или endpoint
               - **scenario**: одна строка "<что делает тест>"
               - **boundary_origin**: null для smoke; "<param>.length=N" / "<param>=<value>" \
                 для BVA-derived
               - **priority**: critical | important | nice_to_have

            3. **BVA (Boundary Value Analysis) обязательно**: для каждого числового или \
               строкового параметра, упомянутого в analysis, создавай отдельные test cases \
               для min-1 / min / min+1 / max-1 / max / max+1 / null / "" / RFC-relevant boundary. \
               Заполняй boundary_origin ссылкой на параметр и его границу.

            4. **coverage_estimate**: 0.0..1.0 — твоя оценка, насколько эти cases покрывают \
               acceptance criteria. 0.9+ только если каждый acceptance item имеет ≥1 case.

            Верни СТРОГО валидный JSON (без markdown, без комментариев):
            {
              "strategy": "tdd" | "adaptive" | "none",
              "cases": [
                {"id": "tc-<n>", "type": "unit|integration|e2e|visual",
                 "target": "<string>", "scenario": "<string>",
                 "boundary_origin": "<string or null>", "priority": "critical|important|nice_to_have"},
                ...
              ],
              "coverage_estimate": <float>,
              "notes": "<2-3 sentence summary of approach>"
            }

            Правила:
            - cases.id: "tc-1", "tc-2", ... в порядке появления, без пропусков.
            - Если strategy=none — cases = [], coverage_estimate = 1.0.
            - Если context_scan.tech_stack.framework говорит о frontend — добавь хотя бы \
              один visual case на главный flow (но только если acceptance касается UI).
            - НЕ выдумывай acceptance items, которых нет в analysis. Если analysis пустой, \
              верни strategy=adaptive + cases=[] + coverage_estimate=0.
            """;

    @Autowired private LlmClient llmClient;
    @Autowired private ObjectMapper objectMapper;

    @Override public String getName() { return "test_planning"; }

    @Override public String getDescription() {
        return "Составляет structured test plan по analysis.acceptance_checklist + context_scan. "
                + "Strategy: tdd/adaptive/none. BVA обязательна для числовых/строковых параметров.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
                "Test Planning",
                "analysis",
                Phase.ANY,
                List.of(),
                false,
                Map.of()
        );
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig blockConfig, PipelineRun run) throws Exception {
        // Collect upstream context from input (PipelineRunner already injects prior block outputs).
        String analysisSummary = summarizeAnalysis(input);
        String contextSummary = summarizeContextScan(input);

        String userMessage = """
                ## Analysis output

                %s

                ## Context scan output

                %s

                Составь test plan по схеме выше.
                """.formatted(analysisSummary, contextSummary);

        String model = "smart";
        int maxTokens = 4096;
        double temperature = 0.4;
        if (blockConfig.getAgent() != null) {
            String overrideModel = blockConfig.getAgent().getEffectiveModel();
            if (overrideModel != null && !overrideModel.isBlank()) model = overrideModel;
            maxTokens = blockConfig.getAgent().getMaxTokensOrDefault();
            Double explicitTemp = blockConfig.getAgent().getTemperature();
            if (explicitTemp != null && explicitTemp != 1.0) temperature = explicitTemp;
        }

        log.info("test_planning[{}]: model={} analysisChars={} contextChars={}",
                blockConfig.getId(), model, analysisSummary.length(), contextSummary.length());
        String response = llmClient.complete(model, SYSTEM_PROMPT, userMessage, maxTokens, temperature);

        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("test_planning[{}]: failed to parse JSON, returning empty plan: {}",
                    blockConfig.getId(), e.getMessage());
            return emptyPlan("adaptive", "LLM response parse error: " + e.getMessage());
        }

        return finalize(parsed);
    }

    // ── helpers ──

    private String summarizeAnalysis(Map<String, Object> input) {
        if (input == null) return "(no analysis output available)";
        Object analysisObj = input.get("analysis");
        if (!(analysisObj instanceof Map<?, ?> a)) return "(no analysis output available)";

        StringBuilder sb = new StringBuilder();
        Object summary = a.get("summary");
        if (summary != null) sb.append("summary: ").append(summary).append("\n");
        Object complexity = a.get("estimated_complexity");
        if (complexity != null) sb.append("complexity: ").append(complexity).append("\n");
        Object affected = a.get("affected_components");
        if (affected != null) sb.append("affected_components: ").append(affected).append("\n");
        Object checklist = a.get("acceptance_checklist");
        if (checklist != null) sb.append("acceptance_checklist: ").append(checklist).append("\n");
        Object risks = a.get("risks");
        if (risks != null) sb.append("risks: ").append(risks).append("\n");
        return sb.length() > 0 ? sb.toString() : "(analysis output is empty)";
    }

    private String summarizeContextScan(Map<String, Object> input) {
        if (input == null) return "(no context_scan output available)";
        Object ctxObj = input.get("context_scan");
        if (!(ctxObj instanceof Map<?, ?> c)) return "(no context_scan output available)";

        StringBuilder sb = new StringBuilder();
        Object stack = c.get("tech_stack");
        if (stack != null) sb.append("tech_stack: ").append(stack).append("\n");
        Object conv = c.get("code_conventions");
        if (conv != null) sb.append("code_conventions: ").append(conv).append("\n");
        Object suggestions = c.get("suggestions_for_codegen");
        if (suggestions != null) sb.append("suggestions_for_codegen: ").append(suggestions).append("\n");
        return sb.length() > 0 ? sb.toString() : "(context_scan output is empty)";
    }

    private Map<String, Object> emptyPlan(String strategy, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", strategy);
        out.put("cases", new ArrayList<>());
        out.put("coverage_estimate", 0.0);
        out.put("notes", reason);
        return out;
    }

    private Map<String, Object> finalize(Map<String, Object> parsed) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object strategy = parsed.get("strategy");
        out.put("strategy", normalizeStrategy(strategy));

        // Normalize cases — strip junk fields, ensure id ordering.
        out.put("cases", normalizeCases(parsed.get("cases")));

        Object coverage = parsed.get("coverage_estimate");
        out.put("coverage_estimate", clampCoverage(coverage));

        Object notes = parsed.get("notes");
        out.put("notes", notes != null ? notes.toString() : "");
        return out;
    }

    private static String normalizeStrategy(Object raw) {
        if (raw == null) return "adaptive";
        String s = raw.toString().toLowerCase().trim();
        return switch (s) {
            case "tdd", "adaptive", "none" -> s;
            default -> "adaptive";
        };
    }

    private List<Map<String, Object>> normalizeCases(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        int idx = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            Object id = m.get("id");
            entry.put("id", id != null ? id.toString() : "tc-" + idx);
            entry.put("type", strOrDef(m.get("type"), "unit"));
            entry.put("target", strOrDef(m.get("target"), ""));
            entry.put("scenario", strOrDef(m.get("scenario"), ""));
            Object bo = m.get("boundary_origin");
            entry.put("boundary_origin", (bo == null || "null".equals(bo.toString())) ? null : bo.toString());
            entry.put("priority", strOrDef(m.get("priority"), "important"));
            out.add(entry);
            idx++;
        }
        return out;
    }

    private static String strOrDef(Object v, String def) {
        return v != null && !v.toString().isBlank() ? v.toString() : def;
    }

    private static double clampCoverage(Object raw) {
        double v;
        if (raw instanceof Number n) v = n.doubleValue();
        else if (raw instanceof String s) {
            try { v = Double.parseDouble(s.trim()); } catch (Exception e) { v = 0.0; }
        } else v = 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
