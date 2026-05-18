package com.workflow.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.integrations.tracker.SubtaskSpec;
import com.workflow.integrations.tracker.TaskTracker;
import com.workflow.integrations.tracker.TaskTrackerRegistry;
import com.workflow.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Provider-agnostic replacement for {@code youtrack_tasks}. Uses LLM to decompose
 * requirements into tasks, then creates them in any configured tracker via
 * {@link TaskTrackerRegistry}.
 *
 * <p>YAML:
 * <pre>
 * - id: create_tasks
 *   block: task_creation
 *   config:
 *     provider: youtrack    # or jira, github, gitlab
 *     mode: decompose       # or supplement (default: decompose)
 * </pre>
 *
 * <p>Output:
 * <pre>
 * {
 *   "tasks": [{ "summary", "description", "type", "priority", "estimated_hours" }],
 *   "issues": [{ "id", "url", "summary" }],
 *   "youtrack_issues": [...]    // backward-compat alias
 * }
 * </pre>
 */
@Component
public class TaskCreationBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(TaskCreationBlock.class);

    private static final String SUPPLEMENT_PROMPT =
        "Ты — senior engineering lead. Задача уже существует в трекере, её нужно дополнить — " +
        "улучшить описание, добавить критерии приёмки, технические детали и план реализации.\n\n" +
        "## Текущее требование\n{refined_requirement}\n\n" +
        "## Технический анализ\n{analysis}\n\n" +
        "## Уточнения\n{clarifications}\n\n" +
        "Ответь ТОЛЬКО JSON:\n" +
        "{\"tasks\":[{\"summary\":\"...\",\"description\":\"...\",\"type\":\"Task|Bug|Feature\",\"priority\":\"Normal|Major|Critical\",\"estimated_hours\":N}]}\n" +
        "Ровно одна задача. Описание исчерпывающее с критериями приёмки. Никакого текста за пределами JSON.";

    private static final String DECOMPOSE_PROMPT =
        "Ты — senior engineering lead. Декомпозируй требование в набор конкретных задач.\n\n" +
        "## Уточнённое требование\n{refined_requirement}\n\n" +
        "## Согласованный технический подход\n{approved_approach}\n\n" +
        "## Анализ\n{analysis}\n\n" +
        "## Уточнения\n{clarifications}\n\n" +
        "Ответь ТОЛЬКО JSON:\n" +
        "{\"tasks\":[{\"summary\":\"...\",\"description\":\"...\",\"type\":\"Task|Bug|Feature\",\"priority\":\"Normal|Major|Critical\",\"estimated_hours\":N}]}\n" +
        "Правила: 3–10 задач, каждая с критериями приёмки, оценка 1–16 ч. Никакого текста за пределами JSON.";

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskTrackerRegistry trackerRegistry;

    @Override
    public String getName() { return "task_creation"; }

    @Override
    public String getDescription() {
        return "Декомпозирует требование на задачи через LLM и создаёт их в task tracker (YouTrack/Jira/GitHub/GitLab).";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        Map<String, Object> cfg = config.getConfig();
        String provider = resolveProvider(cfg);

        // mode can come from config or from upstream input
        String mode = "decompose";
        Object cfgMode = cfg.get("mode");
        if (cfgMode instanceof String s && !s.isBlank()) mode = s;
        Map<String, Object> ytInput = getNestedMap(input, "youtrack_input");
        if (ytInput != null && "supplement".equals(ytInput.get("task_mode"))) mode = "supplement";
        Map<String, Object> taskInput = getNestedMap(input, "task_input");

        // Resolve requirement
        String refinedRequirement = "";
        Map<String, Object> clarificationOutput = getNestedMap(input, "clarification");
        if (clarificationOutput != null) {
            refinedRequirement = (String) clarificationOutput.getOrDefault("refined_requirement", "");
        }
        if (refinedRequirement.isBlank()) refinedRequirement = (String) input.getOrDefault("requirement", "");

        String approvedApproach = clarificationOutput != null
            ? (String) clarificationOutput.getOrDefault("approved_approach", "") : "";
        String analysisText = "";
        Map<String, Object> analysisOutput = getNestedMap(input, "analysis");
        if (analysisOutput != null) {
            try { analysisText = objectMapper.writeValueAsString(analysisOutput); }
            catch (Exception e) { analysisText = analysisOutput.toString(); }
        }

        String clarificationsText = "(нет уточнений)";
        if (clarificationOutput != null && clarificationOutput.get("clarifications") instanceof Map<?, ?> clarMap) {
            clarificationsText = buildClarificationsText((Map<String, String>) clarMap);
        }

        // LLM call
        String model = "smart";
        int maxTokens = 8192;
        double temperature = 1.0;
        if (config.getAgent() != null) {
            if (config.getAgent().getModel() != null && !config.getAgent().getModel().isBlank())
                model = config.getAgent().getModel();
            maxTokens = config.getAgent().getMaxTokensOrDefault();
            temperature = config.getAgent().getTemperatureOrDefault();
        }
        String sysPrompt = config.getAgent() != null ? config.getAgent().getSystemPrompt() : null;

        String prompt = "supplement".equals(mode)
            ? SUPPLEMENT_PROMPT
                .replace("{refined_requirement}", refinedRequirement)
                .replace("{analysis}", analysisText)
                .replace("{clarifications}", clarificationsText)
            : DECOMPOSE_PROMPT
                .replace("{refined_requirement}", refinedRequirement)
                .replace("{approved_approach}", approvedApproach)
                .replace("{analysis}", analysisText)
                .replace("{clarifications}", clarificationsText);

        String llmResponse = llmClient.complete(model, sysPrompt, prompt, maxTokens, temperature);
        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(llmResponse, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse task_creation LLM response: " + e.getMessage(), e);
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        if (parsed.get("tasks") instanceof List<?> list) tasks = (List<Map<String, Object>>) list;

        // Resolve tracker and source issue
        Map<String, Object> trackerConfig = resolveTrackerConfig(cfg, provider);
        TaskTracker tracker = trackerRegistry.get(provider);
        String sourceIssueId = resolveSourceIssueId(ytInput, taskInput, input);
        String baseUrl = resolveBaseUrl(trackerConfig);

        List<Map<String, Object>> issues = new ArrayList<>();

        if ("supplement".equals(mode) && sourceIssueId != null && !tasks.isEmpty()) {
            Map<String, Object> task = tasks.get(0);
            String summary = (String) task.getOrDefault("summary", "");
            String description = (String) task.getOrDefault("description", "");
            try {
                tracker.updateIssue(sourceIssueId, summary, description, trackerConfig);
                Map<String, Object> ref = new HashMap<>();
                ref.put("id", sourceIssueId);
                ref.put("url", buildIssueUrl(baseUrl, provider, sourceIssueId));
                ref.put("summary", summary);
                ref.put("updated", true);
                issues.add(ref);
            } catch (Exception e) {
                log.error("Failed to update issue '{}': {}", sourceIssueId, e.getMessage());
            }
        } else {
            List<SubtaskSpec> specs = new ArrayList<>(tasks.size());
            for (Map<String, Object> task : tasks) {
                specs.add(new SubtaskSpec(
                    (String) task.getOrDefault("summary", ""),
                    (String) task.getOrDefault("description", ""),
                    task.get("estimated_hours") != null ? task.get("estimated_hours") + "h" : null
                ));
            }
            try {
                List<String> createdIds = tracker.createSubtasks(sourceIssueId, specs, trackerConfig);
                for (int idx = 0; idx < createdIds.size(); idx++) {
                    String cid = createdIds.get(idx);
                    Map<String, Object> ref = new HashMap<>();
                    ref.put("id", cid);
                    ref.put("url", buildIssueUrl(baseUrl, provider, cid));
                    ref.put("summary", idx < tasks.size() ? tasks.get(idx).getOrDefault("summary", "") : "");
                    issues.add(ref);
                    log.info("task_creation: created {} issue {}", provider, cid);
                }
            } catch (Exception e) {
                log.error("task_creation: failed to create subtasks in {}: {}", provider, e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("issues", issues);
        result.put("youtrack_issues", issues); // backward-compat alias
        return result;
    }

    @SuppressWarnings("unchecked")
    private String resolveSourceIssueId(Map<String, Object> ytInput, Map<String, Object> taskInput,
                                         Map<String, Object> input) {
        if (ytInput != null && ytInput.get("youtrack_source_issue") instanceof Map<?, ?> src) {
            Object id = ((Map<String, Object>) src).get("id");
            if (id instanceof String s && !s.isBlank()) return s;
        }
        if (taskInput != null && taskInput.get("issue") instanceof Map<?, ?> issueMap) {
            Object readableId = ((Map<String, Object>) issueMap).get("readableId");
            if (readableId instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    private String resolveProvider(Map<String, Object> cfg) {
        Object explicit = cfg.get("provider");
        if (explicit instanceof String s && !s.isBlank()) return s;
        Object defaultProvider = cfg.get("_default_tracker_provider");
        if (defaultProvider instanceof String s && !s.isBlank()) return s;
        return "youtrack";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveTrackerConfig(Map<String, Object> cfg, String provider) {
        Object snapshot = cfg.get("_" + provider + "_config");
        if (snapshot instanceof Map<?, ?> m) return (Map<String, Object>) m;
        Object issuesSnapshot = cfg.get("_" + provider + "_issues_config");
        if (issuesSnapshot instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return cfg;
    }

    private String resolveBaseUrl(Map<String, Object> trackerConfig) {
        Object url = trackerConfig.get("baseUrl");
        if (url instanceof String s && !s.isBlank()) return s;
        url = trackerConfig.get("base_url");
        if (url instanceof String s && !s.isBlank()) return s;
        return "";
    }

    private String buildIssueUrl(String baseUrl, String provider, String issueId) {
        if (baseUrl.isBlank()) return issueId;
        return switch (provider) {
            case "github" -> baseUrl + "/issues/" + issueId;
            case "gitlab" -> baseUrl + "/-/issues/" + issueId;
            case "jira" -> baseUrl + "/browse/" + issueId;
            default -> baseUrl + "/issue/" + issueId;
        };
    }

    private Map<String, Object> getNestedMap(Map<String, Object> input, String key) {
        Object val = input.get(key);
        if (val instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) map;
            return result;
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
