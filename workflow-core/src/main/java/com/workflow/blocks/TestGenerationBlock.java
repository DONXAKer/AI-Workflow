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
import java.util.Map;

@Component
public class TestGenerationBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(TestGenerationBlock.class);

    private static final String SYSTEM_PROMPT =
        "Ты — senior QA-инженер. Ты пишешь тесты (unit/integration/e2e) по требованиям и списку задач до того, " +
        "как написан код (TDD). Тесты должны покрывать happy path, граничные случаи и негативные сценарии. " +
        "Возвращай валидный JSON со списком test-файлов.";

    private static final String USER_TEMPLATE =
        "## Требование\n\n{requirement}\n\n" +
        "## Анализ\n\n{analysis}\n\n" +
        "## Подзадачи\n\n{tasks}\n\n" +
        "---\n\n" +
        "Сгенерируй тесты. Ответь ТОЛЬКО JSON:\n\n" +
        "{\n" +
        "  \"tests\": [\n" +
        "    { \"path\": \"<путь к файлу теста>\", \"content\": \"<содержимое>\", \"type\": \"<unit|integration|e2e>\", \"covers\": [\"<task_id>\"] }\n" +
        "  ],\n" +
        "  \"notes\": \"<ограничения, предположения>\"\n" +
        "}";

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "test_generation";
    }

    @Override
    public String getDescription() {
        return "Генерирует тесты (unit/integration/e2e) по требованию и задачам до написания production-кода (TDD).";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        String requirement = (String) input.getOrDefault("requirement", "");
        String analysis = extract(input, new String[]{"analysis"}, "technical_approach");
        String tasks = extract(input, new String[]{"tasks", "youtrack_tasks"}, "tasks");

        String model = "claude-sonnet-4-6";
        int maxTokens = 8192;
        double temperature = 0.7;
        if (config.getAgent() != null) {
            if (config.getAgent().getModel() != null && !config.getAgent().getModel().isBlank()) {
                model = config.getAgent().getModel();
            }
            maxTokens = config.getAgent().getMaxTokensOrDefault();
            temperature = config.getAgent().getTemperatureOrDefault();
        }

        String userMessage = USER_TEMPLATE
            .replace("{requirement}", requirement != null ? requirement : "")
            .replace("{analysis}", analysis)
            .replace("{tasks}", tasks);

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
            log.error("Failed to parse test_generation JSON: {}", e.getMessage());
            throw new RuntimeException("Failed to parse test_generation LLM response as JSON: " + e.getMessage(), e);
        }

        result.putIfAbsent("tests", new ArrayList<>());
        result.putIfAbsent("notes", "");
        return result;
    }

    @SuppressWarnings("unchecked")
    private String extract(Map<String, Object> input, String[] keys, String field) {
        for (String key : keys) {
            Object val = input.get(key);
            if (val instanceof Map<?, ?> m) {
                Object f = ((Map<String, Object>) m).get(field);
                if (f != null) {
                    try {
                        return f instanceof String s ? s : new ObjectMapper().writeValueAsString(f);
                    } catch (Exception ignored) {
                        return f.toString();
                    }
                }
            }
        }
        return "";
    }
}
