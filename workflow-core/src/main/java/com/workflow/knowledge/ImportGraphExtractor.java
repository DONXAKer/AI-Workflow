package com.workflow.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, language-specific extractor of file-level import edges — no AST, no Spring.
 * Given a file's content and an index of the repo's files, returns the in-repo files it
 * imports (external libraries are dropped). Deterministic and line-based so each edge
 * carries the line of the import statement.
 *
 * <p>Supported: Java (package→path), TypeScript/JS (relative specifiers), Python
 * (module→file, incl. relative), Go (module-prefixed package dir → its .go files).
 */
public class ImportGraphExtractor {

    /** A resolved directed edge fromPath → toPath. */
    public record Edge(String fromPath, String toPath, String importType, int fromLine) {}

    /**
     * Immutable view of the repo's files used for import resolution. Paths are repo-relative,
     * forward-slash separated.
     */
    public static final class RepoIndex {
        private final Set<String> files;
        private final Map<String, List<String>> byBasename;
        private final String goModulePrefix;   // nullable

        public RepoIndex(Set<String> files, String goModulePrefix) {
            this.files = files;
            this.goModulePrefix = goModulePrefix;
            this.byBasename = new java.util.HashMap<>();
            for (String f : files) {
                String base = f.substring(f.lastIndexOf('/') + 1);
                byBasename.computeIfAbsent(base, k -> new ArrayList<>()).add(f);
            }
        }

        boolean exists(String rel) { return files.contains(rel); }
        List<String> byBasename(String filename) { return byBasename.getOrDefault(filename, List.of()); }
        String goModulePrefix() { return goModulePrefix; }

        List<String> filesInDir(String dirRel) {
            String prefix = dirRel.isEmpty() ? "" : dirRel + "/";
            List<String> out = new ArrayList<>();
            for (String f : files) {
                if (f.startsWith(prefix) && f.indexOf('/', prefix.length()) < 0) out.add(f);
            }
            return out;
        }
    }

