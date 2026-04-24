package com.workflow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds files whose path matches a glob pattern, sorted by modification time descending.
 * Matches the Claude Code Glob tool's surface.
 */
@Component
public class GlobTool implements Tool {

    private static final int DEFAULT_LIMIT = 250;

    @Autowired(required = false)
    private FileSystemCache fileSystemCache;

    @Override
    public String name() { return "Glob"; }

    @Override
    public String description() {
        return "Find files matching a glob pattern under the working directory (or a "
            + "narrower path). Returns paths relative to working dir, newest first.";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper om) {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("pattern").put("type", "string")
            .put("description", "Glob pattern, e.g. '**/*.java' or 'src/**/*.ts'.");
        props.putObject("path").put("type", "string")
            .put("description", "Directory to search under. Defaults to working dir.");
        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public String execute(ToolContext ctx, JsonNode input) throws Exception {
        String pattern = input.path("pattern").asText();
        if (pattern.isBlank()) {
            throw new ToolInvocationException("pattern is required");
        }
        String pathArg = input.path("path").asText("");

        Path searchRoot = pathArg.isBlank()
            ? ctx.workingDir()
            : PathScope.resolve(ctx, pathArg);
        if (!Files.isDirectory(searchRoot)) {
            throw new ToolInvocationException("search path is not a directory: " + searchRoot);
        }

        // Cache key is (workingDir, normalizedPattern) — generation handles invalidation
        String cachePattern = searchRoot + "|" + pattern;
        if (fileSystemCache != null) {
            List<String> cached = fileSystemCache.getGlob(ctx.workingDir(), cachePattern);
            if (cached != null) {
                return cached.isEmpty()
                    ? "No files match pattern '" + pattern + "' under " + ctx.workingDir().relativize(searchRoot)
                    : String.join("\n", cached) + "\n";
            }
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        List<Path> matches = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(searchRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> matcher.matches(searchRoot.relativize(p)))
                .forEach(matches::add);
        } catch (IOException e) {
            throw new ToolInvocationException("failed to walk " + searchRoot + ": " + e.getMessage(), e);
        }

        matches.sort(Comparator.comparing(GlobTool::safeMtime).reversed());
        if (matches.size() > DEFAULT_LIMIT) {
            matches = matches.subList(0, DEFAULT_LIMIT);
        }

        List<String> relPaths = matches.stream()
            .map(p -> ctx.workingDir().relativize(p).toString())
            .toList();

        if (fileSystemCache != null) {
            fileSystemCache.putGlob(ctx.workingDir(), cachePattern, relPaths);
        }

        if (relPaths.isEmpty()) {
            return "No files match pattern '" + pattern + "' under " + ctx.workingDir().relativize(searchRoot);
        }

        return String.join("\n", relPaths) + "\n";
    }

    private static long safeMtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
