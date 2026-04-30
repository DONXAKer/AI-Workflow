package com.workflow.tools;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds a short, depth-limited textual tree of a project's working directory for
 * inclusion in agent prompts. The goal is to spend a couple-of-KB of prompt budget
 * once instead of letting the agent burn 5-8 iterations Glob-ing the same structure.
 *
 * <p>Skips noisy directories ({@code .git}, {@code build}, {@code node_modules}, etc.)
 * and caps total entries to {@link #MAX_ENTRIES} so output stays bounded for huge
 * repos.
 */
public final class ProjectTreeSummary {

    /** Max lines emitted; keeps the prompt slice tractable on large monorepos. */
    public static final int MAX_ENTRIES = 200;

    /** Default scan depth from the working dir. Source layouts (`a/b/c/d/Foo.java`) often go 4-6 deep — we go shallow on purpose; the agent still has Read/Glob if it needs more. */
    private static final int DEFAULT_DEPTH = 4;

    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", ".gradle", ".idea", ".vscode", ".vs", ".cache", ".venv",
        "node_modules", "build", "dist", "target", "out", "bin",
        ".next", ".nuxt", "__pycache__", ".pytest_cache",
        "Saved", "Intermediate", "Binaries", "DerivedDataCache" // Unreal
    );

    private ProjectTreeSummary() {}

    /**
     * Returns a single string listing relative paths under {@code workingDir}, one per
     * line, up to {@link #MAX_ENTRIES}. Returns an empty string when the dir doesn't
     * exist or is unreadable.
     */
    public static String summarise(Path workingDir) {
        return summarise(workingDir, DEFAULT_DEPTH, MAX_ENTRIES);
    }

    public static String summarise(Path workingDir, int maxDepth, int maxEntries) {
        if (workingDir == null || !Files.isDirectory(workingDir)) return "";
        List<String> entries = new ArrayList<>();
        try {
            Files.walkFileTree(workingDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (entries.size() >= maxEntries) return FileVisitResult.TERMINATE;
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (!dir.equals(workingDir) && SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    int depth = workingDir.relativize(dir).getNameCount();
                    if (depth > maxDepth) return FileVisitResult.SKIP_SUBTREE;
                    if (!dir.equals(workingDir)) {
                        entries.add(workingDir.relativize(dir).toString().replace('\\', '/') + "/");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (entries.size() >= maxEntries) return FileVisitResult.TERMINATE;
                    int depth = workingDir.relativize(file).getNameCount();
                    if (depth > maxDepth) return FileVisitResult.CONTINUE;
                    entries.add(workingDir.relativize(file).toString().replace('\\', '/'));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return "";
        }
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String e : entries) sb.append(e).append('\n');
        if (entries.size() == maxEntries) {
            sb.append("... (truncated at ").append(maxEntries).append(" entries — use Glob for deeper paths)\n");
        }
        return sb.toString();
    }
}
