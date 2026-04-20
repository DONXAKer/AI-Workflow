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

import java.util.*;

@Component
public class ClarificationBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(ClarificationBlock.class);

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "clarification";
    }

    @Override
    public String getDescription() {
        return "Показывает открытые вопросы из анализа, собирает ответы пользователя и формирует уточнённое требование и согласованный подход.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        String requirement = (String) input.getOrDefault("requirement", "");

        // Get analysis output from input (may be keyed under "analysis")
        Map<String, Object> analysis = new HashMap<>();
        if (input.get("analysis") instanceof Map) {
            analysis = (Map<String, Object>) input.get("analysis");
        }

        List<String> openQuestions = new ArrayList<>();
        if (analysis.get("open_questions") instanceof List) {
            for (Object q : (List<?>) analysis.get("open_questions")) {
                if (q instanceof String) openQuestions.add((String) q);
            }
        }

        // Determine model
        String model = "claude-sonnet-4-6";
        int maxTokens = 8192;
        double temperature = 1.0;
        if (config.getAgent() != null) {
            if (config.getAgent().getModel() != null && !config.getAgent().getModel().isBlank()) {
                model = config.getAgent().getModel();
            }
            maxTokens = config.getAgent().getMaxTokensOrDefault();
            temperature = config.getAgent().getTemperatureOrDefault();
        }

        Scanner scanner = new Scanner(System.in);
        Map<String, String> clarifications = new LinkedHashMap<>();

        // Phase 1: Answer open_questions from analysis
        if (!openQuestions.isEmpty()) {
            System.out.println("\n=== Уточняющие вопросы из анализа ===\n");
            for (int i = 0; i < openQuestions.size(); i++) {
                String question = openQuestions.get(i);
                System.out.printf("[%d/%d] %s%n", i + 1, openQuestions.size(), question);
                System.out.print("Ваш ответ (Enter для пропуска): ");
                String answer = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
                if (answer.isBlank()) {
                    answer = "(пропущено)";
                }
                clarifications.put(question, answer);
            }
        }

        // Phase 2: Generate additional questions via LLM
        String analysisJson;
        try {
            analysisJson = objectMapper.writeValueAsString(analysis);
        } catch (Exception e) {
            analysisJson = analysis.toString();
        }

        String clarificationsText = buildClarificationsText(clarifications);

        String additionalQuestionsPrompt =
            "Ты — senior software engineer, помогающий уточнить требование к фиче перед реализацией.\n\n" +
            "## Исходное требование\n" + requirement + "\n\n" +
            "## Первоначальный анализ\n" + analysisJson + "\n\n" +
            "## Ответы на открытые вопросы до сих пор\n" + clarificationsText + "\n\n" +
            "На основе вышеизложенного сгенерируй до 5 дополнительных уточняющих вопросов, которые помогут создать " +
            "более точный план реализации. Сосредоточься на неоднозначностях, граничных случаях и точках интеграции, " +
            "которые ещё не рассмотрены.\n\n" +
            "Ответь ТОЛЬКО JSON массивом строк с вопросами, например:\n" +
            "[\"Вопрос 1?\", \"Вопрос 2?\"]\n\n" +
            "Если дополнительные вопросы не нужны, ответь пустым массивом: []";

        String profileSystemPrompt = null;
        if (config.getAgent() != null && config.getAgent().getSystemPrompt() != null
                && !config.getAgent().getSystemPrompt().isBlank()) {
            profileSystemPrompt = config.getAgent().getSystemPrompt();
        }

        String additionalQuestionsResponse = llmClient.complete(model, profileSystemPrompt, additionalQuestionsPrompt, maxTokens, temperature);

        List<String> additionalQuestions = new ArrayList<>();
        try {
            additionalQuestions = objectMapper.readValue(additionalQuestionsResponse, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse additional questions: {}", e.getMessage());
        }

        if (!additionalQuestions.isEmpty()) {
            System.out.println("\n=== Дополнительные уточняющие вопросы ===\n");
            for (int i = 0; i < additionalQuestions.size(); i++) {
                String question = additionalQuestions.get(i);
                System.out.printf("[%d/%d] %s%n", i + 1, additionalQuestions.size(), question);
                System.out.print("Ваш ответ (Enter для пропуска): ");
                String answer = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
                if (answer.isBlank()) {
                    answer = "(пропущено)";
                }
                clarifications.put(question, answer);
            }
        }

        // Phase 3: Refine requirement via LLM
        String analysisSummary = (String) analysis.getOrDefault("summary", analysisJson);
        clarificationsText = buildClarificationsText(clarifications);

        String refinePrompt =
            "Ты — технический писатель. На основе исходного требования и предоставленных уточнений составь:\n" +
            "1. Уточнённое, точное формулирование требования.\n" +
            "2. Краткое резюме согласованного технического подхода.\n\n" +
            "## Исходное требование\n" + requirement + "\n\n" +
            "## Резюме анализа\n" + analysisSummary + "\n\n" +
            "## Уточнения\n" + clarificationsText + "\n\n" +
            "Ответь ТОЛЬКО JSON:\n" +
            "{\n" +
            "  \"refined_requirement\": \"<точное требование>\",\n" +
            "  \"approved_approach\": \"<резюме технического подхода>\"\n" +
            "}";

        String refineResponse = llmClient.complete(model, profileSystemPrompt, refinePrompt, maxTokens, temperature);

        Map<String, Object> refined;
        try {
            refined = objectMapper.readValue(refineResponse, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to parse refined requirement JSON: {}", e.getMessage());
            refined = new HashMap<>();
        }

        String refinedRequirement = (String) refined.getOrDefault("refined_requirement", requirement);
        String approvedApproach = (String) refined.getOrDefault("approved_approach", "");

        Map<String, Object> result = new HashMap<>();
        result.put("clarifications", clarifications);
        result.put("refined_requirement", refinedRequirement);
        result.put("approved_approach", approvedApproach);
        return result;
    }

    private String buildClarificationsText(Map<String, String> clarifications) {
        if (clarifications.isEmpty()) {
            return "(нет уточнений)";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : clarifications.entrySet()) {
            sb.append("Q: ").append(entry.getKey()).append("\n");
            sb.append("A: ").append(entry.getValue()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
