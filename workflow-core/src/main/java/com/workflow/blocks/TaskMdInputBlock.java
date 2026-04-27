package com.workflow.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.core.expr.StringInterpolator;
import com.workflow.model.IntegrationConfig;
import com.workflow.model.IntegrationConfigRepository;
import com.workflow.model.IntegrationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a task.md file and extracts structured sections. Expected filename
 * convention is {@code <FEAT_ID>_<slug>.md}. Sections parsed:
 *
 * <ul>
 *   <li>{@code feat_id}, {@code slug} — parsed from the filename
 *       {@code <feat_id>_<slug>.md} (case preserved).</li>
 *   <li>{@code title} — first {@code #} heading in the body, if any.</li>
 *   <li>{@code as_is} / {@code to_be} / {@code out_of_scope} / {@code acceptance}
 *       — bodies of the Russian sections {@code ## Как сейчас}, {@code ## Как надо},
 *       {@code ## Вне scope}, {@code ## Критерии приёмки}. Missing sections resolve
 *       to empty strings (but downstream blocks using {@code ${task_md.xxx}} will
 *       get "" only if they reach a present-but-empty value — PathResolver still
 *       throws on a missing field).</li>
 *   <li>{@code body} — the raw file contents, so downstream prompts can attach the
 *       whole thing as context.</li>
 *   <li>Five heuristic booleans — {@code needs_contract_change}, {@code needs_server},
 *       {@code needs_client}, {@code needs_bp}, {@code is_greenfield} — from keyword
 *       presence in the body.</li>
 * </ul>
 *
 * <p>YAML shape:
 * <pre>
 * - id: task_md
 *   block: task_md_input
 *   config:
 *     file_path: "${input.task_file}"   # or literal absolute path
 * </pre>
 */
@Component
public class TaskMdInputBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(TaskMdInputBlock.class);

    // Matches FEAT-ID-001-slug-words.md — feat_id ends at the last numeric segment
    private static final Pattern FILENAME = Pattern.compile(
        "^(?<featId>[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-\\d+)-(?<slug>[^.]+)\\.md$");
    // Legacy underscore separator: FEAT_ID_slug.md
    private static final Pattern FILENAME_UNDERSCORE = Pattern.compile(
        "^(?<featId>[A-Z0-9][A-Z0-9\\-]*)_(?<slug>[^.]+)\\.md$");
    private static final Pattern H1 = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern FRONTMATTER_TITLE = Pattern.compile(
        "^---\\s*\\R(?:.*\\R)*?title:\\s*(.+?)\\s*\\R", Pattern.MULTILINE);

    /** Canonical section headings — exact match on the {@code ## Heading} line. */
    private static final Map<String, String> SECTION_KEYS = Map.of(
        "как сейчас", "as_is",
        "как надо", "to_be",
        "вне scope", "out_of_scope",
        "критерии приёмки", "acceptance"
    );

    /** Standard heuristic flag names always present in output (default false). */
    private static final List<String> HEURISTIC_FLAGS = List.of(
        "needs_bp", "needs_server", "needs_client", "needs_contract_change"
    );

    @Autowired(required = false) private StringInterpolator stringInterpolator;
    @Autowired(required = false) private IntegrationConfigRepository integrationConfigRepository;
    @Autowired(required = false) private ObjectMapper objectMapper;

    @Override public String getName() { return "task_md_input"; }

    @Override public String getDescription() {
        return "Парсит файл task.md на структурированные секции, извлекает feat_id/slug/title и проставляет эвристические флаги классификации.";
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig blockConfig, PipelineRun run) throws Exception {
        Map<String, Object> cfg = blockConfig.getConfig() != null ? blockConfig.getConfig() : Map.of();

        Object rawPath = cfg.get("file_path");
        if (rawPath == null || rawPath.toString().isBlank()) {
            throw new IllegalArgumentException("task_md_input: config.file_path is required");
        }
        String filePath = stringInterpolator != null
            ? stringInterpolator.interpolate(rawPath.toString(), run, input)
            : rawPath.toString();
        Path path = Paths.get(filePath).toAbsolutePath();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("task_md_input: file not found: " + path);
        }

        String body = Files.readString(path, StandardCharsets.UTF_8);
        String filename = path.getFileName().toString();

        Map<String, Object> out = new LinkedHashMap<>();
        Matcher fm = FILENAME.matcher(filename);
        if (!fm.matches()) fm = FILENAME_UNDERSCORE.matcher(filename);
        if (fm.matches()) {
            out.put("feat_id", fm.group("featId"));
            out.put("slug", fm.group("slug"));
        } else {
            log.warn("task_md_input: filename '{}' does not match expected pattern — feat_id/slug left empty",
                filename);
            out.put("feat_id", "");
            out.put("slug", filename.replaceFirst("\\.md$", ""));
        }

        // Prefer YAML frontmatter title, fall back to first H1 heading
        Matcher ftm = FRONTMATTER_TITLE.matcher(body);
        if (ftm.find()) {
            out.put("title", ftm.group(1).trim());
        } else {
            Matcher hm = H1.matcher(body);
            out.put("title", hm.find() ? hm.group(1).trim() : "");
        }
        out.put("body", body);

        Map<String, String> sections = extractSections(body);
        for (Map.Entry<String, String> e : SECTION_KEYS.entrySet()) {
            out.put(e.getValue(), sections.getOrDefault(e.getValue(), ""));
        }

        applyHeuristics(body, out);

        log.info("task_md_input: feat_id={} slug={} sections={} heuristics={}",
            out.get("feat_id"), out.get("slug"), sectionKeysFound(sections),
            activeHeuristics(out));

        return out;
    }

    /**
     * Split on {@code ## Heading} lines and map known Russian headings to canonical
     * output keys. Unknown headings are skipped (we deliberately ignore them rather
     * than inventing keys that downstream blocks cannot reference reliably).
     */
    private Map<String, String> extractSections(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] lines = body.split("\\R");
        String currentKey = null;
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (currentKey != null) {
                    out.put(currentKey, current.toString().strip());
                }
                String heading = line.substring(3).trim().toLowerCase(Locale.ROOT);
                currentKey = SECTION_KEYS.get(heading);
                current = new StringBuilder();
            } else if (currentKey != null) {
                current.append(line).append('\n');
            }
        }
        if (currentKey != null) {
            out.put(currentKey, current.toString().strip());
        }
        return out;
    }

    private void applyHeuristics(String body, Map<String, Object> out) {
        String lower = body.toLowerCase(Locale.ROOT);
        // Always emit all standard flags so downstream conditions never hit PathNotFoundException.
        HEURISTIC_FLAGS.forEach(flag -> out.put(flag, false));
        Map<String, List<String>> keywords = loadHeuristicKeywords();
        for (Map.Entry<String, List<String>> e : keywords.entrySet()) {
            boolean match = e.getValue().stream().anyMatch(lower::contains);
            out.put(e.getKey(), match);
        }
        boolean anyNeed = HEURISTIC_FLAGS.stream().anyMatch(k -> Boolean.TRUE.equals(out.get(k)));
        boolean hasFilePaths = lower.contains("/src/") || lower.contains("\\src\\")
            || lower.contains(".cpp") || lower.contains(".h") || lower.contains(".java");
        out.put("is_greenfield", !anyNeed && !hasFilePaths);
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> loadHeuristicKeywords() {
        if (integrationConfigRepository == null) return Map.of();
        try {
            Optional<IntegrationConfig> cfg = integrationConfigRepository
                .findByTypeAndIsDefaultTrue(IntegrationType.UNREAL);
            if (cfg.isEmpty()) {
                cfg = integrationConfigRepository.findByType(IntegrationType.UNREAL).stream().findFirst();
            }
            if (cfg.isEmpty() || cfg.get().getExtraConfigJson() == null) return Map.of();
            ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
            Map<String, Object> extra = mapper.readValue(
                cfg.get().getExtraConfigJson(), new TypeReference<>() {});
            Object kws = extra.get("heuristicKeywords");
            if (kws instanceof Map<?, ?> m) {
                return (Map<String, List<String>>) m;
            }
        } catch (Exception e) {
            // UNREAL integration not configured or schema constraint mismatch — use defaults
            log.debug("task_md_input: UNREAL heuristic keywords unavailable: {}", e.getMessage());
        }
        return Map.of();
    }

    private List<String> sectionKeysFound(Map<String, String> sections) {
        return sections.entrySet().stream()
            .filter(e -> !e.getValue().isBlank())
            .map(Map.Entry::getKey).toList();
    }

    private List<String> activeHeuristics(Map<String, Object> out) {
        return out.entrySet().stream()
            .filter(e -> e.getValue() instanceof Boolean b && b)
            .map(Map.Entry::getKey).toList();
    }
}
