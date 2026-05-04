package com.workflow.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads {@code CLAUDE.md} from the target project's working directory so analysis,
 * code-generation and tool-using agents see the project's own conventions (build
 * system quirks, package layout, "do not touch" lists) without the operator having
 * to copy them into every system_prompt.
 *
 * <p>The file is treated as read-only project metadata. Missing file → empty string;
 * read errors are logged at debug and produce empty output (a stale CLAUDE.md must
 * not block a run). A hard cap of {@link #MAX_CHARS} characters protects the prompt
 * budget from accidentally huge files.
 */
public final class ProjectClaudeMd {

    private static final Logger log = LoggerFactory.getLogger(ProjectClaudeMd.class);

    /** ~4K tokens at avg ratio. Plenty for conventions; truncates pathological files. */
    private static final int MAX_CHARS = 16_000;

    private ProjectClaudeMd() {}

    /**
     * Returns the CLAUDE.md content prefixed with a markdown heading suitable for
     * inlining into a user message, or an empty string when the file is absent.
     */
    public static String readForPrompt(Path workingDir) {
        if (workingDir == null) return "";
        Path file = workingDir.resolve("CLAUDE.md");
        if (!Files.isRegularFile(file)) return "";
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            boolean truncated = false;
            if (content.length() > MAX_CHARS) {
                content = content.substring(0, MAX_CHARS);
                truncated = true;
            }
            StringBuilder sb = new StringBuilder()
                .append("## Project conventions (CLAUDE.md)\n")
                .append(content);
            if (!content.endsWith("\n")) sb.append('\n');
            if (truncated) sb.append("... (truncated — full file at ").append(file).append(")\n");
            return sb.toString();
        } catch (IOException e) {
            log.debug("Could not read {}: {}", file, e.getMessage());
            return "";
        }
    }

    /**
     * Convenience: resolves the current project's working directory via
     * {@link ProjectContext} and {@link ProjectRepository} before reading.
     */
    public static String readForCurrentProject(ProjectRepository projectRepository) {
        if (projectRepository == null) return "";
        String slug = ProjectContext.get();
        if (slug == null || slug.isBlank()) return "";
        Project project = projectRepository.findBySlug(slug).orElse(null);
        if (project == null || project.getWorkingDir() == null || project.getWorkingDir().isBlank()) {
            return "";
        }
        return readForPrompt(Path.of(project.getWorkingDir()));
    }
}
