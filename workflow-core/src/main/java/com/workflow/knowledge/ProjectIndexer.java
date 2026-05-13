package com.workflow.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Walks a project's {@code workingDir}, splits source files into overlapping line-based
 * chunks, embeds them via {@link OllamaEmbedder}, and upserts vectors into the per-project
 * Qdrant collection {@code code_<slug>}.
 *
 * <p>Two operations:
 * <ul>
 *   <li>{@link #reindexFull} — full sweep with {@code file_hash}-based skip
 *       (changed files re-embedded, untouched ones untouched, deleted ones removed).</li>
 *   <li>{@link #reindexDelta} — re-embed only the given list of changed paths. Cheaper for
 *       post-run hooks ({@code git diff --name-only}).</li>
 * </ul>
 *
 * <p>Chunking is intentionally simple (line-based, fixed size, overlap) — symbol-aware
 * splitting via tree-sitter is out of scope for v1 and would lock the indexer to a per-language
 * parser stack.
 *
 * <p>Failures (Ollama down, Qdrant down, I/O error on one file) are logged but do not
 * propagate: the indexer returns partial progress so a flaky Ollama doesn't black-hole
 * an entire project.
 */
@Component
public class ProjectIndexer {

    private static final Logger log = LoggerFactory.getLogger(ProjectIndexer.class);

    /** Default chunk width in lines — covers a typical method + surrounding context. */
    private static final int DEFAULT_CHUNK_LINES = 200;
    private static final int DEFAULT_CHUNK_OVERLAP = 30;
    /** Skip files this big — likely generated, lock files, vendored bundles, etc. */
    private static final long MAX_FILE_BYTES = 1_000_000L;
    private static final int EMBED_BATCH_SIZE = 16;

    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "build", "target", "dist", "out",
        ".gradle", ".idea", ".vscode", "__pycache__", ".venv", "venv",
        ".workflow", ".mypy_cache", ".pytest_cache", "coverage");

    /**
     * Source-y file types we want to index. Anything else (binary assets, lock files,
     * images, archives) is skipped.
     */
    private static final Set<String> INDEXED_EXTENSIONS = Set.of(
        "java", "kt", "kts", "scala", "groovy",
        "py", "rb", "go", "rs", "swift",
        "ts", "tsx", "js", "jsx", "mjs", "cjs",
        "c", "cc", "cpp", "cxx", "h", "hpp",
        "cs", "vb",
        "yaml", "yml", "json", "toml", "xml",
        "md", "rst",
        "sh", "bash", "zsh", "ps1",
        "gradle", "pom", "sbt",
        "tf", "hcl", "lua",
        "sql",
        "html", "css", "scss");

    private final QdrantClient qdrant;
    private final OllamaEmbedder embedder;
    private final ProjectIndexEntryRepository entries;
    private final ObjectMapper mapper;
    private final int chunkLines;
    private final int chunkOverlap;

    @Autowired
    public ProjectIndexer(QdrantClient qdrant, OllamaEmbedder embedder,
                          ProjectIndexEntryRepository entries, ObjectMapper mapper,
                          @Value("${workflow.knowledge.chunk-lines:200}") int chunkLines,
                          @Value("${workflow.knowledge.chunk-overlap:30}") int chunkOverlap) {
        this.qdrant = qdrant;
        this.embedder = embedder;
        this.entries = entries;
        this.mapper = mapper;
        this.chunkLines = chunkLines > 0 ? chunkLines : DEFAULT_CHUNK_LINES;
        this.chunkOverlap = chunkOverlap >= 0 ? chunkOverlap : DEFAULT_CHUNK_OVERLAP;
    }

    /** Per-file progress hook for UI polling. {@code currentFile} is the file just processed. */
    @FunctionalInterface
    public interface ProgressCallback {
        void onFile(String currentFile, int processed, int total);
    }

    public Report reindexFull(String projectSlug, Path workingDir) {
        return reindexFull(projectSlug, workingDir, null);
    }

    public Report reindexFull(String projectSlug, Path workingDir, ProgressCallback progress) {
        long startedAt = System.currentTimeMillis();
        if (!qdrant.isEnabled()) {
            log.info("Qdrant not configured — reindexFull is a no-op for {}", projectSlug);
            return new Report(0, 0, 0, 0, 0);
        }
        if (workingDir == null || !Files.isDirectory(workingDir)) {
            log.warn("reindexFull: workingDir not a directory for {} — {}", projectSlug, workingDir);
            return new Report(0, 0, 0, 0, 0);
        }

        // Step 0: ensure target collection exists with right dim. Probe-embed a tiny
        // string to learn the model's vector size before touching the collection.
        Integer dim = probeDimension();
        if (dim == null) {
            log.warn("reindexFull: cannot determine embedding dim for {} — embedder not responding", projectSlug);
            return new Report(0, 0, 0, 0, 0);
        }
        String collection = QdrantKnowledgeBase.collectionName(projectSlug);
        qdrant.ensureCollection(collection, dim);

        // Step 1: walk filesystem, gather candidate files
        List<Path> candidates = new ArrayList<>();
        try {
            Files.walkFileTree(workingDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (SKIP_DIRS.contains(name) || name.startsWith(".") && !dir.equals(workingDir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.size() > MAX_FILE_BYTES) return FileVisitResult.CONTINUE;
                    if (!isIndexableExtension(file)) return FileVisitResult.CONTINUE;
                    candidates.add(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("reindexFull: walk failed for {} — {}", workingDir, e.getMessage());
            return new Report(0, 0, 0, 0, 0);
        }

        // Step 2: remove DB entries for files that disappeared
        Set<String> existingRelPaths = new HashSet<>();
        for (Path p : candidates) {
            existingRelPaths.add(toRel(workingDir, p));
        }
        List<ProjectIndexEntry> previousEntries = entries.findByProjectSlug(projectSlug);
        List<String> orphanedPaths = new ArrayList<>();
        for (ProjectIndexEntry e : previousEntries) {
            if (!existingRelPaths.contains(e.getPath())) {
                orphanedPaths.add(e.getPath());
            }
        }
        if (!orphanedPaths.isEmpty()) {
            qdrant.deleteByPaths(collection, orphanedPaths);
            for (String p : orphanedPaths) entries.deleteByProjectSlugAndPath(projectSlug, p);
            log.info("reindexFull({}): removed {} orphaned files", projectSlug, orphanedPaths.size());
        }

        // Step 3: process each file — skip-if-unchanged, otherwise chunk+embed+upsert
        int processedFiles = 0;
        int skippedFiles = 0;
        int upsertedChunks = 0;
        int failedFiles = 0;
        String embedModel = embedder.modelName();
        int totalCandidates = candidates.size();
        int doneSoFar = 0;
        for (Path file : candidates) {
            String relPath = toRel(workingDir, file);
            try {
                if (progress != null) progress.onFile(relPath, doneSoFar, totalCandidates);
                int n = indexOneFile(projectSlug, collection, relPath, file, embedModel);
                if (n < 0) skippedFiles++;
                else { processedFiles++; upsertedChunks += n; }
            } catch (Exception e) {
                failedFiles++;
                log.warn("reindexFull: failed file {} — {}", relPath, e.getMessage());
            }
            doneSoFar++;
        }
        if (progress != null) progress.onFile("", doneSoFar, totalCandidates);
        long elapsed = System.currentTimeMillis() - startedAt;
        log.info("reindexFull({}): {} files indexed, {} skipped (unchanged), {} orphaned removed, {} failed, {} chunks upserted, {}ms",
            projectSlug, processedFiles, skippedFiles, orphanedPaths.size(), failedFiles, upsertedChunks, elapsed);
        return new Report(processedFiles, skippedFiles, orphanedPaths.size(), upsertedChunks, elapsed);
    }

    public Report reindexDelta(String projectSlug, Path workingDir, List<String> changedRelPaths) {
        long startedAt = System.currentTimeMillis();
        if (!qdrant.isEnabled() || changedRelPaths == null || changedRelPaths.isEmpty()) {
            return new Report(0, 0, 0, 0, 0);
        }
        Integer dim = probeDimension();
        if (dim == null) return new Report(0, 0, 0, 0, 0);
        String collection = QdrantKnowledgeBase.collectionName(projectSlug);
        qdrant.ensureCollection(collection, dim);

        int processed = 0, upserted = 0, removed = 0, failed = 0;
        List<String> toDelete = new ArrayList<>();
        String embedModel = embedder.modelName();
        for (String relPath : changedRelPaths) {
            Path file = workingDir.resolve(relPath).normalize();
            if (!file.startsWith(workingDir)) continue;
            if (!Files.exists(file)) {
                // File was deleted in the diff — drop from index
                toDelete.add(relPath);
                entries.deleteByProjectSlugAndPath(projectSlug, relPath);
                removed++;
                continue;
            }
            if (!isIndexableExtension(file)) continue;
            try {
                int n = indexOneFile(projectSlug, collection, relPath, file, embedModel);
                if (n >= 0) { processed++; upserted += n; }
            } catch (Exception e) {
                failed++;
                log.warn("reindexDelta: failed {} — {}", relPath, e.getMessage());
            }
        }
        if (!toDelete.isEmpty()) qdrant.deleteByPaths(collection, toDelete);
        long elapsed = System.currentTimeMillis() - startedAt;
        log.info("reindexDelta({}): {} files re-indexed, {} removed, {} failed, {} chunks upserted, {}ms",
            projectSlug, processed, removed, failed, upserted, elapsed);
        return new Report(processed, 0, removed, upserted, elapsed);
    }

    /** @return chunk count just upserted, or {@code -1} if the file was skipped (unchanged). */
    private int indexOneFile(String projectSlug, String collection, String relPath, Path absolute,
                              String embedModel) throws Exception {
        List<String> lines;
        try {
            lines = Files.readAllLines(absolute, StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException e) {
            // Non-UTF-8 file (legacy encoding, binary masquerading as text) — skip silently.
            return -1;
        }
        String hash = sha256(String.join("\n", lines));
        Optional<ProjectIndexEntry> prev = entries.findByProjectSlugAndPath(projectSlug, relPath);
        if (prev.isPresent()
            && prev.get().getFileHash().equals(hash)
            && prev.get().getEmbedModel().equals(embedModel)) {
            return -1;  // unchanged + same embed model = nothing to do
        }

        List<Chunk> chunks = chunk(lines);
        if (chunks.isEmpty()) {
            // Empty or whitespace-only file — drop any old entry and skip
            if (prev.isPresent()) {
                qdrant.deleteByPaths(collection, List.of(relPath));
                entries.deleteByProjectSlugAndPath(projectSlug, relPath);
            }
            return 0;
        }

        // Embed in batches to limit memory and let the embedder pack a few inputs per request
        List<QdrantClient.Point> points = new ArrayList<>(chunks.size());
        for (int batchStart = 0; batchStart < chunks.size(); batchStart += EMBED_BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + EMBED_BATCH_SIZE, chunks.size());
            List<String> texts = new ArrayList<>(batchEnd - batchStart);
            for (int i = batchStart; i < batchEnd; i++) texts.add(chunks.get(i).text);
            List<float[]> vectors = embedder.embedBatch(texts);
            if (vectors.size() != texts.size()) {
                log.warn("indexOneFile({}): embedder returned {} vecs for {} inputs, skipping batch",
                    relPath, vectors.size(), texts.size());
                continue;
            }
            for (int i = batchStart; i < batchEnd; i++) {
                Chunk c = chunks.get(i);
                ObjectNode payload = mapper.createObjectNode();
                payload.put("project", projectSlug);
                payload.put("path", relPath);
                payload.put("start_line", c.startLine);
                payload.put("end_line", c.endLine);
                payload.put("content", c.text);
                payload.put("file_hash", hash);
                payload.put("indexed_at", Instant.now().toString());
                points.add(new QdrantClient.Point(
                    QdrantClient.pointId(projectSlug, relPath, i),
                    vectors.get(i - batchStart),
                    payload));
            }
        }
        if (points.isEmpty()) return 0;

        // Drop the prior version's points (chunk count might shrink) before upserting the new set
        if (prev.isPresent()) qdrant.deleteByPaths(collection, List.of(relPath));
        boolean ok = qdrant.upsert(collection, points);
        if (!ok) return 0;

        ProjectIndexEntry entry = prev.orElseGet(ProjectIndexEntry::new);
        entry.setProjectSlug(projectSlug);
        entry.setPath(relPath);
        entry.setFileHash(hash);
        entry.setChunkCount(points.size());
        entry.setLineCount(lines.size());
        entry.setEmbedModel(embedModel);
        entry.setIndexedAt(Instant.now());
        entries.save(entry);
        return points.size();
    }

    private List<Chunk> chunk(List<String> lines) {
        List<Chunk> out = new ArrayList<>();
        if (lines.isEmpty()) return out;
        int step = Math.max(1, chunkLines - chunkOverlap);
        for (int start = 0; start < lines.size(); start += step) {
            int end = Math.min(start + chunkLines, lines.size());
            StringBuilder sb = new StringBuilder();
            boolean nonBlank = false;
            for (int i = start; i < end; i++) {
                String line = lines.get(i);
                if (!line.isBlank()) nonBlank = true;
                sb.append(line).append('\n');
            }
            if (!nonBlank) continue;
            out.add(new Chunk(sb.toString(), start + 1, end));
            if (end == lines.size()) break;
        }
        return out;
    }

    private Integer probeDimension() {
        float[] probe = embedder.embedOne("probe");
        if (probe == null || probe.length == 0) return null;
        return probe.length;
    }

    private static String toRel(Path workingDir, Path file) {
        return workingDir.relativize(file).toString().replace('\\', '/');
    }

    private static boolean isIndexableExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return INDEXED_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Single chunk of source text + the line range it covers (1-based, inclusive). */
    private record Chunk(String text, int startLine, int endLine) {}

    /** Summary of one indexer run — what was processed / skipped / removed. */
    public record Report(int processed, int skippedUnchanged, int removedOrphan,
                         int chunksUpserted, long tookMs) {}
}
