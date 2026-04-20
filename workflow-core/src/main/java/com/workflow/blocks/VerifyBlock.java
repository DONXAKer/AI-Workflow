package com.workflow.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.BlockConfig;
import com.workflow.config.FieldCheckConfig;
import com.workflow.config.LLMCheckConfig;
import com.workflow.config.VerifyConfig;
import com.workflow.core.PipelineRun;
import com.workflow.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class VerifyBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(VerifyBlock.class);

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "verify";
    }

    @Override
    public String getDescription() {
        return "Структурная и LLM-проверка качества вывода предыдущего блока";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        VerifyConfig verifyConfig = config.getVerify();
        if (verifyConfig == null) {
            log.warn("VerifyBlock '{}' has no verify config — passing trivially", config.getId());
            return buildResult(true, null, 0, 0, List.of(), config.getId(), 0, "");
        }

        String subjectId = verifyConfig.getSubject();
        Object subjectOutput = input.get(subjectId);
        Map<String, Object> subject = new HashMap<>();
        if (subjectOutput instanceof Map) {
            subject = (Map<String, Object>) subjectOutput;
        }

        // --- Structural field checks ---
        List<String> issues = new ArrayList<>();
        int checksPassed = 0;
        int checksFailed = 0;

        for (FieldCheckConfig check : verifyConfig.getChecks()) {
            String result = evaluateCheck(check, subject);
            if (result == null) {
                checksPassed++;
            } else {
                checksFailed++;
                String msg = check.getMessage() != null && !check.getMessage().isBlank()
                    ? check.getMessage() : result;
                issues.add(msg);
            }
        }

        boolean structuralPassed = checksFailed == 0;

        // --- LLM check ---
        Double score = null;
        String recommendation = "";
        boolean llmPassed = true;

        LLMCheckConfig llmCheck = verifyConfig.getLlmCheck();
        if (llmCheck != null && llmCheck.isEnabled()) {
            String model = llmCheck.getModel() != null ? llmCheck.getModel() : "claude-sonnet-4-6";
            if (config.getAgent() != null && config.getAgent().getModel() != null) {
                model = config.getAgent().getModel();
            }

            String subjectJson = objectMapper.writeValueAsString(subject);
            String llmPrompt = llmCheck.getPrompt() +
                "\n\n## Subject Output\n```json\n" + subjectJson + "\n```\n\n" +
                "## Structural Issues Found\n" + (issues.isEmpty() ? "None" : String.join("\n", issues.stream().map(s -> "- " + s).toList())) +
                "\n\nRespond ONLY with a JSON object: {\"passed\": bool, \"score\": 0-10, \"issues\": [], \"recommendation\": \"\"}";

            try {
                String verifySystemPrompt = "You are a quality assurance expert. Evaluate outputs objectively and return valid JSON.";
                if (config.getAgent() != null && config.getAgent().getSystemPrompt() != null
                        && !config.getAgent().getSystemPrompt().isBlank()) {
                    verifySystemPrompt = config.getAgent().getSystemPrompt() + "\n\n" + verifySystemPrompt;
                }
                String llmResponse = llmClient.complete(model,
                    verifySystemPrompt,
                    llmPrompt, 1024, 0.3);

                Map<String, Object> llmResult = objectMapper.readValue(llmResponse,
                    new TypeReference<Map<String, Object>>() {});

                Object scoreVal = llmResult.get("score");
                if (scoreVal instanceof Number) {
                    score = ((Number) scoreVal).doubleValue();
                }
                llmPassed = score != null && score >= llmCheck.getMinScore();
                recommendation = (String) llmResult.getOrDefault("recommendation", "");

                Object llmIssues = llmResult.get("issues");
                if (llmIssues instanceof List) {
                    for (Object issue : (List<?>) llmIssues) {
                        if (issue instanceof String s && !s.isBlank()) {
                            issues.add("[LLM] " + s);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("LLM check failed in VerifyBlock '{}': {}", config.getId(), e.getMessage());
                issues.add("LLM check failed: " + e.getMessage());
                llmPassed = false;
            }
        }

        boolean passed = structuralPassed && llmPassed;

        // Iteration count from run state
        String loopKey = "loopback:" + config.getId() + ":" +
            (verifyConfig.getOnFail() != null ? verifyConfig.getOnFail().getTarget() : "");
        int iteration = run.getLoopIterations().getOrDefault(loopKey, 0);

        log.info("VerifyBlock '{}': passed={}, score={}, issues={}", config.getId(), passed, score, issues.size());

        return buildResult(passed, score, checksPassed, checksFailed, issues, subjectId, iteration, recommendation);
    }

    private Map<String, Object> buildResult(boolean passed, Double score, int checksPassed,
                                             int checksFailed, List<String> issues,
                                             String subjectBlock, int iteration, String recommendation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", passed);
        result.put("score", score);
        result.put("checks_passed", checksPassed);
        result.put("checks_failed", checksFailed);
        result.put("issues", new ArrayList<>(issues));
        result.put("subject_block", subjectBlock);
        result.put("iteration", iteration);
        result.put("recommendation", recommendation);
        return result;
    }

    /** Returns null if check passes, or an error string if it fails. */
    @SuppressWarnings("unchecked")
    private String evaluateCheck(FieldCheckConfig check, Map<String, Object> subject) {
        String field = check.getField();
        Object value = subject.get(field);
        Object ruleValue = check.getValue();

        return switch (check.getRule()) {
            case "equals" -> {
                if (value == null && ruleValue == null) yield null;
                if (value == null || ruleValue == null) {
                    yield field + " is " + value + ", expected " + ruleValue;
                }
                // Numeric-aware comparison so {rule: equals, value: 0} matches
                // whether the block returned 0, 0L, or 0.0.
                if (value instanceof Number a && ruleValue instanceof Number b) {
                    yield a.doubleValue() == b.doubleValue()
                        ? null : field + " is " + value + ", expected " + ruleValue;
                }
                yield value.equals(ruleValue) || value.toString().equals(ruleValue.toString())
                    ? null : field + " is " + value + ", expected " + ruleValue;
            }
            case "not_empty" -> {
                if (value == null) yield field + " is null";
                if (value instanceof String s && s.isBlank()) yield field + " is empty";
                if (value instanceof List<?> l && l.isEmpty()) yield field + " is empty list";
                yield null;
            }
            case "min_length" -> {
                int min = toInt(ruleValue);
                String str = value instanceof String s ? s : (value != null ? value.toString() : "");
                yield str.length() >= min ? null : field + " length " + str.length() + " < " + min;
            }
            case "max_length" -> {
                int max = toInt(ruleValue);
                String str = value instanceof String s ? s : (value != null ? value.toString() : "");
                yield str.length() <= max ? null : field + " length " + str.length() + " > " + max;
            }
            case "min_items" -> {
                int min = toInt(ruleValue);
                int size = value instanceof List<?> l ? l.size() : 0;
                yield size >= min ? null : field + " has " + size + " items, need >= " + min;
            }
            case "max_items" -> {
                int max = toInt(ruleValue);
                int size = value instanceof List<?> l ? l.size() : 0;
                yield size <= max ? null : field + " has " + size + " items, max " + max;
            }
            case "one_of" -> {
                if (ruleValue instanceof List<?> allowed) {
                    yield allowed.contains(value) ? null : field + " value '" + value + "' not in " + allowed;
                }
                yield field + ": one_of requires a list value";
            }
            case "regex" -> {
                String str = value instanceof String s ? s : (value != null ? value.toString() : "");
                String pattern = ruleValue != null ? ruleValue.toString() : "";
                yield Pattern.compile(pattern).matcher(str).find() ? null : field + " does not match pattern " + pattern;
            }
            case "gt" -> {
                double num = toDouble(value);
                double threshold = toDouble(ruleValue);
                yield num > threshold ? null : field + " " + num + " is not > " + threshold;
            }
            case "lt" -> {
                double num = toDouble(value);
                double threshold = toDouble(ruleValue);
                yield num < threshold ? null : field + " " + num + " is not < " + threshold;
            }
            default -> "Unknown rule: " + check.getRule();
        };
    }

    private int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(val)); } catch (Exception e) { return 0; }
    }

    private double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception e) { return 0.0; }
    }
}
