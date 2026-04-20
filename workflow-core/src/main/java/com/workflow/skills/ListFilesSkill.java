package com.workflow.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skill: list files matching a glob pattern inside a directory.
 */
@Component
public class ListFilesSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(ListFilesSkill.class);
    private static final int MAX_FILES = 200;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "list_files";
    }

    @Override
    public String getDescription() {
        return "List files matching a glob pattern inside a directory (up to 200 results). Useful for exploring project structure.";
    }

    @Override
    public ObjectNode getInputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode dir = props.putObject("directory");
        dir.put("type", "string");
        dir.put("description", "Root directory to search in (default: current working directory).");

        ObjectNode glob = props.putObject("pattern");
        glob.put("type", "string");
        glob.put("description", "Glob pattern relative to directory, e.g. '**/*.java' or 'src/**/*.ts' (default: '*').");

        schema.putArray("required");
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String dirStr = params.containsKey("directory") ? String.valueOf(params.get("directory")) : ".";
        String globPattern = params.containsKey("pattern") ? String.valueOf(params.get("pattern")) : "*";

        Path root = Paths.get(dirStr).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return Map.of("error", "Not a directory: " + dirStr);
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
        List<String> files = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (files.size() >= MAX_FILES) return FileVisitResult.TERMINATE;
                Path relative = root.relativize(file);
                if (matcher.matches(relative) || matcher.matches(file.getFileName())) {
                    files.add(relative.toString());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        log.debug("list_files: dir='{}' pattern='{}' -> {} files", dirStr, globPattern, files.size());
        return Map.of(
            "directory", dirStr,
            "pattern", globPattern,
            "files", files,
            "count", files.size()
        );
    }
}
