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
        return "Write content to a file under the working directory. Creates parent "
            + "directories if needed; overwrites existing files.";
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
