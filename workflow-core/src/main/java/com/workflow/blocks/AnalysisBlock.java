package com.workflow.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.knowledge.KnowledgeBase;
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
public class AnalysisBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(AnalysisBlock.class);

    private static final String SYSTEM_PROMPT =
        "Ты — senior software analyst с глубокой экспертизой в архитектуре ПО, системном дизайне и оценке технических рисков. " +
        "Ты детально анализируешь требования, определяешь затронутые компоненты, оцениваешь технические подходы, " +
        "выявляешь риски и открытые вопросы. Всегда отвечай валидным JSON согласно указанной схеме.";

    private static final String USER_TEMPLATE =
        "## Требование\n\n{requirement}\n\n" +
        "## Контекст кодовой базы / документации\n\n{context}\n\n" +
        "---\n\n" +
        "Проанализируй требование выше и ответь ТОЛЬКО JSON объектом со следующими ключами:\n\n" +
        "{\n" +
        "  \"summary\": \"<краткое резюме что нужно построить>\",\n" +
        "  \"affected_components\": [\"<компонент1>\", \"<компонент2>\"],\n" +
        "  \"technical_approach\": \"<детальное описание рекомендуемого технического подхода>\",\n" +
        "  \"estimated_complexity\": \"<low|medium|high>\",\n" +
        "  \"risks\": [\"<риск1>\", \"<риск2>\"],\n" +
        "  \"open_questions\": [\"<вопрос1>\", \"<вопрос2>\"]\n" +
        "}\n\n" +
        "Не включай никакого текста за пределами JSON объекта.";

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private KnowledgeBase knowledgeBase;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "analysis";
    }

    @Override
    public String getDescription() {
        return "Глубоко анализирует требование, определяет затронутые компоненты, технический подход, сложность, риски и открытые вопросы.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        String requirement = (String) input.getOrDefault("requirement", "");

        // Query knowledge base for context
        String context = "No additional context available.";
        if (requirement != null && !requirement.isBlank()) {
            try {
                String kbResult = knowledgeBase.query(requirement, 5);
                if (kbResult != null && !kbResult.isBlank()) {
                    context = kbResult;
                }
            } catch (Exception e) {
                log.warn("Knowledge base query failed: {}", e.getMessage());
            }
        }

        // Determine model
        String model = "claude-opus-4-6";
        int maxTokens = 8192;
        double temperature = 1.0;
        if (config.getAgent() != null) {
            if (config.getAgent().getModel() != null && !config.getAgent().getModel().isBlank()) {
                model = config.getAgent().getModel();
            }
            maxTokens = config.getAgent().getMaxTokensOrDefault();
            temperature = config.getAgent().getTemperatureOrDefault();
        }

        String userMessage = USER_TEMPLATE
            .replace("{requirement}", requirement != null ? requirement : "")
            .replace("{context}", context);

        // Append loopback feedback if this is a retry
        @SuppressWarnings("unchecked")
        Map<String, Object> loopback = (Map<String, Object>) input.get("_loopback");
        if (loopback != null) {
            int iteration = loopback.get("iteration") instanceof Number n ? n.intValue() : 0;
            List<?> issuesList = loopback.get("issues") instanceof List<?> l ? l : List.of();
            String feedback = issuesList.stream()
                .map(Object::toString)
                .map(s -> "- " + s)
                .reduce("", (a, b) -> a + "\n" + b).strip();
            String rec = loopback.getOrDefault("recommendation", "").toString();
            userMessage += "\n\n---\n\n## Повторная попытка (итерация " + (iteration + 1) + ")\n\n" +
                "Предыдущий анализ не прошёл верификацию. Проблемы:\n" + feedback;
            if (!rec.isBlank()) userMessage += "\n\nРекомендация: " + rec;
        }

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
            log.error("Failed to parse analysis JSON: {}", e.getMessage());
            throw new RuntimeException("Failed to parse analysis LLM response as JSON: " + e.getMessage(), e);
        }

        // Set defaults for missing keys
        result.putIfAbsent("summary", "");
        result.putIfAbsent("affected_components", new ArrayList<>());
        result.putIfAbsent("technical_approach", "");
        result.putIfAbsent("estimated_complexity", "medium");
        result.putIfAbsent("risks", new ArrayList<>());
        result.putIfAbsent("open_questions", new ArrayList<>());

        return result;
    }
}
