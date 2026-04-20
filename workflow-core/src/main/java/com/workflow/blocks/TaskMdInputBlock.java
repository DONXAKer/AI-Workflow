package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.core.expr.StringInterpolator;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a task.md file and extracts structured sections commonly used in
 * WarCard-style feature tickets:
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

    private static final Pattern FILENAME = Pattern.compile("^(?<featId>[A-Z0-9][A-Z0-9\\-]*)_(?<slug>[^.]+)\\.md$");
    private static final Pattern H1 = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);

    /** Canonical section headings — exact match on the {@code ## Heading} line. */
    private static final Map<String, String> SECTION_KEYS = Map.of(
        "как сейчас", "as_is",
        "как надо", "to_be",
        "вне scope", "out_of_scope",
        "критерии приёмки", "acceptance"
    );

    private static final Map<String, List<String>> HEURISTIC_KEYWORDS = Map.of(
        "needs_contract_change", List.of("ustruct", "contract", "proto", "message schema", "payload"),
        "needs_server",          List.of("server", "gameplay", "backend", "authoritative"),
        "needs_client",          List.of("client", "ui", "widget", "hud"),
        "needs_bp",              List.of("blueprint", "umg", " bp ", "bp_")
    );

    @Autowired(required = false) private StringInterpolator stringInterpolator;

    @Override public String getName() { return "task_md_input"; }

    @Override public String getDescription() {
        return "Parses a task.md file (WarCard convention) into structured sections, "
            + "extracts feat_id/slug/title, and sets heuristic classification flags.";
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
        if (fm.matches()) {
            out.put("feat_id", fm.group("featId"));
            out.put("slug", fm.group("slug"));
        } else {
            log.warn("task_md_input: filename '{}' does not match <FEAT_ID>_<slug>.md — feat_id/slug left empty",
                filename);
            out.put("feat_id", "");
            out.put("slug", filename.replaceFirst("\\.md$", ""));
        }

        Matcher hm = H1.matcher(body);
        out.put("title", hm.find() ? hm.group(1).trim() : "");
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
        for (Map.Entry<String, List<String>> e : HEURISTIC_KEYWORDS.entrySet()) {
            boolean match = e.getValue().stream().anyMatch(lower::contains);
            out.put(e.getKey(), match);
        }
        // Greenfield: none of the "needs_*" are set AND the body has no explicit
        // file paths — a rough cut sufficient for routing.
        boolean anyNeed = HEURISTIC_KEYWORDS.keySet().stream()
            .anyMatch(k -> Boolean.TRUE.equals(out.get(k)));
        boolean hasFilePaths = lower.contains("/src/") || lower.contains("\\src\\")
            || lower.contains(".cpp") || lower.contains(".h") || lower.contains(".java");
        out.put("is_greenfield", !anyNeed && !hasFilePaths);
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
