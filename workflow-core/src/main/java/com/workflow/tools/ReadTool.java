package com.workflow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads a text file and returns its lines in {@code cat -n} format (1-based line numbers,
 * tab-separated). Matches the Claude Code Read tool's surface so SKILL.md files work
 * verbatim.
 */
@Component
public class ReadTool implements Tool {

    static final int DEFAULT_LIMIT = 2000;
    static final int MAX_BYTES = 512 * 1024;

    @Autowired(required = false)
    private FileSystemCache fileSystemCache;

    @Override
    public String name() { return "Read"; }

    @Override
    public String description() {
        return "Read a text file relative to the working directory. "
            + "Returns lines with 1-based line numbers in cat -n format. "
            + "Use offset/limit to page through long files (default limit 2000 lines).";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper om) {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("file_path").put("type", "string")
            .put("description", "Absolute path or path relative to the working directory.");
        props.putObject("offset").put("type", "integer")
            .put("description", "1-based line number to start at.");
        props.putObject("limit").put("type", "integer")
            .put("description", "Max number of lines to return. Default 2000.");
        schema.putArray("required").add("file_path");
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode input) throws Exception {
        String filePath = input.path("file_path").asText();
        int offset = Math.max(1, input.path("offset").asInt(1));
        int limit = input.path("limit").asInt(DEFAULT_LIMIT);
        if (limit <= 0) limit = DEFAULT_LIMIT;

        Path target = PathScope.resolve(ctx, filePath);
        if (!Files.exists(target)) {
            throw new ToolInvocationException("file not found: " + filePath);
        }
        if (!Files.isRegularFile(target)) {
            throw new ToolInvocationException("not a regular file: " + filePath);
        }
        long size = Files.size(target);
        if (size > MAX_BYTES) {
            throw new ToolInvocationException(
                "file too large (" + size + " bytes, limit " + MAX_BYTES
                    + "): " + filePath + " — use offset/limit to read a window");
        }

        // Use cache only for default full-file reads (no offset/limit override)
        boolean isDefaultRead = input.path("offset").isMissingNode() && input.path("limit").isMissingNode();
        List<String> lines = null;
        if (isDefaultRead && fileSystemCache != null) {
            lines = fileSystemCache.getRead(target);
        }
        if (lines == null) {
            try {
                lines = Files.readAllLines(target, StandardCharsets.UTF_8);
            } catch (MalformedInputException e) {
                throw new ToolInvocationException(
                    "file is not valid UTF-8 (likely binary): " + filePath);
            } catch (IOException e) {
                throw new ToolInvocationException("failed to read " + filePath + ": " + e.getMessage(), e);
            }
            if (isDefaultRead && fileSystemCache != null) {
                fileSystemCache.putRead(target, lines);
            }
        }

        int from = Math.min(lines.size(), offset - 1);
        int to = Math.min(lines.size(), from + limit);
        if (from >= to) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(String.format("%6d\t%s%n", i + 1, lines.get(i)));
        }
        return sb.toString();
    }
}
