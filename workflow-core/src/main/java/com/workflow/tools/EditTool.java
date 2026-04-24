package com.workflow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Performs exact string replacement in a file. Matches Claude Code's Edit semantics: the
 * {@code old_string} must be unique in the file unless {@code replace_all} is set, and
 * the file must already exist (use {@link WriteTool} for new files).
 */
@Component
public class EditTool implements Tool {

    @Autowired(required = false)
    private FileSystemCache fileSystemCache;

    @Override
    public String name() { return "Edit"; }

    @Override
    public String description() {
        return "Replace an exact string in an existing file. old_string must be unique "
            + "unless replace_all=true. Use Write to create new files.";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper om) {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("file_path").put("type", "string")
            .put("description", "File to modify, absolute or relative to working dir.");
        props.putObject("old_string").put("type", "string")
            .put("description", "Exact text to replace.");
        props.putObject("new_string").put("type", "string")
            .put("description", "Replacement text.");
        props.putObject("replace_all").put("type", "boolean")
            .put("description", "Replace every occurrence. Default false.");
        schema.putArray("required").add("file_path").add("old_string").add("new_string");
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode input) throws Exception {
        String filePath = input.path("file_path").asText();
        String oldStr = input.path("old_string").asText();
        String newStr = input.path("new_string").asText();
        boolean replaceAll = input.path("replace_all").asBoolean(false);

        if (oldStr.isEmpty()) {
            throw new ToolInvocationException("old_string cannot be empty");
        }
        if (oldStr.equals(newStr)) {
            throw new ToolInvocationException("old_string and new_string are identical — nothing to change");
        }

        Path target = PathScope.resolve(ctx, filePath);
        DenyList.assertWriteAllowed(target);

        if (!Files.exists(target)) {
            throw new ToolInvocationException("file not found: " + filePath);
        }
        if (!Files.isRegularFile(target)) {
            throw new ToolInvocationException("not a regular file: " + filePath);
        }

        String content = Files.readString(target, StandardCharsets.UTF_8);

        int occurrences = countOccurrences(content, oldStr);
        if (occurrences == 0) {
            throw new ToolInvocationException("old_string not found in " + filePath);
        }
        if (!replaceAll && occurrences > 1) {
            throw new ToolInvocationException(
                "old_string appears " + occurrences + " times in " + filePath
                    + " — add surrounding context to make it unique, or set replace_all=true");
        }

        String updated = replaceAll
            ? content.replace(oldStr, newStr)
            : replaceFirst(content, oldStr, newStr);
        Files.writeString(target, updated, StandardCharsets.UTF_8);

        if (fileSystemCache != null) {
            fileSystemCache.invalidateFile(target);
        }

        int replaced = replaceAll ? occurrences : 1;
        return "edited " + ctx.workingDir().relativize(target)
            + " — replaced " + replaced + " occurrence" + (replaced == 1 ? "" : "s");
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String replaceFirst(String haystack, String needle, String replacement) {
        int idx = haystack.indexOf(needle);
        if (idx < 0) return haystack;
        return haystack.substring(0, idx) + replacement + haystack.substring(idx + needle.length());
    }
}
