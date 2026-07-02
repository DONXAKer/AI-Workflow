package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.core.expr.PathNotFoundException;
import com.workflow.core.expr.StringInterpolator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an intent.md file and extracts structured sections for the Agentic SDLC cycle.
 *
 * <p>Expected sections (all optional):
 * {@code ## Summary}, {@code ## Goal}, {@code ## Context}, {@code ## In Scope},
 * {@code ## Out of Scope}, {@code ## Invariants}, {@code ## Claims}, {@code ## Open Questions},
 * {@code ## Notes}.
 *
 * <p>Key output:
 * <ul>
 *   <li>{@code acceptance_checklist} — Claims table parsed into
 *       {@code [{id, text, priority, how_to_verify}]} compatible with {@code agent_verify}.</li>
 *   <li>{@code invariants} — bullet list from {@code ## Invariants} → {@code List<String>}.</li>
 *   <li>Scalar sections: {@code summary}, {@code goal}, {@code context_text},
 *       {@code in_scope}, {@code out_of_scope}, {@code open_questions}, {@code notes}.</li>
 *   <li>{@code feat_id}, {@code slug} — parsed from filename ({@code FEAT-001-slug.md} or
 *       {@code FEAT_ID_slug.md}); empty strings when filename does not match.</li>
 * </ul>
 *
 * <p>YAML shape:
 * <pre>
 * - id: intent_parse
 *   block: intent_md_input
 *   config:
 *     file_path: "${input.intent_file}"
 * </pre>
 */
@Component
public class IntentMdInputBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(IntentMdInputBlock.class);

    private static final Pattern FILENAME = Pattern.compile(
        "^(?<featId>[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-\\d+)-(?<slug>[^.]+)\\.md$");
    private static final Pattern FILENAME_UNDERSCORE = Pattern.compile(
        "^(?<featId>[A-Z0-9][A-Z0-9\\-]*)_(?<slug>[^.]+)\\.md$");
    private static final Pattern H1 = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern BACKTICK = Pattern.compile("`([^`]+)`");

    /** Maps lowercase ## heading to internal key. */
    private static final Map<String, String> SECTION_KEYS = Map.ofEntries(
        Map.entry("summary",         "summary"),
        Map.entry("goal",            "goal"),
        Map.entry("context",         "context_text"),
        Map.entry("in scope",        "in_scope"),
        Map.entry("out of scope",    "out_of_scope"),
        Map.entry("invariants",      "_invariants_raw"),
        Map.entry("stop conditions", "_stop_raw"),
        Map.entry("affected areas",  "_affected_raw"),
        Map.entry("claims",          "_claims_raw"),
        Map.entry("open questions",  "open_questions"),
        Map.entry("notes",           "notes")
    );

    @Autowired(required = false) private StringInterpolator stringInterpolator;

    @Override public String getName() { return "intent_md_input"; }

    @Override public boolean isCacheable(BlockConfig config) { return true; }

    @Override public String getDescription() {
        return "Парсит intent.md (Summary/Goal/In Scope/Out of Scope/Invariants/Claims) в структурированные поля. " +
               "Claims-таблица → acceptance_checklist, совместим с agent_verify.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
            "Intent.md input",
            "input",
            Phase.INTAKE,
            List.of(
                FieldSchema.requiredString("file_path", "Путь к intent.md",
                    "Абсолютный путь к файлу намерения или ${input.intent_file} для подстановки.")
                    .withLevel("essential")
            ),
            false,
            Map.of(),
            List.of(
                FieldSchema.output("feat_id", "Feat ID", "string",
                    "Идентификатор фичи из имени файла (например, FEAT-001)."),
                FieldSchema.output("slug", "Slug", "string",
                    "Slug-часть имени файла."),
                FieldSchema.output("title", "Title", "string",
                    "Первый H1-заголовок в теле файла или имя файла."),
                FieldSchema.output("body", "Body", "string",
                    "Полное содержимое файла intent.md."),
                FieldSchema.output("summary", "Summary", "string",
                    "Содержимое секции ## Summary."),
                FieldSchema.output("goal", "Goal", "string",
                    "Содержимое секции ## Goal."),
                FieldSchema.output("context_text", "Context", "string",
                    "Содержимое секции ## Context."),
                FieldSchema.output("in_scope", "In Scope", "string",
                    "Содержимое секции ## In Scope."),
                FieldSchema.output("out_of_scope", "Out of Scope", "string",
                    "Содержимое секции ## Out of Scope."),
                FieldSchema.output("invariants", "Invariants", "string_array",
                    "Список инвариантов из ## Invariants (bullet list → String[])."),
                FieldSchema.output("affected_areas", "Affected Areas", "string_array",
                    "Repo-relative пути из ## Affected Areas (для scope enforcement)."),
                FieldSchema.output("stop_conditions", "Stop Conditions", "string",
                    "Содержимое секции ## Stop Conditions."),
                FieldSchema.output("acceptance_checklist", "Acceptance checklist", "string_array",
                    "Claims из ## Claims в формате agent_verify: [{id, text, priority, how_to_verify}]."),
                FieldSchema.output("open_questions", "Open Questions", "string",
                    "Содержимое секции ## Open Questions."),
                FieldSchema.output("notes", "Notes", "string",
                    "Содержимое секции ## Notes.")
            ),
            100
        );
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig blockConfig, PipelineRun run)
            throws Exception {
        Map<String, Object> cfg = blockConfig.getConfig() != null ? blockConfig.getConfig() : Map.of();

        Object rawPath = cfg.get("file_path");
        if (rawPath == null || rawPath.toString().isBlank()) {
            throw new IllegalArgumentException("intent_md_input: config.file_path is required");
        }
        String filePath;
        try {
            filePath = stringInterpolator != null
                ? stringInterpolator.interpolate(rawPath.toString(), run, input)
                : rawPath.toString();
        } catch (PathNotFoundException e) {
            Object req = input.get("requirement");
            if (req != null && !req.toString().isBlank()) {
                log.warn("intent_md_input: {} — falling back to 'requirement' as file path", e.getMessage());
                filePath = req.toString();
            } else {
                throw e;
            }
        }

        Path path = Paths.get(filePath).toAbsolutePath();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("intent_md_input: file not found: " + path);
        }

        String body = Files.readString(path, StandardCharsets.UTF_8);
        String filename = path.getFileName().toString();

        Map<String, Object> out = new LinkedHashMap<>();

        // feat_id / slug from filename
        Matcher fm = FILENAME.matcher(filename);
        if (!fm.matches()) fm = FILENAME_UNDERSCORE.matcher(filename);
        if (fm.matches()) {
            out.put("feat_id", fm.group("featId"));
            out.put("slug", fm.group("slug"));
        } else {
            log.warn("intent_md_input: filename '{}' does not match expected pattern — feat_id/slug left empty",
                filename);
            out.put("feat_id", "");
            out.put("slug", filename.replaceFirst("\\.md$", ""));
        }

        // Title: first H1 or filename
        Matcher hm = H1.matcher(body);
        out.put("title", hm.find() ? hm.group(1).trim() : filename.replaceFirst("\\.md$", ""));
        out.put("body", body);

        // Parse sections
        Map<String, String> rawSections = extractSections(body);

        // Scalar sections
        for (String key : List.of("summary", "goal", "context_text", "in_scope", "out_of_scope",
                "open_questions", "notes")) {
            out.put(key, rawSections.getOrDefault(key, ""));
        }

        // Invariants: bullet list → List<String>
        String invariantsRaw = rawSections.getOrDefault("_invariants_raw", "");
        List<String> invariants = parseBulletList(invariantsRaw);
        out.put("invariants", invariants);

        // Affected Areas: bullet list of `path` — desc → List<String> of repo-relative paths
        String affectedRaw = rawSections.getOrDefault("_affected_raw", "");
        List<String> affectedAreas = parseAffectedAreas(affectedRaw);
        out.put("affected_areas", affectedAreas);

        // Stop Conditions: raw section text
        out.put("stop_conditions", rawSections.getOrDefault("_stop_raw", ""));

        // Claims table → acceptance_checklist (agent_verify format)
        String claimsRaw = rawSections.getOrDefault("_claims_raw", "");
        List<Map<String, Object>> acceptanceChecklist = parseClaimsTable(claimsRaw);
        out.put("acceptance_checklist", acceptanceChecklist);

        log.info("intent_md_input: feat_id={} slug={} claims={} invariants={}",
            out.get("feat_id"), out.get("slug"), acceptanceChecklist.size(), invariants.size());

        return out;
    }

    /**
     * Split body on {@code ## Heading} lines and map known headings to canonical internal keys.
     * Unknown headings are silently skipped.
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

    /** Parse a bullet or checkbox list into trimmed strings, skipping headers and blanks. */
    private List<String> parseBulletList(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String s = line.strip();
            if (s.startsWith("- [ ] ") || s.startsWith("- [x] ") || s.startsWith("- [X] ")) {
                result.add(s.substring(6).strip());
            } else if (s.startsWith("- ") || s.startsWith("* ")) {
                result.add(s.substring(2).strip());
            } else if (!s.isEmpty() && !s.startsWith("#") && !s.startsWith("|")) {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * Parse the {@code ## Affected Areas} bullet list into repo-relative paths.
     * Each bullet is expected as {@code `path/to/file` — описание}; the backticked path is taken,
     * falling back to the first whitespace-delimited token. Placeholders ({@code ...}) are skipped.
     */
    private List<String> parseAffectedAreas(String text) {
        List<String> result = new ArrayList<>();
        for (String item : parseBulletList(text)) {
            String path;
            Matcher m = BACKTICK.matcher(item);
            if (m.find()) {
                path = m.group(1).trim();
            } else {
                path = item.split("\\s+", 2)[0].trim();
            }
            if (!path.isEmpty() && !path.equals("...")) {
                result.add(path);
            }
        }
        return result;
    }

    /**
     * Parse a Markdown Claims table into the {@code acceptance_checklist} format expected by
     * {@code agent_verify}:
     * <pre>
     * | # | Claim | Как проверить |
     * |---|-------|---------------|
     * | 1 | text  | how           |
     * </pre>
     * → {@code [{id: "claim-1", text: "...", priority: "critical", how_to_verify: "..."}]}
     *
     * <p>All claims default to {@code priority: "critical"} — they are the operator's explicit
     * acceptance conditions, written before code.
     */
    private List<Map<String, Object>> parseClaimsTable(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        boolean headerSeen = false;
        int claimNumber = 0;

        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            if (!stripped.startsWith("|")) continue;
            // Skip separator rows: cells contain only -, :, spaces
            if (stripped.replaceAll("[|:\\-\\s]", "").isEmpty()) continue;

            String[] cells = stripped.split("\\|", -1);
            // cells[0] is the empty string before the leading |; data starts at index 1
            if (cells.length < 4) continue;

            String col1 = cells[1].strip();
            String col2 = cells[2].strip();
            String col3 = cells.length > 3 ? cells[3].strip() : "";

            // Skip header row (first column is "#", "No", "№" or "Claim")
            if (!headerSeen) {
                boolean isHeader = col1.equals("#") || col1.equalsIgnoreCase("no")
                    || col1.equals("№") || col1.equalsIgnoreCase("claim")
                    || col2.equalsIgnoreCase("claim") || col2.equalsIgnoreCase("claims");
                if (isHeader) {
                    headerSeen = true;
                    continue;
                }
            }
            headerSeen = true;

            if (col2.isEmpty() || col2.equals("...")) continue;

            claimNumber++;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", "claim-" + claimNumber);
            item.put("text", col2);
            item.put("priority", "critical");
            item.put("how_to_verify", col3.isEmpty() ? "по diff или тесту" : col3);
            result.add(item);
        }
        return result;
    }
}
