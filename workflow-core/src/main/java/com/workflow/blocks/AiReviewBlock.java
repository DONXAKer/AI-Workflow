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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AiReviewBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(AiReviewBlock.class);

    private static final String SYSTEM_PROMPT =
        "Ты — senior code reviewer с фокусом на корректность, безопасность, производительность и читаемость. " +
        "Ты даёшь прицельные замечания по diff, различая блокеры (must fix) и подсказки (nits). " +
        "Всегда отвечай валидным JSON согласно указанной схеме.";

    private static final String USER_TEMPLATE =
        "## Требование\n\n{requirement}\n\n" +
        "## Сгенерированный код / изменения\n\n{changes}\n\n" +
        "---\n\n" +
        "Сделай review и ответь ТОЛЬКО JSON объектом со следующими ключами:\n\n" +
        "{\n" +
        "  \"verdict\": \"<approve|request_changes|reject>\",\n" +
        "  \"summary\": \"<краткое резюме 1-3 предложения>\",\n" +
        "  \"blockers\": [\"<критичная проблема 1>\", \"<...>\"],\n" +
        "  \"suggestions\": [\"<подсказка 1>\", \"<...>\"],\n" +
        "  \"security_concerns\": [\"<проблема безопасности>\", \"<...>\"],\n" +
        "  \"score\": <оценка 0-10>\n" +
        "}\n\n" +
        "Не включай никакого текста за пределами JSON объекта.";

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "ai_review";
    }

    @Override
    public String getDescription() {
        return "Проводит AI code review по сгенерированному diff: корректность, безопасность, стиль. Может триггерить loopback на code_generation.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        String requirement = (String) input.getOrDefault("requirement", "");
        String changes = resolveChanges(input);

        String model = "smart";
        int maxTokens = 8192;
        double temperature = 0.3;
        if (config.getAgent() != null) {
            if (config.getAgent().getModel() != null && !config.getAgent().getModel().isBlank()) {
                model = config.getAgent().getModel();
            }
            maxTokens = config.getAgent().getMaxTokensOrDefault();
            temperature = config.getAgent().getTemperatureOrDefault();
        }

        String userMessage = USER_TEMPLATE
            .replace("{requirement}", requirement != null ? requirement : "")
            .replace("{changes}", changes);

        String effectiveSystemPrompt = SYSTEM_PROMPT;
        if (config.getAgent() != null && config.getAgent().getSystemPrompt() != null
                && !config.getAgent().getSystemPrompt().isBlank()) {
            effectiveSystemPrompt = config.getAgent().getSystemPrompt() + "\n\n" + SYSTEM_PROMPT;
        }

        String response = llmClient.complete(model, effectiveSystemPrompt, userMessage, maxTokens, temperature);

        Map<String, Object> result;
        try {
            result = objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to parse ai_review JSON: {}", e.getMessage());
            throw new RuntimeException("Failed to parse ai_review LLM response as JSON: " + e.getMessage(), e);
        }

        result.putIfAbsent("verdict", "request_changes");
        result.putIfAbsent("summary", "");
        result.putIfAbsent("blockers", new ArrayList<>());
        result.putIfAbsent("suggestions", new ArrayList<>());
        result.putIfAbsent("security_concerns", new ArrayList<>());
        result.putIfAbsent("score", 0);

        // "passed" флаг — совместимость с verify-стилем loopback
        String verdict = String.valueOf(result.get("verdict"));
        result.put("passed", "approve".equals(verdict));
        result.put("issues", result.get("blockers"));
        return result;
    }

    @SuppressWarnings("unchecked")
    private String resolveChanges(Map<String, Object> input) {
        for (String key : new String[]{"code_generation", "codegen", "changes"}) {
            Object val = input.get(key);
            if (val instanceof Map<?, ?> m) {
                Map<String, Object> mm = (Map<String, Object>) m;
                if (mm.containsKey("changes")) {
                    try {
                        return new ObjectMapper().writeValueAsString(mm.get("changes"));
                    } catch (Exception ignored) {
                        return String.valueOf(mm.get("changes"));
                    }
                }
            }
        }
        return "(no changes available)";
    }
}
