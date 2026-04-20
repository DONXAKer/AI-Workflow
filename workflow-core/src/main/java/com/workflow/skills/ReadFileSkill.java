package com.workflow.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Skill: read the contents of a file from the workspace.
 */
@Component
public class ReadFileSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(ReadFileSkill.class);
    private static final int MAX_CHARS = 32_000;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Read the full contents of a file. Returns the file text (up to 32 000 characters).";
    }

    @Override
    public ObjectNode getInputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "Path to the file (relative to the workspace root or absolute).");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String pathStr = String.valueOf(params.get("path"));
        Path filePath = Paths.get(pathStr);

        if (!Files.exists(filePath)) {
            return Map.of("error", "File not found: " + pathStr);
        }
        if (!Files.isRegularFile(filePath)) {
            return Map.of("error", "Not a regular file: " + pathStr);
        }

        String content = Files.readString(filePath);
        boolean truncated = content.length() > MAX_CHARS;
        if (truncated) {
            content = content.substring(0, MAX_CHARS);
        }

        log.debug("read_file: {} ({} chars{})", pathStr, content.length(), truncated ? ", truncated" : "");
        return Map.of(
            "path", pathStr,
            "content", content,
            "truncated", truncated
        );
    }
}
