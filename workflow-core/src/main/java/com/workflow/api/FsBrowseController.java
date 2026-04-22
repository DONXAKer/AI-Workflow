package com.workflow.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fs")
public class FsBrowseController {

    private final Path rootPath;

    public FsBrowseController(
            @Value("${workflow.browse-root:#{systemProperties['user.home']}}") String browseRoot) {
        this.rootPath = Paths.get(browseRoot).toAbsolutePath().normalize();
    }

    @GetMapping("/browse")
    public ResponseEntity<?> browse(@RequestParam(required = false) String path) throws IOException {
        Path target;
        if (path == null || path.isBlank()) {
            target = rootPath;
        } else {
            target = Paths.get(path).toAbsolutePath().normalize();
        }

        // Security: stay within rootPath
        if (!target.startsWith(rootPath)) {
            target = rootPath;
        }

        File dir = target.toFile();
        if (!dir.exists() || !dir.isDirectory()) {
            target = rootPath;
            dir = target.toFile();
        }

        File[] entries = dir.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        List<String> dirs = entries == null ? List.of() :
                Arrays.stream(entries)
                        .map(File::getName)
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();

        String parent = target.equals(rootPath) ? null : target.getParent().toString();

        return ResponseEntity.ok(Map.of(
                "path", target.toString(),
                "parent", parent != null ? parent : "",
                "directories", dirs,
                "root", rootPath.toString()
        ));
    }
}
