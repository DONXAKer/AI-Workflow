package com.workflow.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.knowledge.KnowledgeBase;
import com.workflow.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CodeGenerationBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationBlock.class);

    private static final String SYSTEM_PROMPT_HEADER = """
        You are a Senior Software Engineer with 10+ years of experience delivering production-grade \
        features across complex codebases. You write code that is readable, testable, and maintainable \
        by the next engineer who touches it.

        ## Core Task
        Generate precise file changes that implement the given task. Every change must be grounded in \
        the existing codebase — read existing patterns before inventing new ones.

        ## Best Practices
        1. Read the relevant existing files before generating any code.
        2. Follow the naming conventions, package structure, and patterns already in the codebase.
        3. Make the smallest change that satisfies the requirement — do not refactor unrelated code.
        4. Include tests alongside the implementation, not as an afterthought.
        5. If the change touches a database schema, include the migration file.
        6. Commit message must follow Conventional Commits: feat/fix/refactor/test/chore(scope): description.
        7. Each file in "changes" must contain the complete file content, not a patch.""";

    private static final String SYSTEM_PROMPT_FOOTER = """
        ## Output Contract
        Respond ONLY with a valid JSON object matching the schema in the user message.
        No markdown fences, no preamble, no commentary outside the JSON.

        ## Quality Bar
        High-quality code generation:
        - Each changed file has been conceptually read before modification
        - No change outside the scope of the task
        - Tests exist for the new logic

        NEVER:
        - Generate code for a file you have not conceptually read in this context
        - Omit the migration when a database table changes
        - Write TODO comments without a ticket reference
        - Skip the test_changes array (use empty array [] if tests are not applicable)""";

    private static final String USER_TEMPLATE =
        "## Задача для реализации\n{task_summary}\n\n{task_description}\n\n" +
        "## Контекст кодовой базы\n{context}\n\n" +
        "## Связанные задачи\n{other_tasks}\n\n" +
        "## Согласованный технический подход\n{approved_approach}\n\n" +
        "Сгенерируй полные изменения кода необходимые для реализации этой задачи. Ответь ТОЛЬКО JSON:\n\n" +
        "{\n" +
        "  \"branch_name\": \"feature/<issue-id>-<краткое-описание>\",\n" +
        "  \"changes\": [\n" +
        "    {\n" +
        "      \"file_path\": \"src/path/to/file.py\",\n" +
        "      \"action\": \"create|modify|delete\",\n" +
        "      \"content\": \"<полное содержимое файла строкой>\",\n" +
        "      \"description\": \"<что делает это изменение>\"\n" +
        "    }\n" +
        "  ],\n" +
        "  \"commit_message\": \"feat: <краткое описание изменений>\",\n" +
        "  \"test_changes\": [...]\n" +
        "}";

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private KnowledgeBase knowledgeBase;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "code_generation";
    }

    @Override
    public String getDescription() {
        return "Для каждой задачи запрашивает контекст из базы знаний и генерирует полные изменения файлов, имя ветки и commit message.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        // Loopback context from verify/CI failure
        @SuppressWarnings("unchecked")
        Map<String, Object> loopback = (Map<String, Object>) input.get("_loopback");
        String loopbackSection = "";
        if (loopback != null) {
            int iteration = loopback.get("iteration") instanceof Number n ? n.intValue() : 0;
            List<?> issuesList = loopback.get("issues") instanceof List<?> l ? l : List.of();
            String feedback = issuesList.stream()
                .map(Object::toString)
                .map(s -> "- " + s)
                .reduce("", (a, b) -> a + "\n" + b).strip();
            String rec = loopback.getOrDefault("recommendation", "").toString();
            Object ciStagesObj = loopback.get("ci_stages");
            loopbackSection = "\n\n---\n\n## Повторная попытка (итерация " + (iteration + 1) + ")\n\n" +
                "Предыдущая генерация не прошла верификацию. Проблемы:\n" + feedback;
            if (!rec.isBlank()) loopbackSection += "\n\nРекомендация: " + rec;
            if (ciStagesObj != null) loopbackSection += "\n\nCI стадии при ошибке:\n" + ciStagesObj;
        }

        // Resolve tasks from youtrack_tasks or tasks key
        List<Map<String, Object>> tasks = new ArrayList<>();
        List<Map<String, Object>> youtrackIssues = new ArrayList<>();

        for (String key : new String[]{"youtrack_tasks", "tasks"}) {
            Object val = input.get(key);
            if (val instanceof Map) {
                Map<String, Object> tasksOutput = (Map<String, Object>) val;
                if (tasksOutput.get("tasks") instanceof List) {
                    tasks = (List<Map<String, Object>>) tasksOutput.get("tasks");
                }
                if (tasksOutput.get("youtrack_issues") instanceof List) {
                    youtrackIssues = (List<Map<String, Object>>) tasksOutput.get("youtrack_issues");
                }
                break;
            }
        }

        // Resolve approved_approach
        String approvedApproach = "";
        Object clarOutput = input.get("clarification");
        if (clarOutput instanceof Map) {
            approvedApproach = (String) ((Map<String, Object>) clarOutput).getOrDefault("approved_approach", "");
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

        // Build other tasks description
        StringBuilder otherTasksBuilder = new StringBuilder();
        for (Map<String, Object> task : tasks) {
            String ts = (String) task.getOrDefault("summary", "");
            otherTasksBuilder.append("- ").append(ts).append("\n");
        }
        String otherTasksText = otherTasksBuilder.toString().trim();

        // Merged output maps: file_path -> change (last writer wins)
        Map<String, Map<String, Object>> mergedChanges = new LinkedHashMap<>();
        Map<String, Map<String, Object>> mergedTestChanges = new LinkedHashMap<>();
        String branchName = "feature/generated";
        String commitMessage = "feat: generated changes";
        int tasksGenerated = 0;

        for (Map<String, Object> task : tasks) {
            String taskSummary = (String) task.getOrDefault("summary", "");
            String taskDescription = (String) task.getOrDefault("description", "");

            // Query knowledge base for context
            String context = "No additional context available.";
            String kbQuery = taskSummary + " " + taskDescription;
            if (!kbQuery.isBlank()) {
                try {
                    String kbResult = knowledgeBase.query(kbQuery.trim(), 5);
                    if (kbResult != null && !kbResult.isBlank()) {
                        context = kbResult;
                    }
                } catch (Exception e) {
                    log.warn("Knowledge base query failed for task '{}': {}", taskSummary, e.getMessage());
                }
            }

            String userMessage = USER_TEMPLATE
                .replace("{task_summary}", taskSummary)
                .replace("{task_description}", taskDescription != null ? taskDescription : "")
                .replace("{context}", context)
                .replace("{other_tasks}", otherTasksText.isBlank() ? "(нет других задач)" : otherTasksText)
                .replace("{approved_approach}", approvedApproach.isBlank() ? "(не указан)" : approvedApproach)
                + loopbackSection;

            String yamlPrompt = config.getAgent() != null ? config.getAgent().getSystemPrompt() : null;
            String effectiveSystemPrompt = AgentConfig.buildSystemPrompt(
                SYSTEM_PROMPT_HEADER, yamlPrompt, SYSTEM_PROMPT_FOOTER);

            String llmResponse;
            try {
                llmResponse = llmClient.complete(model, effectiveSystemPrompt, userMessage, maxTokens, temperature);
            } catch (Exception e) {
                log.error("LLM call failed for task '{}': {}", taskSummary, e.getMessage());
                continue;
            }

            Map<String, Object> parsed;
            try {
                parsed = objectMapper.readValue(llmResponse, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.error("Failed to parse code generation JSON for task '{}': {}", taskSummary, e.getMessage());
                continue;
            }

            tasksGenerated++;

            // Take branch name and commit from first successful task
            if ("feature/generated".equals(branchName)) {
                String parsedBranch = (String) parsed.getOrDefault("branch_name", "");
                if (!parsedBranch.isBlank()) {
                    branchName = sanitizeBranchName(parsedBranch);
                }
                String parsedCommit = (String) parsed.getOrDefault("commit_message", "");
                if (!parsedCommit.isBlank()) {
                    commitMessage = parsedCommit;
                }
            }

            // Merge changes (last writer wins)
            if (parsed.get("changes") instanceof List) {
                for (Object changeObj : (List<?>) parsed.get("changes")) {
                    if (changeObj instanceof Map) {
                        Map<String, Object> change = (Map<String, Object>) changeObj;
                        String filePath = (String) change.getOrDefault("file_path", "");
                        if (!filePath.isBlank()) {
                            mergedChanges.put(filePath, change);
                        }
                    }
                }
            }

            // Merge test changes (last writer wins)
            if (parsed.get("test_changes") instanceof List) {
                for (Object changeObj : (List<?>) parsed.get("test_changes")) {
                    if (changeObj instanceof Map) {
                        Map<String, Object> change = (Map<String, Object>) changeObj;
                        String filePath = (String) change.getOrDefault("file_path", "");
                        if (!filePath.isBlank()) {
                            mergedTestChanges.put(filePath, change);
                        }
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("branch_name", branchName);
        result.put("changes", new ArrayList<>(mergedChanges.values()));
        result.put("test_changes", new ArrayList<>(mergedTestChanges.values()));
        result.put("commit_message", commitMessage);
        result.put("tasks_generated", tasksGenerated);
        result.put("youtrack_issues", youtrackIssues);
        return result;
    }

    private String sanitizeBranchName(String name) {
        String sanitized = name.toLowerCase()
            .replaceAll("[^a-z0-9/_-]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-+|-+$", "");
        return sanitized.isBlank() ? "feature/generated" : sanitized;
    }
}