    private static final Pattern JAVA_IMPORT =
        Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+)\\s*;");
    private static final Pattern TS_IMPORT =
        Pattern.compile("(?:import\\b[^'\"]*from|import|export\\b[^'\"]*from|require\\s*\\()\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern PY_FROM = Pattern.compile("^\\s*from\\s+(\\.*)([\\w.]*)\\s+import\\b");
    private static final Pattern PY_IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+)");
    private static final Pattern GO_SINGLE = Pattern.compile("^\\s*import\\s+(?:[\\w.]+\\s+|_\\s+|\\.\\s+)?\"([^\"]+)\"");
    private static final Pattern GO_INBLOCK = Pattern.compile("^\\s*(?:[\\w.]+\\s+|_\\s+|\\.\\s+)?\"([^\"]+)\"");

    private static final List<String> TS_EXTS = List.of(".ts", ".tsx", ".js", ".jsx", ".mts", ".cts");
    private static final int GO_PKG_FILE_CAP = 20;

    public List<Edge> extract(String relPath, String content, RepoIndex repo) {
        String ext = relPath.substring(relPath.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "java" -> extractJava(relPath, content, repo);
            case "ts", "tsx", "js", "jsx", "mts", "cts" -> extractTs(relPath, content, repo);
            case "py" -> extractPy(relPath, content, repo);
            case "go" -> extractGo(relPath, content, repo);
            default -> List.of();
        };
    }

    // ---- Java: import a.b.C; → **/a/b/C.java -------------------------------------------
    private List<Edge> extractJava(String from, String content, RepoIndex repo) {
        List<Edge> edges = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = JAVA_IMPORT.matcher(lines[i]);
            if (!m.find()) continue;
            boolean isStatic = m.group(1) != null;
            String fqcn = m.group(2);
            if (fqcn.endsWith(".*")) continue;                 // wildcard — can't resolve to one file
            if (isStatic) {                                     // static import a.b.C.method → class a.b.C
                int dot = fqcn.lastIndexOf('.');
                if (dot > 0) fqcn = fqcn.substring(0, dot);
            }
            String candidate = fqcn.replace('.', '/') + ".java";
            String resolved = resolveSuffix(repo, candidate);
            if (resolved != null && !resolved.equals(from)) {
                edges.add(new Edge(from, resolved, isStatic ? "static_import" : "import", i + 1));
            }
        }
        return edges;
    }

    // ---- TS/JS: import ... from './x' (relative only) ----------------------------------
    private List<Edge> extractTs(String from, String content, RepoIndex repo) {
        List<Edge> edges = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        String dir = parentDir(from);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TS_IMPORT.matcher(lines[i]);
            while (m.find()) {
                String spec = m.group(1);
                if (!spec.startsWith(".")) continue;            // bare specifier = external lib → skip
                String base = normalize(dir + "/" + spec);
                String resolved = resolveTsTarget(repo, base);
                if (resolved != null && !resolved.equals(from)) {
                    edges.add(new Edge(from, resolved, "import", i + 1));
                }
            }
        }
        return edges;
    }

    private String resolveTsTarget(RepoIndex repo, String base) {
        if (repo.exists(base)) return base;
        for (String ext : TS_EXTS) if (repo.exists(base + ext)) return base + ext;
        for (String ext : TS_EXTS) if (repo.exists(base + "/index" + ext)) return base + "/index" + ext;
        return null;
    }

    // ---- Python: from a.b import x / import a.b ----------------------------------------
    private List<Edge> extractPy(String from, String content, RepoIndex repo) {
        List<Edge> edges = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        String dir = parentDir(from);
        for (int i = 0; i < lines.length; i++) {
            Matcher fm = PY_FROM.matcher(lines[i]);
            if (fm.find()) {
                String dots = fm.group(1);                      // relative level (. / ..)
                String module = fm.group(2);
                String resolved = dots.isEmpty()
                    ? resolvePyAbsolute(repo, module)
                    : resolvePyRelative(repo, dir, dots.length(), module);
                if (resolved != null && !resolved.equals(from)) {
                    edges.add(new Edge(from, resolved, "from_import", i + 1));
                }
                continue;
            }
            Matcher im = PY_IMPORT.matcher(lines[i]);
            if (im.find()) {
                String resolved = resolvePyAbsolute(repo, im.group(1));
                if (resolved != null && !resolved.equals(from)) {
                    edges.add(new Edge(from, resolved, "import", i + 1));
                }
            }
        }
        return edges;
    }

    private String resolvePyAbsolute(RepoIndex repo, String module) {
        if (module == null || module.isEmpty()) return null;
        String base = module.replace('.', '/');
        String mod = resolveSuffix(repo, base + ".py");
        if (mod != null) return mod;
        return resolveSuffix(repo, base + "/__init__.py");
    }

    private String resolvePyRelative(RepoIndex repo, String importerDir, int level, String module) {
        String dir = importerDir;
        for (int k = 1; k < level; k++) dir = parentDir(dir);   // one dot = current package
        String base = (dir.isEmpty() ? "" : dir + "/") + (module.isEmpty() ? "" : module.replace('.', '/'));
        base = normalize(base);
        if (repo.exists(base + ".py")) return base + ".py";
        if (repo.exists(base + "/__init__.py")) return base + "/__init__.py";
        return null;
    }

    // ---- Go: import "<module>/pkg" → .go files in pkg dir ------------------------------
    private List<Edge> extractGo(String from, String content, RepoIndex repo) {
        String prefix = repo.goModulePrefix();
        if (prefix == null || prefix.isBlank()) return List.of();
        List<Edge> edges = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        boolean inBlock = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!inBlock && line.matches("\\s*import\\s*\\(\\s*")) { inBlock = true; continue; }
            if (inBlock && line.matches("\\s*\\)\\s*")) { inBlock = false; continue; }
            Matcher m = inBlock ? GO_INBLOCK.matcher(line) : GO_SINGLE.matcher(line);
            if (!m.find()) continue;
            String pkg = m.group(1);
            if (!pkg.equals(prefix) && !pkg.startsWith(prefix + "/")) continue;   // external → skip
            String dir = pkg.equals(prefix) ? "" : pkg.substring(prefix.length() + 1);
            int added = 0;
            for (String f : repo.filesInDir(dir)) {
                if (!f.endsWith(".go") || f.endsWith("_test.go") || f.equals(from)) continue;
                edges.add(new Edge(from, f, "go_import", i + 1));
                if (++added >= GO_PKG_FILE_CAP) break;
            }
        }
        return edges;
    }

    // ---- helpers ----------------------------------------------------------------------

    /** Resolves a candidate relative path by exact match, else by unique path suffix. */
    private String resolveSuffix(RepoIndex repo, String candidate) {
        if (repo.exists(candidate)) return candidate;
        String basename = candidate.substring(candidate.lastIndexOf('/') + 1);
        String tail = "/" + candidate;
        for (String p : repo.byBasename(basename)) {
            if (p.endsWith(tail)) return p;
        }
        return null;
    }

    private static String parentDir(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash);
    }

    /** Collapses ./ and ../ segments; forward-slash output, no leading slash. */
    static String normalize(String path) {
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) { if (!stack.isEmpty()) stack.removeLast(); }
            else stack.addLast(seg);
        }
        return String.join("/", stack);
    }
}
