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
            // Self-correction context: instead of forcing the model to re-Read the file,
            // include up to 3 closest-prefix matches with ±5 surrounding lines so it can
            // diagnose drift (whitespace, newlines, slightly different identifiers) directly.
            throw new ToolInvocationException(
                "old_string not found in " + filePath
                    + closestMatchesContext(content, oldStr));
        }
        if (!replaceAll && occurrences > 1) {
            // Same idea for ambiguous matches: show ±3 lines around each occurrence so the
            // model can pick which one it meant and add disambiguating context to its retry.
            throw new ToolInvocationException(
                "old_string appears " + occurrences + " times in " + filePath
                    + " — add surrounding context to make it unique, or set replace_all=true."
                    + multipleMatchesContext(content, oldStr));
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

    /** Total payload cap for self-correction context (chars). Models pay token cost on this. */
    private static final int CONTEXT_BUDGET_CHARS = 2000;
    /** Length of prefix used when no exact match exists, to find approximate sites. */
    private static final int PREFIX_PROBE_CHARS = 40;
    /** Max number of ambiguous-match snippets to include in a multi-match error. */
    private static final int MAX_AMBIGUOUS_HITS = 5;
    /** Max number of prefix-probe matches to include when old_string isn't present at all. */
    private static final int MAX_PREFIX_HITS = 3;

    /**
     * Builds a "closest matches by prefix" snippet for the no-occurrence error path.
     * Looks for the first {@link #PREFIX_PROBE_CHARS} of {@code oldStr} in {@code content}
     * (progressively trimming the prefix on whitespace boundaries until something hits)
     * and returns up to {@link #MAX_PREFIX_HITS} hits with ±5 surrounding lines each.
     * Returns an empty string when no prefix overlap is found.
     */
    private static String closestMatchesContext(String content, String oldStr) {
        String probe = oldStr.length() > PREFIX_PROBE_CHARS
            ? oldStr.substring(0, PREFIX_PROBE_CHARS)
            : oldStr;
        // Trim trailing whitespace on the probe so we don't fail on EOL drift.
        while (!probe.isEmpty() && Character.isWhitespace(probe.charAt(probe.length() - 1))) {
            probe = probe.substring(0, probe.length() - 1);
        }
        // Walk the probe down progressively so we still find SOMETHING when the prefix
        // also drifted (e.g. the model's old_string had a typo at char 30).
        int[] hits = null;
        while (probe.length() >= 8) {
            hits = findAllOccurrences(content, probe);
            if (hits.length > 0) break;
            // Cut the probe at the last word boundary to retry.
            int cut = lastWordBoundary(probe);
            if (cut <= 8) break;
            probe = probe.substring(0, cut);
        }
        if (hits == null || hits.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\nClosest matches by prefix (").append(hits.length).append(" hit")
            .append(hits.length == 1 ? "" : "s").append(", showing up to ")
            .append(MAX_PREFIX_HITS).append(" with ±5 surrounding lines):\n");
        int shown = 0;
        for (int pos : hits) {
            if (shown++ >= MAX_PREFIX_HITS) break;
            String snippet = surroundingLines(content, pos, 5);
            sb.append(snippet);
            if (sb.length() > CONTEXT_BUDGET_CHARS) {
                sb.setLength(CONTEXT_BUDGET_CHARS);
                sb.append("\n... (context truncated)\n");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Builds a snippet for the multiple-occurrence error path: shows ±3 lines around each
     * of the first {@link #MAX_AMBIGUOUS_HITS} occurrences so the model can pick which
     * site it meant and append disambiguating text to its retry.
     */
    private static String multipleMatchesContext(String content, String oldStr) {
        int[] hits = findAllOccurrences(content, oldStr);
        if (hits.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\nMatch sites (showing up to ").append(MAX_AMBIGUOUS_HITS)
            .append(" with ±3 surrounding lines each):\n");
        int shown = 0;
        for (int pos : hits) {
            if (shown++ >= MAX_AMBIGUOUS_HITS) break;
            String snippet = surroundingLines(content, pos, 3);
            sb.append(snippet);
            if (sb.length() > CONTEXT_BUDGET_CHARS) {
                sb.setLength(CONTEXT_BUDGET_CHARS);
                sb.append("\n... (context truncated)\n");
                break;
            }
        }
        return sb.toString();
    }

    /** Returns all start positions of {@code needle} in {@code haystack}. */
    private static int[] findAllOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return new int[0];
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            out.add(idx);
            idx += needle.length();
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    /** Returns the last whitespace-delimited word-boundary index in {@code s}, or -1. */
    private static int lastWordBoundary(String s) {
        for (int i = s.length() - 1; i > 0; i--) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /**
     * Returns a snippet of {@code content} containing {@code linesBeforeAfter} lines on
     * either side of the line containing {@code charPos}, with 1-indexed line numbers
     * and a separator header. Format mirrors {@code cat -n}.
     */
    private static String surroundingLines(String content, int charPos, int linesBeforeAfter) {
        // Pre-compute line offsets for the file.
        int targetLine = 1;
        for (int i = 0; i < charPos && i < content.length(); i++) {
            if (content.charAt(i) == '\n') targetLine++;
        }
        int from = Math.max(1, targetLine - linesBeforeAfter);
        int to = targetLine + linesBeforeAfter;
        StringBuilder sb = new StringBuilder();
        sb.append("--- around line ").append(targetLine).append(" ---\n");
        int lineNum = 1;
        int lineStart = 0;
        for (int i = 0; i <= content.length(); i++) {
            boolean eof = i == content.length();
            if (eof || content.charAt(i) == '\n') {
                if (lineNum >= from && lineNum <= to) {
                    String line = content.substring(lineStart, i);
                    sb.append(String.format("%6d\t%s%n", lineNum, line));
                }
                lineNum++;
                lineStart = i + 1;
                if (lineNum > to) break;
            }
        }
        return sb.toString();
    }
}
