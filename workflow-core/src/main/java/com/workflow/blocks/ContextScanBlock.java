package com.workflow.blocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.llm.LlmClient;
import com.workflow.project.Project;
import com.workflow.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans the target project's structure (build manifests + a small sample of source
 * files) and asks a flash-tier LLM to describe its tech stack, conventions, and
 * which language-specific best practices apply.
 *
 * <p>Unlike {@link AgentWithToolsBlock}, this block does NOT run an agentic loop —
 * a single completion call against a pre-curated prompt is faster, deterministic-ish,
 * and sufficient because we only need a description, not exploration. The prompt
 * is composed by reading manifest files (pom.xml / build.gradle / package.json /
 * etc.), 2–3 sample source files, the language-specific best-practices markdown
 * from the classpath registry, and any {@code ## Best Practices} section in the
 * target's CLAUDE.md.
 *
 * <p>Output shape (also defined in {@link #getMetadata}):
 * <pre>
 * context_scan:
 *   tech_stack: { language, framework, build_tool, key_deps: [...] }
 *   code_conventions: [ "uses Lombok @Data for entities", ... ]
 *   applicable_best_practices: [ { rule, source, confidence } ]
 *   suggestions_for_codegen: [ "When adding a new entity, follow ...", ... ]
 *   language: "java"
 *   source_files_sampled: 3
 * </pre>
 *
 * <p>{@code suggestions_for_codegen} is <em>advice</em> for downstream codegen —
 * NOT acceptance criteria. verify/agent_verify must not gate on it.
 */
@Component
public class ContextScanBlock implements Block {

    private static final Logger log = LoggerFactory.getLogger(ContextScanBlock.class);

    private static final int MAX_MANIFEST_BYTES = 8 * 1024;
    private static final int MAX_SAMPLE_BYTES = 4 * 1024;
    private static final int MAX_SAMPLES = 3;
    private static final int MAX_PROMPT_BYTES = 24 * 1024;

    private static final String SYSTEM_PROMPT = """
            Ты senior engineer-onboarder. Твоя задача — по содержимому build-manifest \
            файлов и 2-3 примеров кода проекта определить tech stack и описать что в этом \
            проекте используется как convention, какие best practices применимы.

            Верни СТРОГО валидный JSON по схеме (без markdown, без комментариев):
            {
              "tech_stack": {
                "language": "java" | "python" | "typescript" | "javascript" | "go" | "rust" | "<other>",
                "framework": "<short name or null>",
                "build_tool": "gradle" | "maven" | "npm" | "pnpm" | "yarn" | "pytest" | "cargo" | "go-modules" | null,
                "key_deps": ["<dep1>", "<dep2>", ...]
              },
              "code_conventions": [
                "<one-line observation about pattern used, e.g. 'uses Lombok @Data for entities (sampled X.java)'>",
                ...
              ],
              "applicable_best_practices": [
                {"rule": "<rule from registry that applies here>",
                 "source": "<filename or 'inferred'>",
                 "confidence": <0.0..1.0>},
                ...
              ],
              "suggestions_for_codegen": [
                "<actionable hint for code that will be generated, e.g. 'New entities should follow the Lombok+JPA pattern from X.java'>",
                ...
              ]
            }

            Правила:
            - confidence: 0.9+ только если ты явно видишь паттерн в samples; иначе 0.5-0.8.
            - НЕ выдумывай deps которых нет в manifest. key_deps — только то что в файле.
            - code_conventions: 3-7 пунктов, каждый — одна строка с указанием в скобках "(sampled X)" \
              если основано на конкретном файле.
            - suggestions_for_codegen: рекомендации к codegen, не acceptance criteria. \
              "Используй паттерн Y" — да; "должен быть тест" — нет.
            - Если best-practices registry пуст, applicable_best_practices = [].
            """;

    @Autowired private LlmClient llmClient;
    @Autowired private ObjectMapper objectMapper;
    @Autowired(required = false) private ProjectRepository projectRepository;

    @Override public String getName() { return "context_scan"; }

    @Override public String getDescription() {
        return "Описывает tech stack и code conventions целевого проекта по манифестам "
                + "(pom.xml/build.gradle/package.json/...) + 2-3 sample-файлам + "
                + "best-practices registry. Один LLM-вызов на flash-tier модели.";
    }

    @Override
    public BlockMetadata getMetadata() {
        return new BlockMetadata(
                "Context Scan",
                "analysis",
                Phase.ANY,
                List.of(
                        FieldSchema.string("working_dir", "Рабочая директория",
                                "Абсолютный путь. Пусто — берётся workingDir текущего проекта."),
                        FieldSchema.string("language", "Язык (override)",
                                "Override для авто-детекта (java/python/typescript/go/rust)."),
                        FieldSchema.number("max_samples", "Сколько sample-файлов",
                                MAX_SAMPLES, "Максимум исходных файлов для context (cap=" + MAX_SAMPLES + ").")
                ),
                false,
                Map.of()
        );
    }

    @Override
    public Map<String, Object> run(Map<String, Object> input, BlockConfig blockConfig, PipelineRun run) throws Exception {
        Map<String, Object> cfg = blockConfig.getConfig() != null ? blockConfig.getConfig() : Map.of();

        Path workingDir = resolveWorkingDir(cfg, run);
        String language = resolveLanguage(cfg, workingDir);

        ManifestExcerpt manifest = readManifest(workingDir);
        List<SampleFile> samples = readSamples(workingDir, language, intCfg(cfg, "max_samples", MAX_SAMPLES));
        String registryContent = readBestPracticesRegistry(language);
        String claudeMdBestPractices = readClaudeMdBestPractices(workingDir);

        String userMessage = composeUserPrompt(workingDir, language, manifest, samples,
                registryContent, claudeMdBestPractices);

        String model = "flash";
        int maxTokens = 2048;
        double temperature = 0.3;
        if (blockConfig.getAgent() != null) {
            String overrideModel = blockConfig.getAgent().getEffectiveModel();
            if (overrideModel != null && !overrideModel.isBlank()) model = overrideModel;
            maxTokens = blockConfig.getAgent().getMaxTokensOrDefault();
            Double explicitTemp = blockConfig.getAgent().getTemperature();
            if (explicitTemp != null && explicitTemp != 1.0) temperature = explicitTemp;
        }

        log.info("context_scan[{}]: language={} samples={} model={}",
                blockConfig.getId(), language, samples.size(), model);
        String response = llmClient.complete(model, SYSTEM_PROMPT, userMessage, maxTokens, temperature);

        Map<String, Object> parsed = parseSafely(response);
        return finalize(parsed, language, samples.size());
    }

    // ── prompt composition ─────────────────────────────────────────────────────

    private String composeUserPrompt(Path workingDir, String language, ManifestExcerpt manifest,
                                       List<SampleFile> samples, String registry, String claudeMdBest) {
        StringBuilder sb = new StringBuilder();
        sb.append("Project root: ").append(workingDir).append("\n");
        sb.append("Inferred language: ").append(language).append("\n\n");

        if (manifest != null && manifest.content != null && !manifest.content.isBlank()) {
            sb.append("## Manifest (").append(manifest.filename).append(")\n```\n");
            sb.append(manifest.content);
            if (manifest.truncated) sb.append("\n...[truncated]");
            sb.append("\n```\n\n");
        } else {
            sb.append("## Manifest\n(none found — likely greenfield or non-standard layout)\n\n");
        }

        if (!samples.isEmpty()) {
            sb.append("## Sample source files\n");
            for (SampleFile s : samples) {
                sb.append("### ").append(s.relativePath).append("\n```\n");
                sb.append(s.content);
                if (s.truncated) sb.append("\n...[truncated]");
                sb.append("\n```\n");
            }
            sb.append("\n");
        }

        if (registry != null && !registry.isBlank()) {
            sb.append("## Best-practices registry (").append(language).append(")\n");
            sb.append(registry).append("\n\n");
        } else {
            sb.append("## Best-practices registry\n(empty for ").append(language).append(" — return [] for applicable_best_practices)\n\n");
        }

        if (claudeMdBest != null && !claudeMdBest.isBlank()) {
            sb.append("## Project-specific Best Practices (CLAUDE.md)\n");
            sb.append(claudeMdBest).append("\n\n");
        }

        sb.append("Опиши tech_stack + code_conventions + applicable_best_practices + suggestions_for_codegen ");
        sb.append("по схеме. Только JSON.");

        // Cap prompt size — pathological CLAUDE.md or huge registry shouldn't blow context.
        if (sb.length() > MAX_PROMPT_BYTES) {
            String head = sb.substring(0, MAX_PROMPT_BYTES);
            return head + "\n...[prompt truncated at " + MAX_PROMPT_BYTES + " chars]";
        }
        return sb.toString();
    }

    // ── manifest detection ─────────────────────────────────────────────────────

    private static final String[] MANIFEST_CANDIDATES = {
            "build.gradle.kts", "build.gradle", "pom.xml",
            "package.json", "pyproject.toml", "go.mod", "Cargo.toml"
    };

    private ManifestExcerpt readManifest(Path workingDir) {
        for (String name : MANIFEST_CANDIDATES) {
            Path f = workingDir.resolve(name);
            if (Files.isRegularFile(f)) {
                try {
                    String content = Files.readString(f, StandardCharsets.UTF_8);
                    boolean truncated = content.length() > MAX_MANIFEST_BYTES;
                    if (truncated) content = content.substring(0, MAX_MANIFEST_BYTES);
                    return new ManifestExcerpt(name, content, truncated);
                } catch (IOException e) {
                    log.debug("context_scan: failed to read {}: {}", f, e.getMessage());
                }
            }
        }
        return null;
    }

    private String resolveLanguage(Map<String, Object> cfg, Path workingDir) {
        Object override = cfg.get("language");
        if (override != null && !override.toString().isBlank()) {
            return override.toString().toLowerCase();
        }
        // Manifest-based heuristic — same as PreflightConfigResolver auto-detect.
        if (Files.isRegularFile(workingDir.resolve("build.gradle"))
                || Files.isRegularFile(workingDir.resolve("build.gradle.kts"))
                || Files.isRegularFile(workingDir.resolve("pom.xml"))) {
            return "java";
        }
        if (Files.isRegularFile(workingDir.resolve("package.json"))) {
            // Differentiate ts vs js by tsconfig.json presence.
            return Files.isRegularFile(workingDir.resolve("tsconfig.json")) ? "typescript" : "javascript";
        }
        if (Files.isRegularFile(workingDir.resolve("pyproject.toml"))
                || Files.isRegularFile(workingDir.resolve("setup.py"))) {
            return "python";
        }
        if (Files.isRegularFile(workingDir.resolve("go.mod"))) return "go";
        if (Files.isRegularFile(workingDir.resolve("Cargo.toml"))) return "rust";
        return "unknown";
    }

    // ── sample file extraction ────────────────────────────────────────────────

    private List<SampleFile> readSamples(Path workingDir, String language, int maxSamples) {
        String[] dirs = sampleDirsFor(language);
        String[] extensions = sampleExtensionsFor(language);
        List<SampleFile> out = new ArrayList<>();

        for (String dir : dirs) {
            Path searchRoot = workingDir.resolve(dir);
            if (!Files.isDirectory(searchRoot)) continue;
            try (Stream<Path> stream = Files.walk(searchRoot, 8)) {
                List<Path> matched = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> hasExtensionAny(p, extensions))
                        .filter(p -> {
                            try { return Files.size(p) < 30_000; } catch (Exception e) { return false; }
                        })
                        .limit(maxSamples - out.size() + 5)  // few extra in case of read errors
                        .toList();
                for (Path p : matched) {
                    if (out.size() >= maxSamples) break;
                    try {
                        String content = Files.readString(p, StandardCharsets.UTF_8);
                        boolean truncated = content.length() > MAX_SAMPLE_BYTES;
                        if (truncated) content = content.substring(0, MAX_SAMPLE_BYTES);
                        out.add(new SampleFile(workingDir.relativize(p).toString().replace('\\', '/'),
                                content, truncated));
                    } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                log.debug("context_scan: failed to walk {}: {}", searchRoot, e.getMessage());
            }
            if (out.size() >= maxSamples) break;
        }
        return out;
    }

    private static String[] sampleDirsFor(String lang) {
        return switch (lang) {
            case "java" -> new String[]{"src/main/java"};
            case "python" -> new String[]{"src", "."};
            case "typescript", "javascript" -> new String[]{"src", "app"};
            case "go" -> new String[]{"."};
            case "rust" -> new String[]{"src"};
            default -> new String[]{"src", "."};
        };
    }

    private static String[] sampleExtensionsFor(String lang) {
        return switch (lang) {
            case "java" -> new String[]{".java"};
            case "python" -> new String[]{".py"};
            case "typescript" -> new String[]{".ts", ".tsx"};
            case "javascript" -> new String[]{".js", ".jsx"};
            case "go" -> new String[]{".go"};
            case "rust" -> new String[]{".rs"};
            default -> new String[]{".java", ".py", ".ts", ".js", ".go", ".rs"};
        };
    }

    private static boolean hasExtensionAny(Path p, String[] exts) {
        String name = p.getFileName().toString();
        for (String e : exts) if (name.endsWith(e)) return true;
        return false;
    }

    // ── best-practices registry + CLAUDE.md ───────────────────────────────────

    String readBestPracticesRegistry(String language) {
        ClassPathResource resource = new ClassPathResource("knowledge/best-practices/" + language + ".md");
        if (!resource.exists()) return "";
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("context_scan: failed to read registry for {}: {}", language, e.getMessage());
            return "";
        }
    }

    private static final Pattern CLAUDE_BEST_PRACTICES = Pattern.compile(
            "(?im)^##\\s*best\\s+practices\\s*$\\s*([\\s\\S]*?)(?=^##\\s|\\z)");

    String readClaudeMdBestPractices(Path workingDir) {
        Path file = workingDir.resolve("CLAUDE.md");
        if (!Files.isRegularFile(file)) return "";
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = CLAUDE_BEST_PRACTICES.matcher(content);
            return m.find() ? m.group(1).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    // ── response handling ─────────────────────────────────────────────────────

    private Map<String, Object> parseSafely(String response) {
        try {
            return objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("context_scan: failed to parse JSON, returning empty result: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> finalize(Map<String, Object> parsed, String language, int sampledCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tech_stack", parsed.getOrDefault("tech_stack", new LinkedHashMap<>()));
        out.put("code_conventions", asListSafe(parsed.get("code_conventions")));
        out.put("applicable_best_practices", asListSafe(parsed.get("applicable_best_practices")));
        out.put("suggestions_for_codegen", asListSafe(parsed.get("suggestions_for_codegen")));
        out.put("language", language);
        out.put("source_files_sampled", sampledCount);
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Object> asListSafe(Object raw) {
        if (raw instanceof List<?> list) return (List<Object>) list;
        return new ArrayList<>();
    }

    // ── working dir resolution ─────────────────────────────────────────────────

    private Path resolveWorkingDir(Map<String, Object> cfg, PipelineRun run) {
        Object raw = cfg.get("working_dir");
        String resolved = raw != null ? raw.toString() : null;
        if ((resolved == null || resolved.isBlank()) && projectRepository != null) {
            String slug = run.getProjectSlug();
            if (slug != null && !slug.isBlank()) {
                Project p = projectRepository.findBySlug(slug).orElse(null);
                if (p != null && p.getWorkingDir() != null && !p.getWorkingDir().isBlank()) {
                    resolved = p.getWorkingDir();
                }
            }
        }
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException(
                    "context_scan: working_dir is not set and current project has no workingDir");
        }
        Path path = Paths.get(resolved).toAbsolutePath();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("context_scan: working_dir is not a directory: " + path);
        }
        return path;
    }

    private static int intCfg(Map<String, Object> cfg, String key, int def) {
        Object v = cfg.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (Exception e) { return def; }
    }

    // ── tiny value holders ─────────────────────────────────────────────────────

    private record ManifestExcerpt(String filename, String content, boolean truncated) {}
    private record SampleFile(String relativePath, String content, boolean truncated) {}
}
