package com.workflow.knowledge;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One directed edge of the file-level import graph: {@code fromPath} imports {@code toPath}
 * (both relative to {@code Project.workingDir}, forward-slash separated). Built by
 * {@code ImportGraphService} from a deterministic, language-specific import scan — no AST.
 *
 * <p>Lives next to {@link ProjectIndexEntry} (the vector-index bookkeeping) but is
 * independent of Qdrant: the graph works even when the knowledge layer is disabled.
 * The {@code Deps} tool queries these rows so an agent can ask "what does X depend on /
 * what depends on X" before editing.
 */
@Entity
@Table(name = "project_index_edge",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_edge_unique",
           columnNames = {"project_slug", "from_path", "to_path", "from_line"}),
       indexes = {
           @Index(name = "idx_edge_from", columnList = "project_slug,from_path"),
           @Index(name = "idx_edge_to", columnList = "project_slug,to_path")
       })
public class ProjectIndexEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_slug", nullable = false)
    private String projectSlug;

    /** Source file (the one containing the import statement). */
    @Column(name = "from_path", nullable = false, length = 1024)
    private String fromPath;

    /** Target file resolved inside the repo (the dependency). External libs are not stored. */
    @Column(name = "to_path", nullable = false, length = 1024)
    private String toPath;

    /** Kind of edge: "import" | "static_import" | "from_import" | "go_import". */
    @Column(name = "import_type", nullable = false, length = 32)
    private String importType;

    /** 1-based line where the import statement occurs in {@code fromPath}. */
    @Column(name = "from_line", nullable = false)
    private int fromLine;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;

    public ProjectIndexEdge() {}

    public ProjectIndexEdge(String projectSlug, String fromPath, String toPath,
                            String importType, int fromLine) {
        this.projectSlug = projectSlug;
        this.fromPath = fromPath;
        this.toPath = toPath;
        this.importType = importType;
        this.fromLine = fromLine;
        this.indexedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getProjectSlug() { return projectSlug; }
    public void setProjectSlug(String s) { this.projectSlug = s; }
    public String getFromPath() { return fromPath; }
    public void setFromPath(String p) { this.fromPath = p; }
    public String getToPath() { return toPath; }
    public void setToPath(String p) { this.toPath = p; }
    public String getImportType() { return importType; }
    public void setImportType(String t) { this.importType = t; }
    public int getFromLine() { return fromLine; }
    public void setFromLine(int l) { this.fromLine = l; }
    public Instant getIndexedAt() { return indexedAt; }
    public void setIndexedAt(Instant t) { this.indexedAt = t; }
}
