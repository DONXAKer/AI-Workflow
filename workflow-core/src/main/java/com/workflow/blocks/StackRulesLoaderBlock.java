package com.workflow.blocks;

import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads stack-specific coding rules from {@code classpath:stack-rules/} based on the
 * {@code tech_stack} detected by context_scan. Returns {@code rules_content} (concatenated
 * markdown) and {@code loaded_rules} (list of matched rule file names).
 *
 * <p>Designed to run after context_scan and before analysis/chunk, so that downstream
 * blocks can reference {@code ${stack_rules.rules_content}} in their prompts without
 * embedding a static catalog of all possible rules.
 */
@Component
public class StackRulesLoaderBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(StackRulesLoaderBlock.class);

    private static final String RULES_DIR = "stack-rules/";

    /** Maps lowercase keywords (matched against any tech_stack value) to rule file names. */
    private static final Map<String, String> KEYWORD_TO_FILE = new LinkedHashMap<>();

    static {
        KEYWORD_TO_FILE.put("java",        "java-spring.md");
        KEYWORD_TO_FILE.put("spring",      "java-spring.md");
        KEYWORD_TO_FILE.put("spring-boot", "java-spring.md");
        KEYWORD_TO_FILE.put("liquibase",   "liquibase.md");
        KEYWORD_TO_FILE.put("flyway",      "flyway.md");
        KEYWORD_TO_FILE.put("typescript",  "typescript-react.md");
        KEYWORD_TO_FILE.put("react",       "typescript-react.md");
        KEYWORD_TO_FILE.put("postgresql",  "postgresql.md");
        KEYWORD_TO_FILE.put("postgres",    "postgresql.md");
        KEYWORD_TO_FILE.put("mysql",       "mysql.md");
        KEYWORD_TO_FILE.put("mariadb",     "mysql.md");
        KEYWORD_TO_FILE.put("go",          "go.md");
        KEYWORD_TO_FILE.put("golang",      "go.md");
        KEYWORD_TO_FILE.put("python",      "python.md");
    }

    @Override
    public String getName() { return "stack_rules_loader"; }

    @Override
    public String getDescription() {
        return "Загружает файлы стек-специфичных правил на основе tech_stack из context_scan.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
            "Stack Rules Loader",
            "utility",
            Phase.ANALYZE,
            List.of(
                FieldSchema.string("rules_dir", "Папка правил",
                    "Classpath-путь к папке с .md файлами правил (default: stack-rules/)")
            ),
            false,
            Map.of()
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> input, BlockConfig config, PipelineRun run) {
        Map<String, Object> cfg = config.getConfig() != null ? config.getConfig() : Map.of();
        String rulesDir = cfg.getOrDefault("rules_dir", RULES_DIR).toString();
        if (!rulesDir.endsWith("/")) rulesDir += "/";

        Map<String, Object> contextScan = toMap(input.get("context_scan"));
        Map<String, Object> techStack   = toMap(contextScan.get("tech_stack"));

        Set<String> tokens = extractTokens(techStack);
        log.debug("stack_rules: tech_stack tokens={}", tokens);

        // Collect unique rule files in stable order (LinkedHashSet preserves insertion order)
        Set<String> ruleFiles = new LinkedHashSet<>();
        for (String token : tokens) {
            for (Map.Entry<String, String> e : KEYWORD_TO_FILE.entrySet()) {
                if (token.contains(e.getKey())) {
                    ruleFiles.add(e.getValue());
                }
            }
        }

        log.info("stack_rules: loading {} rule file(s): {}", ruleFiles.size(), ruleFiles);

        StringBuilder content = new StringBuilder();
        List<String> loaded = new ArrayList<>();
        for (String fileName : ruleFiles) {
            String text = readResource(rulesDir + fileName);
            if (text != null) {
                content.append(text).append("\n\n");
                loaded.add(fileName);
            } else {
                log.warn("stack_rules: rule file not found: {}{}", rulesDir, fileName);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rules_content", content.toString().trim());
        result.put("loaded_rules", loaded);
        result.put("detected_tokens", new ArrayList<>(tokens));
        return result;
    }

    /**
     * Flattens all string values from the tech_stack map into a set of lowercase tokens.
     * Handles: language (string), framework (string), build_tool (string),
     *          key_deps (List<String>), and any other string values.
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractTokens(Map<String, Object> techStack) {
        Set<String> tokens = new LinkedHashSet<>();
        if (techStack == null || techStack.isEmpty()) return tokens;

        for (Map.Entry<String, Object> entry : techStack.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String s && !s.isBlank()) {
                tokens.add(s.toLowerCase(Locale.ROOT).trim());
            } else if (val instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s && !s.isBlank()) {
                        tokens.add(s.toLowerCase(Locale.ROOT).trim());
                    }
                }
            }
        }
        return tokens;
    }

    private String readResource(String path) {
        try {
            ClassPathResource res = new ClassPathResource(path);
            return new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object obj) {
        if (obj instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return new LinkedHashMap<>();
    }
}
