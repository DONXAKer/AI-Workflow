package com.workflow.knowledge;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Per-(project, file) record of what's currently indexed in Qdrant.
 *
 * <p>{@link #fileHash} is recomputed on each indexer run; if it matches what's stored,
 * the file is skipped — that's the fast path for incremental reindex. {@link #chunkCount}
 * tells the indexer how many points to delete from Qdrant when the file is re-chunked
 * (point ids are deterministic by chunk index, but we need the count to enumerate them).
 */
@Entity
@Table(name = "project_index_entry",
       uniqueConstraints = @UniqueConstraint(columnNames = {"project_slug", "path"}),
       indexes = @Index(columnList = "project_slug"))
public class ProjectIndexEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_slug", nullable = false)
    private String projectSlug;

    /** File path relative to {@code Project.workingDir}, forward-slash separated. */
    @Column(nullable = false, length = 1024)
    private String path;

    /** SHA-256 of file content (hex) — drives the "did this change" check. */
    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    /** Number of chunks produced for this file last time it was indexed. */
    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    /** Total line count of the file at last index — used for the Write-guard. */
    @Column(name = "line_count", nullable = false)
    private int lineCount;

    /** Embedding model used (e.g. nomic-embed-text:v1.5) — switches force re-embed. */
    @Column(name = "embed_model", nullable = false, length = 100)
    private String embedModel;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;

    public ProjectIndexEntry() {}

    public ProjectIndexEntry(String projectSlug, String path, String fileHash,
                             int chunkCount, int lineCount, String embedModel) {
        this.projectSlug = projectSlug;
        this.path = path;
        this.fileHash = fileHash;
        this.chunkCount = chunkCount;
        this.lineCount = lineCount;
        this.embedModel = embedModel;
        this.indexedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getProjectSlug() { return projectSlug; }
    public void setProjectSlug(String s) { this.projectSlug = s; }
    public String getPath() { return path; }
    public void setPath(String p) { this.path = p; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String h) { this.fileHash = h; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int c) { this.chunkCount = c; }
    public int getLineCount() { return lineCount; }
    public void setLineCount(int c) { this.lineCount = c; }
    public String getEmbedModel() { return embedModel; }
    public void setEmbedModel(String m) { this.embedModel = m; }
    public Instant getIndexedAt() { return indexedAt; }
    public void setIndexedAt(Instant t) { this.indexedAt = t; }
}
