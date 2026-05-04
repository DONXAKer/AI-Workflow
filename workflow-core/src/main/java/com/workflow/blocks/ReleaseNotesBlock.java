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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Component
public class ReleaseNotesBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(ReleaseNotesBlock.class);

    private static final String SYSTEM_PROMPT =
        "Ты — technical writer. Ты пишешь release notes для конечных пользователей по результатам разработки: " +
        "бизнес-ценность, пользовательские изменения, technical notes, known issues. Пиши лаконично, без маркетингового шума. " +
        "Отвечай валидным JSON.";

    private static final String USER_TEMPLATE =
        "## Исходное требование\n\n{requirement}\n\n" +
        "## Анализ\n\n{analysis}\n\n" +
        "## Изменения\n\n{changes}\n\n" +
        "## Версия артефакта\n\n{version}\n\n" +
        "---\n\n" +
        "Сформируй release notes. Ответь ТОЛЬКО JSON:\n\n" +
        "{\n" +
        "  \"title\": \"<заголовок релиза>\",\n" +
        "  \"summary\": \"<1-2 предложения что поменялось для пользователя>\",\n" +
        "  \"user_facing_changes\": [\"<изменение 1>\", \"<...>\"],\n" +
        "  \"technical_notes\": [\"<техническая заметка>\", \"<...>\"],\n" +
        "  \"known_issues\": [\"<известное ограничение>\", \"<...>\"],\n" +
        "  \"markdown\": \"<финальный текст в markdown для публикации>\"\n" +
        "}";

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "release_notes";
    }

    @Override
    public String getDescription() {
        return "Суммаризует результаты разработки в release notes (markdown) для публикации в tracker/Slack/changelog.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        String requirement = (String) input.getOrDefault("requirement", "");
        String analysis = extract(input, new String[]{"analysis"}, "summary");
        String changes = extract(input, new String[]{"code_generation", "codegen"}, "commit_message");
        String version = extract(input, new String[]{"build"}, "artifact_version");

        String model = "smart";
        int maxTokens = 4096;
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
            .replace("{changes}", changes)
            .replace("{version}", version);

        String response = llmClient.complete(model, SYSTEM_PROMPT, userMessage, maxTokens, temperature);

        Map<String, Object> result;
        try {
            result = objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to parse release_notes JSON: {}", e.getMessage());
            throw new RuntimeException("Failed to parse release_notes LLM response as JSON: " + e.getMessage(), e);
        }

        result.putIfAbsent("title", "Release");
        result.putIfAbsent("summary", "");
        result.putIfAbsent("user_facing_changes", new ArrayList<>());
        result.putIfAbsent("technical_notes", new ArrayList<>());
        result.putIfAbsent("known_issues", new ArrayList<>());
        result.putIfAbsent("markdown", "");
        result.put("generated_at", Instant.now().toString());

        // TODO: опционально запостить markdown в task tracker / Slack через skill-tool

        return result;
    }

    @SuppressWarnings("unchecked")
    private String extract(Map<String, Object> input, String[] keys, String field) {
        for (String key : keys) {
            Object val = input.get(key);
            if (val instanceof Map<?, ?> m) {
                Object f = ((Map<String, Object>) m).get(field);
                if (f != null) return f.toString();
            }
        }
        return "";
    }
}
