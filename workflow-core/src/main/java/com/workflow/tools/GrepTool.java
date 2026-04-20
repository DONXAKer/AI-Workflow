package com.workflow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Content search by regex across files under the working directory. Trimmed-down
 * surface compared to Claude Code's Grep, sufficient for Phase 1:
 * {@code pattern}, {@code path}, {@code glob}, {@code output_mode}
 * ({@code files_with_matches} | {@code content} | {@code count}), case-insensitive
 * flag, and a head limit.
 */
@Component
public class GrepTool implements Tool {

    private static final int DEFAULT_HEAD_LIMIT = 250;
    private static final long PER_FILE_MAX_BYTES = 2 * 1024 * 1024;

    @Override
    public String name() { return "Grep"; }

    @Override
    public String description() {
        return "Regex search over files under the working directory. "
            + "output_mode: 'files_with_matches' (default), 'content', or 'count'.";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper om) {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("pattern").put("type", "string")
            .put("description", "Java regex to search for inside file contents.");
        props.putObject("path").put("type", "string")
            .put("description", "Directory (or file) to search. Defaults to working dir.");
        props.putObject("glob").put("type", "string")
            .put("description", "Only search files whose relative path matches this glob.");
        props.putObject("output_mode").put("type", "string")
            .put("description", "'files_with_matches' | 'content' | 'count'. Default files_with_matches.");
        props.putObject("case_insensitive").put("type", "boolean")
            .put("description", "Case-insensitive matching. Default false.");
        props.putObject("head_limit").put("type", "integer")
            .put("description", "Cap the number of output lines/entries. Default 250.");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode input) throws Exception {
        String patternStr = input.path("pattern").asText();
        if (patternStr.isBlank()) {
            throw new ToolInvocationException("pattern is required");
        }
        String pathArg = input.path("path").asText("");
        String globArg = input.path("glob").asText("");
        String mode = input.path("output_mode").asText("files_with_matches");
        boolean caseInsensitive = input.path("case_insensitive").asBoolean(false);
        int headLimit = input.path("head_limit").asInt(DEFAULT_HEAD_LIMIT);
        if (headLimit <= 0) headLimit = DEFAULT_HEAD_LIMIT;

        if (!List.of("files_with_matches", "content", "count").contains(mode)) {
            throw new ToolInvocationException("output_mode must be files_with_matches, content, or count");
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr, caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new ToolInvocationException("invalid regex: " + e.getDescription());
        }

        Path searchRoot = pathArg.isBlank()
            ? ctx.workingDir()
            : PathScope.resolve(ctx, pathArg);

        PathMatcher globMatcher = globArg.isBlank()
            ? null
            : FileSystems.getDefault().getPathMatcher("glob:" + globArg);

        List<Path> files = collectFiles(searchRoot);
        Path wd = ctx.workingDir().toRealPath();

        List<String> outLines = new ArrayList<>();
        int totalMatches = 0;
        int filesWithMatches = 0;

        for (Path f : files) {
            Path rel = searchRoot.relativize(f);
            if (globMatcher != null && !globMatcher.matches(rel)) continue;
            if (Files.size(f) > PER_FILE_MAX_BYTES) continue;

            List<String> lines;
            try {
                lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }

            boolean matchedInFile = false;
            int fileMatches = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (pattern.matcher(lines.get(i)).find()) {
                    matchedInFile = true;
                    fileMatches++;
                    if ("content".equals(mode)) {
                        outLines.add(wd.relativize(f) + ":" + (i + 1) + ":" + lines.get(i));
                        if (outLines.size() >= headLimit) break;
                    }
                }
            }
            if (matchedInFile) {
                filesWithMatches++;
                totalMatches += fileMatches;
                if ("files_with_matches".equals(mode)) {
                    outLines.add(wd.relativize(f).toString());
                }
                if ("count".equals(mode)) {
                    outLines.add(wd.relativize(f) + ":" + fileMatches);
                }
            }
            if (outLines.size() >= headLimit && "content".equals(mode)) break;
        }

        if (outLines.isEmpty()) {
            return "No matches for /" + patternStr + "/" + (caseInsensitive ? "i" : "")
                + " under " + ctx.workingDir().relativize(searchRoot);
        }

        if (outLines.size() > headLimit) {
            outLines = outLines.subList(0, headLimit);
        }
        String summary = "\n[" + filesWithMatches + " file(s), " + totalMatches + " match(es)]";
        return String.join("\n", outLines) + summary;
    }

    private static List<Path> collectFiles(Path root) throws IOException {
        if (Files.isRegularFile(root)) return List.of(root);
        List<Path> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(result::add);
        }
        return result;
    }
}
