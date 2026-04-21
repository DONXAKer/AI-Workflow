package com.workflow.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.PipelineConfig;
import com.workflow.config.PipelineConfigLoader;
import com.workflow.llm.LlmClient;
import com.workflow.project.Project;
import com.workflow.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses freeform user input (URLs, stack traces, free text) and scans the project folder
 * to suggest the most appropriate pipeline entry point.
 *
 * <p>Three-stage pipeline:
 * <ol>
 *   <li>Regex parse of structured tokens (tracker URLs/IDs, stack traces)</li>
 *   <li>Java file-system scan for project facts (build system, tests, tasks, git state)</li>
 *   <li>LLM classification of intent (bug_fix | feature | code_review)</li>
 * </ol>
 */
@Service
public class SmartDetectService {

    private static final Logger log = LoggerFactory.getLogger(SmartDetectService.class);

    // --- regex patterns ---
    private static final Pattern YT_URL   = Pattern.compile("https?://[^/\\s]+/issue/([A-Z]+-\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern YT_ID    = Pattern.compile("^[A-Z]+-\\d+$");
    private static final Pattern GH_ISSUE = Pattern.compile("https?://github\\.com/([^/]+)/([^/]+)/issues/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GH_PR    = Pattern.compile("https?://github\\.com/([^/]+)/([^/]+)/pull/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GL_MR    = Pattern.compile("https?://[^/\\s]+/.*/-/merge_requests/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GL_ISSUE = Pattern.compile("https?://[^/\\s]+/.*/-/issues/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JIRA     = Pattern.compile("https?://[^/\\s]+/browse/([A-Z]+-\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STACKTRACE = Pattern.compile("\\bat\\s+[\\w$.]+\\([\\w$.]+\\.java:\\d+\\)", Pattern.MULTILINE);

    @Autowired private LlmClient llmClient;
    @Autowired private EntryPointResolver entryPointResolver;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private PipelineConfigLoader pipelineConfigLoader;
    @Autowired private ObjectMapper objectMapper;

    @Value("${workflow.smart-detect.model:google/gemini-flash-1.5}")
    private String detectModel;

    @Value("${workflow.smart-detect.confidence-threshold:0.7}")
    private double confidenceThreshold;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Analyses rawInput + project workspace and returns a suggested entry point.
     *
     * @param rawInput    whatever the user typed (URL, issue ID, stack trace, free text)
     * @param configPath  path to the pipeline YAML (used for VCS detection); may be null
     * @param projectSlug current project slug (from X-Project-Slug header)
     * @return detection result as a plain Map (serialised directly to JSON by the controller)
     */
    public Map<String, Object> detect(String rawInput, String configPath, String projectSlug) {
        ParsedInput parsed = parseInput(rawInput);
        log.info("smart-detect: input={} parsedType={} project={}", abbreviate(rawInput), parsed.type(), projectSlug);

        ProjectFacts facts = scanProject(projectSlug);

        // VCS state — only when we have a tracker issue ID and a pipeline config
        EntryPointResolver.DetectionResult vcsState = null;
        if (parsed.type() == InputType.YOUTRACK && parsed.id() != null && configPath != null) {
            try {
                PipelineConfig config = pipelineConfigLoader.load(Path.of(configPath));
                vcsState = entryPointResolver.autoDetect(parsed.id(), config);
                log.debug("smart-detect VCS state: {}", vcsState.suggestedEntryPointId());
            } catch (Exception e) {
                log.debug("smart-detect VCS auto-detect skipped: {}", e.getMessage());
            }
        }

        IntentResult intent = classifyIntent(rawInput, parsed, facts);
        String entryPointId = mapToEntryPoint(intent.intent(), vcsState, facts);

        String clarificationQuestion = intent.confidence() < confidenceThreshold
            ? intent.clarificationQuestion() : null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggested", Map.of(
            "entryPointId", entryPointId,
            "confidence",   intent.confidence(),
            "intentLabel",  intent.intent().label()
        ));
        result.put("explanation", buildExplanation(parsed, intent, vcsState, facts));
        result.put("detectedInputs", buildDetectedInputs(parsed, vcsState));
        if (clarificationQuestion != null) {
            result.put("clarificationQuestion", clarificationQuestion);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Step 1: input parsing
    // -------------------------------------------------------------------------

    private ParsedInput parseInput(String raw) {
        if (raw == null || raw.isBlank()) return new ParsedInput(InputType.FREETEXT, raw, null, null, null);
        String t = raw.trim();

        Matcher m;

        m = JIRA.matcher(t);
        if (m.find()) return new ParsedInput(InputType.JIRA, t, m.group(1), null, null);

        m = YT_URL.matcher(t);
        if (m.find()) return new ParsedInput(InputType.YOUTRACK, t, m.group(1), null, null);

        if (YT_ID.matcher(t).matches()) return new ParsedInput(InputType.YOUTRACK, t, t, null, null);

        m = GH_PR.matcher(t);
        if (m.find()) return new ParsedInput(InputType.GITHUB_PR, t, null, m.group(1) + "/" + m.group(2), m.group(3));

        m = GH_ISSUE.matcher(t);
        if (m.find()) return new ParsedInput(InputType.GITHUB_ISSUE, t, null, m.group(1) + "/" + m.group(2), m.group(3));

        m = GL_MR.matcher(t);
        if (m.find()) return new ParsedInput(InputType.GITLAB_MR, t, null, null, m.group(1));

        m = GL_ISSUE.matcher(t);
        if (m.find()) return new ParsedInput(InputType.GITLAB_ISSUE, t, null, null, m.group(1));

        if (STACKTRACE.matcher(t).find()) return new ParsedInput(InputType.STACKTRACE, t, null, null, null);

        return new ParsedInput(InputType.FREETEXT, t, null, null, null);
    }

    // -------------------------------------------------------------------------
    // Step 2: project scan
    // -------------------------------------------------------------------------

    private ProjectFacts scanProject(String projectSlug) {
        if (projectSlug == null) return ProjectFacts.EMPTY;
        Optional<Project> projectOpt = projectRepository.findBySlug(projectSlug);
        if (projectOpt.isEmpty() || projectOpt.get().getWorkingDir() == null) return ProjectFacts.EMPTY;

        Path workDir = Path.of(projectOpt.get().getWorkingDir());
        if (!Files.isDirectory(workDir)) return ProjectFacts.EMPTY;

        BuildSystem bs = detectBuildSystem(workDir);
        boolean hasTests        = detectHasTests(workDir);
        boolean hasActiveTasks  = Files.isDirectory(workDir.resolve("tasks/active")) &&
                                  countMdFiles(workDir.resolve("tasks/active")) > 0;
        boolean uncommitted     = detectUncommittedChanges(workDir);
        String  branch          = detectCurrentBranch(workDir);

        return new ProjectFacts(bs, hasTests, hasActiveTasks, uncommitted, branch);
    }

    private BuildSystem detectBuildSystem(Path dir) {
        if (Files.exists(dir.resolve("pom.xml")))                   return BuildSystem.MAVEN;
        if (Files.exists(dir.resolve("build.gradle")) ||
            Files.exists(dir.resolve("build.gradle.kts")))          return BuildSystem.GRADLE;
        if (Files.exists(dir.resolve("package.json")))              return BuildSystem.NODE;
        if (Files.exists(dir.resolve("requirements.txt")) ||
            Files.exists(dir.resolve("pyproject.toml")))            return BuildSystem.PYTHON;
        return BuildSystem.UNKNOWN;
    }

    private boolean detectHasTests(Path dir) {
        try {
            return Files.walk(dir, 6)
                .limit(500)
                .anyMatch(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith("Test.java") || n.endsWith("Tests.java")
                        || n.endsWith(".spec.ts")  || n.endsWith(".test.ts")
                        || n.endsWith("_test.py")  || n.startsWith("test_") && n.endsWith(".py");
                });
        } catch (IOException e) {
            return false;
        }
    }

    private long countMdFiles(Path dir) {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".md")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private boolean detectUncommittedChanges(Path dir) {
        try {
            Process p = new ProcessBuilder("git", "status", "--porcelain")
                .directory(dir.toFile()).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            return !out.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private String detectCurrentBranch(Path dir) {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(dir.toFile()).start();
            return new String(p.getInputStream().readAllBytes()).trim();
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Step 3: LLM intent classification
    // -------------------------------------------------------------------------

    private IntentResult classifyIntent(String rawInput, ParsedInput parsed, ProjectFacts facts) {
        String systemPrompt = """
            You are a software workflow router. Given the user's input and project facts, classify the intent.

            Respond ONLY with a JSON object — no markdown, no explanation:
            {
              "intent": "bug_fix" | "feature" | "code_review",
              "confidence": <0.0-1.0>,
              "clarification_question": "<question>" | null
            }

            Definitions:
            - bug_fix: user reports an error, exception, broken behavior, or provides a stack trace
            - feature: user wants new functionality, an enhancement, or describes something to build
            - code_review: user provides a PR/MR/branch URL or wants to review existing code

            Set clarification_question when confidence < 0.7.
            """;

        String userPrompt = String.format("""
            User input: %s

            Parsed token type: %s
            Build system: %s
            Has test files: %s
            Has active task files: %s
            Has uncommitted git changes: %s
            Current git branch: %s
            """,
            rawInput,
            parsed.type().name(),
            facts.buildSystem().name(),
            facts.hasTests(),
            facts.hasActiveTasks(),
            facts.hasUncommittedChanges(),
            facts.currentBranch() != null ? facts.currentBranch() : "unknown"
        );

        try {
            String response = llmClient.complete(detectModel, systemPrompt, userPrompt, 256, 0.1);
            JsonNode json = objectMapper.readTree(response);

            String intentStr = json.path("intent").asText("feature");
            double confidence = json.path("confidence").asDouble(0.5);
            String clarification = json.path("clarification_question").isNull()
                ? null : json.path("clarification_question").asText(null);

            Intent intent = switch (intentStr) {
                case "bug_fix"     -> Intent.BUG_FIX;
                case "code_review" -> Intent.CODE_REVIEW;
                default            -> Intent.FEATURE;
            };
            return new IntentResult(intent, confidence, clarification);

        } catch (Exception e) {
            log.warn("smart-detect LLM classification failed ({}), using heuristic fallback", e.getMessage());
            return heuristicFallback(parsed);
        }
    }

    private IntentResult heuristicFallback(ParsedInput parsed) {
        return switch (parsed.type()) {
            case STACKTRACE   -> new IntentResult(Intent.BUG_FIX,    0.6, null);
            case GITLAB_MR,
                 GITHUB_PR    -> new IntentResult(Intent.CODE_REVIEW, 0.6, null);
            default           -> new IntentResult(Intent.FEATURE,     0.5,
                "Не удалось классифицировать автоматически. Это баг, фича или код-ревью?");
        };
    }

    // -------------------------------------------------------------------------
    // Step 4: map intent + VCS state → entry point
    // -------------------------------------------------------------------------

    private String mapToEntryPoint(Intent intent, EntryPointResolver.DetectionResult vcs, ProjectFacts facts) {
        if (intent == Intent.CODE_REVIEW) {
            if (vcs != null && "mr_open".equals(vcs.suggestedEntryPointId())) return "mr_open";
            if (vcs != null && "branch_exists".equals(vcs.suggestedEntryPointId())) return "branch_exists";
        }
        if (vcs != null) {
            String v = vcs.suggestedEntryPointId();
            if (v != null && !v.equals("from_scratch")) return v;
        }
        if (facts.hasActiveTasks()) return "tasks_exist";
        return "from_scratch";
    }

    // -------------------------------------------------------------------------
    // Response helpers
    // -------------------------------------------------------------------------

    private String buildExplanation(ParsedInput parsed, IntentResult intent,
                                    EntryPointResolver.DetectionResult vcs, ProjectFacts facts) {
        var parts = new ArrayList<String>();
        parts.add(switch (parsed.type()) {
            case YOUTRACK     -> "Найдена задача YouTrack " + parsed.id();
            case JIRA         -> "Найдена задача Jira " + parsed.id();
            case GITHUB_ISSUE -> "Найден GitHub Issue";
            case GITHUB_PR    -> "Найден GitHub PR";
            case GITLAB_MR    -> "Найден GitLab MR";
            case GITLAB_ISSUE -> "Найден GitLab Issue";
            case STACKTRACE   -> "Обнаружен стектрейс";
            default           -> "Свободный текст";
        });
        if (vcs != null && vcs.suggestedEntryPointId() != null) {
            parts.add("VCS: " + vcs.suggestedEntryPointId().replace("_", " "));
        }
        if (facts.hasActiveTasks()) parts.add("есть активные задачи");
        parts.add("определён intent: " + intent.intent().label());
        return String.join(", ", parts);
    }

    private Map<String, Object> buildDetectedInputs(ParsedInput parsed,
                                                     EntryPointResolver.DetectionResult vcs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inputType", parsed.type().name().toLowerCase());
        if (parsed.id()   != null) m.put("issueId",  parsed.id());
        if (parsed.repo() != null) m.put("repo",     parsed.repo());
        if (parsed.num()  != null) m.put("number",   parsed.num());
        if (vcs != null && vcs.detected() != null) m.put("vcs", vcs.detected());
        return m;
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    enum InputType {
        YOUTRACK, JIRA, GITHUB_ISSUE, GITHUB_PR, GITLAB_MR, GITLAB_ISSUE, STACKTRACE, FREETEXT
    }

    enum BuildSystem { MAVEN, GRADLE, NODE, PYTHON, UNKNOWN }

    enum Intent {
        BUG_FIX("баг-фикс"), FEATURE("фича"), CODE_REVIEW("код-ревью");

        private final String label;
        Intent(String label) { this.label = label; }
        public String label() { return label; }
    }

    record ParsedInput(InputType type, String raw, String id, String repo, String num) {}

    record ProjectFacts(BuildSystem buildSystem, boolean hasTests, boolean hasActiveTasks,
                        boolean hasUncommittedChanges, String currentBranch) {
        static final ProjectFacts EMPTY =
            new ProjectFacts(BuildSystem.UNKNOWN, false, false, false, null);
    }

    record IntentResult(Intent intent, double confidence, String clarificationQuestion) {}
}
