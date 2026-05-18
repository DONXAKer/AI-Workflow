package com.workflow.project;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Applies a built-in pipeline template (one of the YAMLs under {@code workflow-core/config/})
 * to a project's {@code .ai-workflow/} directory, so operators can bootstrap a project from
 * the Web UI rather than copying YAMLs by hand.
 *
 * <p>MVP: implementation is intentionally minimal — the method validates the template id
 * resolves to a real file under the platform config root, then refuses to overwrite an
 * existing target. Wire-up beyond that is a follow-up.
 */
@Service
public class TemplateService {

    /**
     * Copies the YAML for {@code templateId} from the platform config directory into
     * {@code configDir} (which the caller has already resolved — typically
     * {@code <workingDir>/.ai-workflow}). Returns the path written.
     *
     * @throws IllegalArgumentException when {@code templateId} doesn't resolve to a known template
     * @throws IllegalStateException    when the target file already exists (409 from the API)
     * @throws IOException              on filesystem failure
     */
    public Path applyTemplate(String templateId, Path configDir) throws IOException {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId is required");
        }
        Path source = Path.of("config", sanitize(templateId) + ".yaml");
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Unknown template: " + templateId);
        }
        Files.createDirectories(configDir);
        Path target = configDir.resolve(source.getFileName());
        if (Files.exists(target)) {
            throw new IllegalStateException("Target already exists: " + target);
        }
        Files.copy(source, target);
        return target;
    }

    private static String sanitize(String id) {
        // Conservative — keep only filename-safe characters; reject anything that could traverse.
        return id.replaceAll("[^a-zA-Z0-9._-]", "");
    }
}
