package com.workflow.tools;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JVM-scoped cache for filesystem reads. Avoids redundant disk I/O across tool-use
 * iterations within a run, or across consecutive runs on the same project.
 *
 * <p>Two caches:
 * <ul>
 *   <li><b>Glob</b>: {@code (workingDir, pattern, generation)} → {@code List<String>} of
 *       relative paths. Invalidated as a group by bumping a per-directory generation counter
 *       — no need to enumerate cache keys.
 *   <li><b>Read</b>: {@code absolutePath} → {@code ReadEntry(lines, mtime)}. Validated on
 *       access: if the file's mtime changed the entry is silently dropped.
 * </ul>
 *
 * <p>Invalidation is triggered by mutating tools (Write, Edit) and by Bash commands that
 * are not on the read-only whitelist.
 */
@Component
public class FileSystemCache {

    private static final Logger log = LoggerFactory.getLogger(FileSystemCache.class);

    // Bash command prefixes that are known to be read-only — no invalidation needed
    static final Set<String> READ_ONLY_BASH_PREFIXES = Set.of(
        "git log", "git status", "git diff", "git show", "git branch", "git tag",
        "git blame", "git describe", "cat ", "find ", "grep ", "ls ", "ls\n", "ls",
        "echo ", "pwd", "which ", "wc ", "head ", "tail ", "file ", "stat ",
        "du ", "df ", "env ", "printenv"
    );

    // Glob cache — key includes directory generation to enable O(1) bulk invalidation
    private final Cache<String, List<String>> globCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .build();

    // Read cache — entry carries mtime for staleness check
    private final Cache<String, ReadEntry> readCache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build();

    // Per-directory generation counters for Glob invalidation
    private final ConcurrentHashMap<String, AtomicLong> dirGenerations = new ConcurrentHashMap<>();

    // ── Glob ──────────────────────────────────────────────────────────────────

    public List<String> getGlob(Path workingDir, String pattern) {
        return globCache.getIfPresent(globKey(workingDir, pattern));
    }

    public void putGlob(Path workingDir, String pattern, List<String> paths) {
        globCache.put(globKey(workingDir, pattern), List.copyOf(paths));
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public List<String> getRead(Path file) {
        ReadEntry entry = readCache.getIfPresent(file.toString());
        if (entry == null) return null;
        try {
            long currentMtime = Files.getLastModifiedTime(file).toMillis();
            if (currentMtime != entry.mtime()) {
                readCache.invalidate(file.toString());
                return null;
            }
            return entry.lines();
        } catch (IOException e) {
            readCache.invalidate(file.toString());
            return null;
        }
    }

    public void putRead(Path file, List<String> lines) {
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            readCache.put(file.toString(), new ReadEntry(lines, mtime));
        } catch (IOException e) {
            // skip caching if we can't read mtime
        }
    }

    // ── Invalidation ──────────────────────────────────────────────────────────

    /** Invalidate a single file in the read cache (used by Write/Edit). */
    public void invalidateFile(Path file) {
        readCache.invalidate(file.toString());
        log.debug("cache: invalidated read entry for {}", file);
    }

    /**
     * Invalidate all glob results for a directory and its read entries under it.
     * Called when any file-mutating operation happens inside workingDir.
     */
    public void invalidateDirectory(Path workingDir) {
        String dirKey = workingDir.toString();
        dirGenerations.computeIfAbsent(dirKey, k -> new AtomicLong(0)).incrementAndGet();
        log.debug("cache: invalidated glob+read for directory {}", workingDir);
    }

    /** True if a Bash command is known to be read-only and does not need cache invalidation. */
    public boolean isBashReadOnly(String command) {
        if (command == null || command.isBlank()) return true;
        String trimmed = command.stripLeading();
        for (String prefix : READ_ONLY_BASH_PREFIXES) {
            if (trimmed.startsWith(prefix)) return true;
        }
        return false;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private String globKey(Path workingDir, String pattern) {
        String dirKey = workingDir.toString();
        long gen = dirGenerations.computeIfAbsent(dirKey, k -> new AtomicLong(0)).get();
        return dirKey + "|" + pattern + "|" + gen;
    }

    record ReadEntry(List<String> lines, long mtime) {}
}
