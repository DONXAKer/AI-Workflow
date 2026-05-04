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
public class BusinessIntakeBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(BusinessIntakeBlock.class);

    private static final String SYSTEM_PROMPT =
        "Ты — product analyst. Ты превращаешь сырое бизнес-описание (Slack/email/текст) в формализованную задачу: " +
        "проблема, user story, acceptance criteria, затронутые роли, предполагаемая ценность. " +
        "Отвечай валидным JSON.";

    private static final String USER_TEMPLATE =
        "## Сырое описание\n\n{raw_text}\n\n" +
        "## Дополнительные вложения / контекст\n\n{attachments}\n\n" +
        "---\n\n" +
        "Сформализуй задачу. Ответь ТОЛЬКО JSON:\n\n" +
        "{\n" +
        "  \"title\": \"<короткий заголовок>\",\n" +
        "  \"problem\": \"<какую проблему решаем>\",\n" +
        "  \"user_story\": \"Как <роль>, я хочу <действие>, чтобы <выгода>\",\n" +
        "  \"acceptance_criteria\": [\"<критерий 1>\", \"<...>\"],\n" +
        "  \"stakeholders\": [\"<роль 1>\", \"<...>\"],\n" +
        "  \"estimated_value\": \"<low|medium|high>\",\n" +
        "  \"open_questions\": [\"<вопрос 1>\", \"<...>\"],\n" +
        "  \"requirement\": \"<единое консолидированное требование для последующих этапов>\"\n" +
        "}";

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "business_intake";
    }

    @Override
    public String getDescription() {
        return "Превращает сырой бизнес-запрос (свободный текст) в формализованную задачу с acceptance criteria; далее может создать issue в task tracker.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();
        String rawText = (String) input.getOrDefault("requirement", "");
        if (rawText == null || rawText.isBlank()) {
            rawText = stringOr(cfg.get("raw_text"), "");
        }
        String attachments = stringOr(cfg.get("attachments"), "(none)");

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
            .replace("{raw_text}", rawText)
            .replace("{attachments}", attachments);

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
            log.error("Failed to parse business_intake JSON: {}", e.getMessage());
            throw new RuntimeException("Failed to parse business_intake LLM response as JSON: " + e.getMessage(), e);
        }

        result.putIfAbsent("title", "");
        result.putIfAbsent("problem", "");
        result.putIfAbsent("user_story", "");
        result.putIfAbsent("acceptance_criteria", new ArrayList<>());
        result.putIfAbsent("stakeholders", new ArrayList<>());
        result.putIfAbsent("estimated_value", "medium");
        result.putIfAbsent("open_questions", new ArrayList<>());

        // "requirement" ключ должен быть строкой, чтобы PipelineRunner подхватил его для последующих этапов
        Object reqObj = result.get("requirement");
        if (!(reqObj instanceof String) || ((String) reqObj).isBlank()) {
            String fallback = buildRequirementString(result);
            result.put("requirement", fallback);
        }

        // TODO: опционально создать issue в task tracker через skill-tool
        return result;
    }

    @SuppressWarnings("unchecked")
    private String buildRequirementString(Map<String, Object> result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.getOrDefault("title", "")).append("\n\n");
        sb.append("Problem: ").append(result.getOrDefault("problem", "")).append("\n\n");
        sb.append("User story: ").append(result.getOrDefault("user_story", "")).append("\n\n");
        sb.append("Acceptance criteria:\n");
        Object ac = result.get("acceptance_criteria");
        if (ac instanceof java.util.List<?> list) {
            for (Object item : list) sb.append("- ").append(item).append("\n");
        }
        return sb.toString();
    }

    private String stringOr(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }
}
