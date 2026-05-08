package com.workflow.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.AgentConfig;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.core.expr.StringInterpolator;
import com.workflow.knowledge.KnowledgeBase;
import com.workflow.llm.LlmClient;
import com.workflow.project.Project;
import com.workflow.project.ProjectClaudeMd;
import com.workflow.project.ProjectContext;
import com.workflow.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AnalysisBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(AnalysisBlock.class);

    private static final String SYSTEM_PROMPT_HEADER = """
        You are a Staff Software Architect with 15+ years of experience designing and delivering \
        large-scale production systems. You are the first line of defence against vague requirements \
        and hidden technical risk.

        ## Core Task
        Analyse the incoming requirement, identify all affected system components, assess technical \
        approaches and their trade-offs, and surface risks and open questions the team must resolve \
        before implementation begins.

        ## Best Practices
        1. Always read the requirement twice — once for scope, once for hidden assumptions.
        2. List affected components specifically (e.g. "UserController, AuthService, users table") — never vague terms like "backend".
        3. Propose at least two technical approaches and compare them explicitly.
        4. Estimate complexity honestly: prefer "high" over "medium" when uncertain.
        5. Surface security and data-privacy implications (auth changes, PII handling, external integrations).
        6. Open questions must be specific and actionable — not "investigate further" but "confirm whether X requires Y".
        7. If the requirement touches a database schema, note migration strategy.""";

    private static final String SYSTEM_PROMPT_FOOTER = """
        ## Output Contract
        Respond ONLY with a valid JSON object matching the schema in the user message.
        No markdown fences, no preamble, no commentary outside the JSON.

        ## Quality Bar
        A high-quality analysis:
        - Names concrete files/classes/tables, not vague layers
        - Includes at least one non-obvious risk
        - Has actionable open questions (none when the requirement is fully specified)

        ## Acceptance Checklist Rules (CRITICAL)
        The `acceptance_checklist` is the single source of truth for what "done" means.
        - Each item must be a concrete, binary check (PASS/FAIL), not a vague goal.
        - For every explicit acceptance criterion in the requirement, emit one item with \
          `source: "requirement"`. Quote the original phrasing closely — do not paraphrase \
          to the point of losing the requirement's intent.
        - Add `source: "derived"` items for things the requirement implies but does not state \
          (edge cases, error handling, tests for new code, security checks, migration safety). \
          Soft cap: derived items ≤ 1.5× the count of requirement items. If the requirement \
          is very thin (1-2 items only), emit only the 2-3 most critical derived items and \
          set `needs_clarification: true`.
        - Set `priority`:
          - `critical` — feature unusable / data loss / security broken without it.
          - `important` — should be done; missing it is a quality gap.
          - `nice_to_have` — bonus polish, OK to skip on tight deadlines.
        - Item ids: short kebab-case (`a1`, `auth-token-refresh`, `migration-rollback`).
        - Set `needs_clarification: true` when fewer than 2 requirement-source items can be \
          extracted, OR when complexity is `high` and key trade-offs are ambiguous. The \
          downstream `clarification` block uses this flag to decide whether to ask the \
          operator follow-up questions before locking the checklist.

        NEVER:
        - Return an analysis without specifying at least one affected component
        - Return an empty acceptance_checklist
        - Mark every item as critical (priority discrimination is mandatory)
        - Write "investigate further" as an open question — replace it with the specific thing to investigate
        - Skip complexity estimation""";

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
        "  \"open_questions\": [\"<вопрос1>\", \"<вопрос2>\"],\n" +
        "  \"acceptance_checklist\": [\n" +
        "    {\n" +
        "      \"id\": \"<короткий kebab-case id>\",\n" +
        "      \"text\": \"<конкретный бинарный пункт приёмки>\",\n" +
        "      \"source\": \"requirement|derived\",\n" +
        "      \"priority\": \"critical|important|nice_to_have\"\n" +
        "    }\n" +
        "  ],\n" +
        "  \"needs_clarification\": <true|false>\n" +
        "}\n\n" +
        "Не включай никакого текста за пределами JSON объекта.";

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private KnowledgeBase knowledgeBase;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private ProjectRepository projectRepository;

    @Autowired(required = false)
    private StringInterpolator stringInterpolator;

    @Override
    public String getName() {
        return "analysis";
    }

    @Override
    public String getDescription() {
        return "Глубоко анализирует требование, определяет затронутые компоненты, технический подход, сложность, риски и открытые вопросы.";
    }

    @Override
    public BlockMetadata getMetadata() {
        // analysis-блок не имеет настраиваемых config-полей: всё управляется через
        // agent (model/temperature/systemPrompt) и через текст требования.
        return new BlockMetadata(
            "Analysis",
            "agent",
            Phase.ANALYZE,
            List.of(),
            false,
            Map.of(),
            List.of(
                FieldSchema.output("summary", "Summary", "string",
                    "Краткое резюме того, что нужно построить."),
                FieldSchema.output("affected_components", "Affected components", "string_array",
                    "Список затронутых компонентов (классы / таблицы / сервисы)."),
                FieldSchema.output("technical_approach", "Technical approach", "string",
                    "Рекомендуемый технический подход с обоснованием."),
                FieldSchema.output("estimated_complexity", "Estimated complexity", "enum",
                    "Оценка сложности: low | medium | high."),
                FieldSchema.output("risks", "Risks", "string_array",
                    "Список выявленных рисков."),
                FieldSchema.output("open_questions", "Open questions", "string_array",
                    "Открытые вопросы, требующие решения до реализации."),
                FieldSchema.output("acceptance_checklist", "Acceptance checklist", "string_array",
                    "Структурированный чек-лист приёмки (id/text/source/priority)."),
                FieldSchema.output("needs_clarification", "Needs clarification", "boolean",
                    "Флаг: требуется ли уточнение требования у оператора.")
            ),
            100
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) throws Exception {
        String requirement = (String) input.getOrDefault("requirement", "");

        // If task_md output is available (via depends_on), use its body as the requirement.
        // This avoids the LLM analysing a file path instead of the actual task content.
        if (input.get("task_md") instanceof Map<?, ?> taskMd) {
            Object body = taskMd.get("body");
            if (body instanceof String s && !s.isBlank()) requirement = s;
        }

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

        // Prepend CLAUDE.md from the target project so analysis sees the
        // codebase's own conventions (build quirks, package layout, etc.)
        // without the operator hardcoding them into system_prompt.
        String claudeMd = ProjectClaudeMd.readForCurrentProject(projectRepository);
        if (!claudeMd.isEmpty()) {
            context = claudeMd + "\n---\n\n" + context;
        }

        // Determine model. Default tier is "smart" — analysis is an analytical role
        // (per smart-checklist design); operator can override via agent.model or agent.tier.
        String model = "smart";
        int maxTokens = 8192;
        double temperature = 1.0;
        if (config.getAgent() != null) {
            String effective = config.getAgent().getEffectiveModel();
            if (effective != null) {
                model = effective;
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

        String yamlPrompt = config.getAgent() != null ? config.getAgent().getSystemPrompt() : null;
        if (yamlPrompt != null && stringInterpolator != null) {
            Path wd = resolveWorkingDir();
            List<String> extraAllow = config.getAgent() != null ? config.getAgent().getPromptContextAllow() : null;
            yamlPrompt = stringInterpolator.interpolate(yamlPrompt, run, input, wd, extraAllow);
        }
        String effectiveSystemPrompt = AgentConfig.buildSystemPrompt(
            SYSTEM_PROMPT_HEADER, yamlPrompt, SYSTEM_PROMPT_FOOTER);

        String response = llmClient.complete(model, effectiveSystemPrompt, userMessage, maxTokens, temperature);

        Map<String, Object> result;
        try {
            String json = response.strip();
            // Strip markdown code fences: ```json ... ``` or ``` ... ```
            if (json.startsWith("```")) {
                int start = json.indexOf('\n');
                int end   = json.lastIndexOf("```");
                if (start > 0 && end > start) json = json.substring(start + 1, end).strip();
            }
            // Fix invalid JSON escapes produced by some models (e.g. \` is not valid JSON)
            json = json.replace("\\`", "`");
            try {
                result = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            } catch (Exception parseEx) {
                // Two common failure modes:
                // 1. Raw control chars (newlines, tabs) inside JSON strings — Gemini does this.
                // 2. LLM wraps JSON in prose ("Вот анализ:\n{...}\nГотово") — Sonnet sometimes does this.
                // Strategy: extract the outermost {...} substring (covers case 2), then try lenient
                // parser that allows unescaped control chars (covers case 1).
                String candidate = json;
                int firstBrace = json.indexOf('{');
                int lastBrace = json.lastIndexOf('}');
                if (firstBrace >= 0 && lastBrace > firstBrace) {
                    candidate = json.substring(firstBrace, lastBrace + 1);
                }
                com.fasterxml.jackson.core.JsonFactory lf = new com.fasterxml.jackson.core.JsonFactory();
                lf.configure(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true);
                result = new ObjectMapper(lf).readValue(candidate, new TypeReference<Map<String, Object>>() {});
            }
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
        result.putIfAbsent("acceptance_checklist", new ArrayList<>());
        result.putIfAbsent("needs_clarification", false);

        normalizeChecklist(result);

        return result;
    }

    /**
     * Defends against malformed checklist items from the LLM: missing ids get
     * synthesized (a1, a2, ...), missing source/priority get conservative defaults
     * ("derived" / "important"). Items that aren't a Map at all are dropped.
     */
    @SuppressWarnings("unchecked")
    private void normalizeChecklist(Map<String, Object> result) {
        Object raw = result.get("acceptance_checklist");
        if (!(raw instanceof List<?> rawList)) return;

        List<Map<String, Object>> normalized = new ArrayList<>();
        int autoId = 1;
        for (Object o : rawList) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> item = new HashMap<>((Map<String, Object>) m);
            Object id = item.get("id");
            if (!(id instanceof String s) || s.isBlank()) {
                item.put("id", "a" + autoId++);
            }
            item.putIfAbsent("source", "derived");
            item.putIfAbsent("priority", "important");
            if (item.get("text") == null) item.put("text", "");
            normalized.add(item);
        }
        result.put("acceptance_checklist", normalized);
    }

    private Path resolveWorkingDir() {
        if (projectRepository == null) return null;
        String slug = ProjectContext.get();
        if (slug == null || slug.isBlank()) return null;
        return projectRepository.findBySlug(slug)
            .map(Project::getWorkingDir)
            .filter(wd -> wd != null && !wd.isBlank())
            .map(Path::of)
            .orElse(null);
    }
}
