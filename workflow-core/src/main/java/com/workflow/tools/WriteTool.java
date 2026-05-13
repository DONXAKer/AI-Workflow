package com.workflow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes a text file under the working directory. Creates parent directories as needed.
 * Overwrites existing content when the target already exists.
 *
 * <p>Destructive-path deny-list (credentials, keys, .env) lives in {@code DenyList} and
 * is wired here in M2.3. For now, {@link PathScope} bounds writes to the project working
 * directory so the blast radius is capped.
 */
@Component
public class WriteTool implements Tool {

    @Autowired(required = false)
    private FileSystemCache fileSystemCache;

    @Override
    public String name() { return "Write"; }

    @Override
    public String description() {
        return "Write content to a file under the working directory. **OVERWRITES the "
            + "entire file** if it already exists — use Edit for partial modifications. "
            + "Creates parent directories if needed. Refuses to truncate a >100-line "
            + "existing file with a <40%-size payload (likely a bug; use Edit instead).";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper om) {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("file_path").put("type", "string")
            .put("description", "Target path, absolute or relative to working directory.");
        props.putObject("content").put("type", "string")
            .put("description", "Full contents to write to the file.");
        schema.putArray("required").add("file_path").add("content");
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode input) throws Exception {
        String filePath = input.path("file_path").asText();
        if (!input.has("content") || input.path("content").isNull()) {
            throw new ToolInvocationException("content is required");
        }
        String content = input.path("content").asText();

        Path target = PathScope.resolve(ctx, filePath);
        DenyList.assertWriteAllowed(target);

        // Large-file truncate guard: if the model calls Write on an existing >100-line
        // file with a much smaller payload, it almost certainly meant Edit. This
        // exact pattern destroyed UnitCard.java (576 → 16 lines) on FEAT-AP-002.
        if (Files.exists(target) && Files.isRegularFile(target)) {
            try {
                long existingLines = Files.lines(target, StandardCharsets.UTF_8).count();
                if (existingLines > 100) {
                    int newLines = content.isEmpty() ? 0 : (int) content.chars().filter(c -> c == '\n').count() + 1;
                    if (newLines < existingLines * 0.4) {
                        throw new ToolInvocationException(String.format(
                            "Refusing to overwrite %s: existing file has %d lines, " +
                            "your payload has %d lines (%.0f%%). This is almost always " +
                            "an Edit-vs-Write mistake. Use the Edit tool to modify part " +
                            "of the file. If you really need to replace the whole file, " +
                            "delete it first via Bash (rm) then call Write.",
                            ctx.workingDir().relativize(target),
                            existingLines, newLines, 100.0 * newLines / existingLines));
                    }
                }
            } catch (java.io.IOException ignored) { /* fall through — write proceeds */ }
        }

        Path parent = target.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        Files.writeString(target, content, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        if (fileSystemCache != null) {
            fileSystemCache.invalidateFile(target);
            fileSystemCache.invalidateDirectory(ctx.workingDir());
        }

        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        return "wrote " + bytes + " bytes to " + ctx.workingDir().relativize(target);
    }
}
