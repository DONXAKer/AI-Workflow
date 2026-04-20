package com.workflow.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.YouTrackClient;
import com.workflow.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class YouTrackTaskCreationBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(YouTrackTaskCreationBlock.class);

    private static final String SUPPLEMENT_PROMPT_TEMPLATE =
        "Ты — senior engineering lead. Задача уже существует в YouTrack, её нужно дополнить — " +
        "улучшить описание, добавить критерии приёмки, технические детали и план реализации.\n\n" +
        "## Текущее требование / описание задачи\n{refined_requirement}\n\n" +
        "## Технический анализ\n{analysis}\n\n" +
        "## Уточнения\n{clarifications}\n\n" +
        "Ответь ТОЛЬКО JSON объектом с одной задачей — обновлённой версией существующей:\n" +
        "{\n" +
        "  \"tasks\": [\n" +
        "    {\n" +
        "      \"summary\": \"<уточнённый заголовок задачи>\",\n" +
        "      \"description\": \"<полное обновлённое описание в markdown с критериями приёмки и планом реализации>\",\n" +
        "      \"type\": \"Task|Bug|Feature\",\n" +
        "      \"priority\": \"Normal|Major|Critical\",\n" +
        "      \"estimated_hours\": <целое число>\n" +
        "    }\n" +
        "  ]\n" +
        "}\n\n" +
        "Правила:\n" +
        "- Ровно одна задача — это дополнение существующей, не декомпозиция.\n" +
        "- Описание должно быть исчерпывающим: что сделать, как проверить, edge cases.\n" +
        "- Не включай никакого текста за пределами JSON объекта.";

    private static final String DECOMPOSE_PROMPT_TEMPLATE =
        "Ты — senior engineering lead. Декомпозируй следующее уточнённое требование в набор конкретных задач для YouTrack. " +
        "Каждая задача должна быть самостоятельно реализуемой.\n\n" +
        "## Уточнённое требование\n{refined_requirement}\n\n" +
        "## Согласованный технический подход\n{approved_approach}\n\n" +
        "## Анализ\n{analysis}\n\n" +
        "## Уточнения\n{clarifications}\n\n" +
        "Ответь ТОЛЬКО JSON объектом:\n" +
        "{\n" +
        "  \"tasks\": [\n" +
        "    {\n" +
        "      \"summary\": \"<краткий заголовок задачи>\",\n" +
        "      \"description\": \"<подробное описание в markdown что нужно сделать>\",\n" +
        "      \"type\": \"Task|Bug|Feature\",\n" +
        "      \"priority\": \"Normal|Major|Critical\",\n" +
        "      \"estimated_hours\": <целое число>\n" +
        "    }\n" +
        "  ]\n" +
        "}\n\n" +
        "Правила:\n" +
        "- Создай 3–10 задач подходящей гранулярности.\n" +
        "- Описание каждой задачи должно включать критерии приёмки.\n" +
        "- Оценка часов должна быть реалистичной (1–16 на задачу).\n" +
        "- Не включай никакого текста за пределами JSON объекта.";

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "youtrack_tasks";
    }

    @Override
    public String getDescription() {
        return "Декомпозирует требование на задачи и создаёт соответствующие issues в YouTrack.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();

        // Resolve task_mode from youtrack_input output
        String taskMode = "decompose";
        Map<String, Object> ytInput = getNestedMap(input, "youtrack_input");
        if (ytInput != null) {
            taskMode = (String) ytInput.getOrDefault("task_mode", "decompose");
        }

        // Resolve refined_requirement from clarification output or input
        String refinedRequirement = "";
        Map<String, Object> clarificationOutput = getNestedMap(input, "clarification");
        if (clarificationOutput != null) {
            refinedRequirement = (String) clarificationOutput.getOrDefault("refined_requirement", "");
        }
        if (refinedRequirement.isBlank()) {
            refinedRequirement = (String) input.getOrDefault("requirement", "");
        }

        // Resolve approved_approach
        String approvedApproach = "";
        if (clarificationOutput != null) {
            approvedApproach = (String) clarificationOutput.getOrDefault("approved_approach", "");
        }

        // Resolve analysis
        String analysisText = "";
        Map<String, Object> analysisOutput = getNestedMap(input, "analysis");
        if (analysisOutput != null) {
            try {
                analysisText = objectMapper.writeValueAsString(analysisOutput);
            } catch (Exception e) {
                analysisText = analysisOutput.toString();
            }
        }

        // Resolve clarifications text
        String clarificationsText = "(нет уточнений)";
        if (clarificationOutput != null && clarificationOutput.get("clarifications") instanceof Map) {
            Map<String, String> clarMap = (Map<String, String>) clarificationOutput.get("clarifications");
            clarificationsText = buildClarificationsText(clarMap);
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

        String prompt;
        if ("supplement".equals(taskMode)) {
            prompt = SUPPLEMENT_PROMPT_TEMPLATE
                .replace("{refined_requirement}", refinedRequirement)
                .replace("{analysis}", analysisText)
                .replace("{clarifications}", clarificationsText);
        } else {
            prompt = DECOMPOSE_PROMPT_TEMPLATE
                .replace("{refined_requirement}", refinedRequirement)
                .replace("{approved_approach}", approvedApproach)
                .replace("{analysis}", analysisText)
                .replace("{clarifications}", clarificationsText);
        }

        String profileSystemPrompt = null;
        if (config.getAgent() != null && config.getAgent().getSystemPrompt() != null
                && !config.getAgent().getSystemPrompt().isBlank()) {
            profileSystemPrompt = config.getAgent().getSystemPrompt();
        }

        String llmResponse = llmClient.complete(model, profileSystemPrompt, prompt, maxTokens, temperature);

        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(llmResponse, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to parse tasks JSON: {}", e.getMessage());
            throw new RuntimeException("Failed to parse task creation LLM response: " + e.getMessage(), e);
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        if (parsed.get("tasks") instanceof List) {
            tasks = (List<Map<String, Object>>) parsed.get("tasks");
        }

        List<Map<String, Object>> youtrackIssues = new ArrayList<>();

        // Create issues in YouTrack if config available
        Object ytCfgObj = cfg.get("_youtrack_config");
        if (ytCfgObj instanceof Map) {
            Map<String, Object> ytCfg = (Map<String, Object>) ytCfgObj;
            String baseUrl = (String) ytCfg.getOrDefault("base_url", "");
            String token = (String) ytCfg.getOrDefault("token", "");
            String project = (String) ytCfg.getOrDefault("project", "");

            YouTrackClient ytClient = new YouTrackClient(baseUrl, token, project);

            // Resolve source issue id for linking
            String sourceIssueId = null;
            if (ytInput != null) {
                Map<String, Object> sourceIssue = (Map<String, Object>) ytInput.get("youtrack_source_issue");
                if (sourceIssue != null) {
                    sourceIssueId = (String) sourceIssue.get("id");
                }
            }

            if ("supplement".equals(taskMode) && sourceIssueId != null && !sourceIssueId.isBlank() && !tasks.isEmpty()) {
                // Update the existing issue with the first (and only) task
                Map<String, Object> task = tasks.get(0);
                String summary = (String) task.getOrDefault("summary", "");
                String description = (String) task.getOrDefault("description", "");
                Map<String, Object> updatedIssue = ytClient.updateIssue(sourceIssueId, summary, description);

                Map<String, Object> issueRef = new HashMap<>();
                issueRef.put("id", updatedIssue.getOrDefault("idReadable", sourceIssueId));
                issueRef.put("url", baseUrl + "/issue/" + updatedIssue.getOrDefault("idReadable", sourceIssueId));
                issueRef.put("summary", summary);
                issueRef.put("updated", true);
                youtrackIssues.add(issueRef);
            } else {
                // Create individual issues (decompose mode)
                List<String> createdLinks = new ArrayList<>();
                for (Map<String, Object> task : tasks) {
                    String summary = (String) task.getOrDefault("summary", "");
                    String description = (String) task.getOrDefault("description", "");
                    String issueType = (String) task.getOrDefault("type", "Task");
                    String priority = (String) task.getOrDefault("priority", "Normal");

                    try {
                        Map<String, Object> createdIssue = ytClient.createIssue(summary, description, issueType, priority);
                        String createdId = (String) createdIssue.getOrDefault("idReadable",
                            createdIssue.getOrDefault("id", ""));
                        String issueUrl = baseUrl + "/issue/" + createdId;

                        Map<String, Object> issueRef = new HashMap<>();
                        issueRef.put("id", createdId);
                        issueRef.put("url", issueUrl);
                        issueRef.put("summary", summary);
                        youtrackIssues.add(issueRef);
                        createdLinks.add(issueUrl);
                        log.info("Created YouTrack issue: {}", createdId);
                    } catch (Exception e) {
                        log.error("Failed to create YouTrack issue '{}': {}", summary, e.getMessage());
                    }
                }

                // Add comment to source issue with links to created issues
                if (sourceIssueId != null && !sourceIssueId.isBlank() && !createdLinks.isEmpty()) {
                    try {
                        String commentText = "Декомпозиция задачи создана pipeline Workflow AI:\n" +
                            String.join("\n", createdLinks);
                        ytClient.addComment(sourceIssueId, commentText);
                    } catch (Exception e) {
                        log.warn("Failed to add decomposition comment to source issue: {}", e.getMessage());
                    }
                }
            }
        } else {
            log.info("No _youtrack_config configured, tasks generated but not created in YouTrack.");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("youtrack_issues", youtrackIssues);
        return result;
    }

    private Map<String, Object> getNestedMap(Map<String, Object> input, String key) {
        Object val = input.get(key);
        if (val instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) val;
            return map;
        }
        return null;
    }

    private String buildClarificationsText(Map<String, String> clarifications) {
        if (clarifications == null || clarifications.isEmpty()) return "(нет уточнений)";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : clarifications.entrySet()) {
            sb.append("Q: ").append(entry.getKey()).append("\n");
            sb.append("A: ").append(entry.getValue()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
