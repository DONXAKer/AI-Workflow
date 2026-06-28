package com.workflow.knowledge;

import com.workflow.knowledge.ImportGraphExtractor.Edge;
import com.workflow.knowledge.ImportGraphExtractor.RepoIndex;
import com.workflow.project.Project;
import com.workflow.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds and queries the per-project file-level import graph ({@link ProjectIndexEdge}).
 * Independent of Qdrant — works on Postgres alone. Full rebuild is invoked from the
 * project reindex endpoint; per-file delta is invoked after a pipeline run. Queries back
 * the {@code Deps} tool.
 */
@Service
public class ImportGraphService {

    private static final Logger log = LoggerFactory.getLogger(ImportGraphService.class);

    private static final Set<String> GRAPH_EXTS = Set.of(
        "java", "ts", "tsx", "js", "jsx", "mts", "cts", "py", "go");
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "build", "target", "dist", "out", "vendor",
        ".venv", "venv", "__pycache__", ".gradle", ".idea", "bin");
    private static final long MAX_FILE_BYTES = 1_000_000;
    private static final Pattern GO_MODULE = Pattern.compile("^module\\s+(\\S+)", Pattern.MULTILINE);

    private final ProjectRepository projectRepo;
    private final ProjectIndexEdgeRepository edges;
    private final ImportGraphExtractor extractor = new ImportGraphExtractor();

    @Autowired
    public ImportGraphService(ProjectRepository projectRepo, ProjectIndexEdgeRepository edges) {
        this.projectRepo = projectRepo;
        this.edges = edges;
    }

    public record RebuildResult(int filesScanned, int edges) {}

    public record Neighbor(String path, String direction, String importType, int line, int depth) {}

    public long edgeCount(String slug) { return edges.countByProjectSlug(slug); }

    // ---- build -------------------------------------------------------------------------

    /** Full rebuild: replaces all edges for the project. Returns counts (no-op if no workingDir). */
    public RebuildResult rebuild(String slug) {
        Path root = resolveWorkingDir(slug);
        if (root == null) return new RebuildResult(0, 0);

        List<String> relFiles = walkRepo(root);
        RepoIndex repo = new RepoIndex(new HashSet<>(relFiles), readGoModule(root));

        List<ProjectIndexEdge> rows = new ArrayList<>();
        int scanned = 0;
        for (String rel : relFiles) {
            String content = readFile(root.resolve(rel));
            if (content == null) continue;
            scanned++;
            for (Edge e : extractor.extract(rel, content, repo)) {
                rows.add(new ProjectIndexEdge(slug, e.fromPath(), e.toPath(), e.importType(), e.fromLine()));
            }
        }
        edges.deleteByProjectSlug(slug);
        edges.saveAll(dedupe(rows));
        log.info("import-graph rebuild({}) — {} files scanned, {} edges", slug, scanned, rows.size());
        return new RebuildResult(scanned, rows.size());
    }

    /** Delta: re-extract outgoing edges for changed files only (best-effort, no-op without workingDir). */
    public void updateDelta(String slug, List<String> changedRelPaths) {
        if (changedRelPaths == null || changedRelPaths.isEmpty()) return;
        Path root = resolveWorkingDir(slug);
        if (root == null) return;
        RepoIndex repo = new RepoIndex(new HashSet<>(walkRepo(root)), readGoModule(root));
        for (String rel : changedRelPaths) {
            String norm = rel.replace('\\', '/');
            edges.deleteByProjectSlugAndFromPath(slug, norm);
            String content = readFile(root.resolve(norm));
            if (content == null) continue;
            List<ProjectIndexEdge> rows = new ArrayList<>();
            for (Edge e : extractor.extract(norm, content, repo)) {
                rows.add(new ProjectIndexEdge(slug, e.fromPath(), e.toPath(), e.importType(), e.fromLine()));
            }
            edges.saveAll(dedupe(rows));
        }
    }

    public void rebuildAsync(String slug) {
        Long tenantId = com.workflow.account.TenantContext.get();
        String scopeSlug = com.workflow.project.ProjectContext.get();
        Thread.startVirtualThread(() -> withScope(tenantId, scopeSlug, () -> {
            try { rebuild(slug); }
            catch (Exception e) { log.warn("import-graph rebuildAsync({}) failed: {}", slug, e.getMessage()); }
        }));
    }

    public void updateDeltaAsync(String slug, List<String> changedRelPaths) {
        Long tenantId = com.workflow.account.TenantContext.get();
        String scopeSlug = com.workflow.project.ProjectContext.get();
        Thread.startVirtualThread(() -> withScope(tenantId, scopeSlug, () -> {
            try { updateDelta(slug, changedRelPaths); }
            catch (Exception e) { log.warn("import-graph updateDeltaAsync({}) failed: {}", slug, e.getMessage()); }
        }));
    }

    // ---- query (Deps tool) -------------------------------------------------------------

    /**
     * BFS over the graph from {@code relPath}. {@code direction}: out | in | both.
     * {@code depth} ≥ 1 (transitive), result count capped at {@code maxNodes}.
     */
    public List<Neighbor> query(String slug, String relPath, String direction, int depth, int maxNodes) {
        boolean wantOut = !"in".equals(direction);
        boolean wantIn = !"out".equals(direction);
        List<Neighbor> out = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        visited.add(relPath);
        List<String> frontier = List.of(relPath);

        for (int d = 1; d <= depth && out.size() < maxNodes; d++) {
            List<String> next = new ArrayList<>();
            for (String node : frontier) {
                if (wantOut) {
                    for (ProjectIndexEdge e : edges.findByProjectSlugAndFromPath(slug, node)) {
                        if (out.size() >= maxNodes) break;
                        if (visited.add(e.getToPath())) {
                            out.add(new Neighbor(e.getToPath(), "out", e.getImportType(), e.getFromLine(), d));
                            next.add(e.getToPath());
                        }
                    }
                }
                if (wantIn) {
                    for (ProjectIndexEdge e : edges.findByProjectSlugAndToPath(slug, node)) {
                        if (out.size() >= maxNodes) break;
                        if (visited.add(e.getFromPath())) {
                            out.add(new Neighbor(e.getFromPath(), "in", e.getImportType(), e.getFromLine(), d));
                            next.add(e.getFromPath());
                        }
                    }
                }
            }
            frontier = next;
            if (frontier.isEmpty()) break;
        }
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------

    private List<String> walkRepo(Path root) {
        List<String> rel = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                Path r = root.relativize(p);
                for (Path seg : r) if (SKIP_DIRS.contains(seg.toString())) return;
                String rp = r.toString().replace('\\', '/');
                String ext = rp.substring(rp.lastIndexOf('.') + 1).toLowerCase();
                if (GRAPH_EXTS.contains(ext)) rel.add(rp);
            });
        } catch (IOException e) {
            log.warn("import-graph walk failed for {}: {}", root, e.getMessage());
        }
        return rel;
    }

    private String readGoModule(Path root) {
        Path goMod = root.resolve("go.mod");
        if (!Files.isRegularFile(goMod)) return null;
        try {
            Matcher m = GO_MODULE.matcher(Files.readString(goMod));
            return m.find() ? m.group(1) : null;
        } catch (IOException e) { return null; }
    }

    private String readFile(Path p) {
        try {
            if (!Files.isRegularFile(p) || Files.size(p) > MAX_FILE_BYTES) return null;
            return Files.readString(p);
        } catch (IOException e) {
            return null;   // binary / unreadable — skip
        }
    }

    private static List<ProjectIndexEdge> dedupe(List<ProjectIndexEdge> rows) {
        Map<String, ProjectIndexEdge> seen = new LinkedHashMap<>();
        for (ProjectIndexEdge e : rows) {
            seen.putIfAbsent(e.getFromPath() + " " + e.getToPath() + " " + e.getFromLine(), e);
        }
        return new ArrayList<>(seen.values());
    }

    private Path resolveWorkingDir(String slug) {
        Optional<Project> p = projectRepo.findBySlug(slug);
        if (p.isEmpty() || p.get().getWorkingDir() == null || p.get().getWorkingDir().isBlank()) {
            log.warn("import-graph: project '{}' has no workingDir", slug);
            return null;
        }
        return Paths.get(p.get().getWorkingDir()).toAbsolutePath();
    }

    private static void withScope(Long tenantId, String scopeSlug, Runnable body) {
        if (tenantId != null) com.workflow.account.TenantContext.set(tenantId);
        if (scopeSlug != null) com.workflow.project.ProjectContext.set(scopeSlug);
        try { body.run(); }
        finally {
            com.workflow.project.ProjectContext.clear();
            com.workflow.account.TenantContext.clear();
        }
    }
}
