package com.workflow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.knowledge.ImportGraphService;
import com.workflow.knowledge.ImportGraphService.Neighbor;
import com.workflow.project.ProjectContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Agent-facing query over the project's file-level import graph (built by
 * {@code ImportGraphService}). Lets an agent ask, before editing a file, "what does X
 * depend on" ({@code direction=out}) and "what depends on X" ({@code direction=in}) —
 * structural relationships that semantic {@code Search} does not surface.
 *
 * <p>Returns lightweight edges (path + direction + import kind + line); the agent then
 * {@code Read}s whatever it needs. Read-only. When the graph is empty (project not yet
 * graph-indexed, or the file has no in-repo imports) it returns a clear note so the agent
 * falls back to Grep/Search instead of looping.
 */
@Component
public class DepsTool implements Tool {

    private static final int DEFAULT_DEPTH = 1, MAX_DEPTH = 3;
    private static final int DEFAULT_MAX_NODES = 50, MAX_NODES = 200;

    private final ImportGraphService graph;

    @Autowired
    public DepsTool(ImportGraphService graph) {
        this.graph = graph;
    }

    @Override public String name() { return "Deps"; }

    @Override
    public String description() {
        return "Query the project's file-level import/dependency graph. Given a file path, "
            + "returns the in-repo files it imports (direction=out — what it depends on) and/or "
            + "the files that import it (direction=in — what would break if you change it). "
            + "Use this BEFORE editing a file to understand its context and blast radius — it "
            + "surfaces structural links that semantic Search can't. Returns edges only "
            + "(path, kind, line); Read the ones you care about. Empty result = file has no "
            + "in-repo imports or the graph isn't built yet (then use Grep/Search).";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper objectMapper) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "File to inspect, relative to the project root (e.g. "
            + "\"src/main/java/com/workflow/core/PipelineRunner.java\"). Absolute paths inside "
            + "the project are also accepted.");

        ObjectNode direction = props.putObject("direction");
        direction.put("type", "string");
        direction.put("description", "out = files this file imports; in = files that import it; "
            + "both = union (default).");
        direction.putArray("enum").add("out").add("in").add("both");

        ObjectNode depth = props.putObject("depth");
        depth.put("type", "integer");
        depth.put("description", "Transitive hops to follow (1-3). Default 1 = direct neighbours.");
        depth.put("minimum", 1);
        depth.put("maximum", MAX_DEPTH);

        ObjectNode maxNodes = props.putObject("max_nodes");
        maxNodes.put("type", "integer");
        maxNodes.put("description", "Cap on returned edges (1-200). Default 50.");
        maxNodes.put("minimum", 1);
        maxNodes.put("maximum", MAX_NODES);

        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public String execute(ToolContext context, JsonNode input) throws Exception {
        String rawPath = input.path("path").asText("").trim();
        if (rawPath.isEmpty()) {
            throw new ToolInvocationException("Deps: 'path' must not be empty");
        }
        String direction = input.path("direction").asText("both").trim().toLowerCase();
        if (!direction.equals("out") && !direction.equals("in") && !direction.equals("both")) {
            direction = "both";
        }
        int depth = clamp(input.path("depth").asInt(DEFAULT_DEPTH), 1, MAX_DEPTH);
        int maxNodes = clamp(input.path("max_nodes").asInt(DEFAULT_MAX_NODES), 1, MAX_NODES);

        String slug = ProjectContext.get();
        if (slug == null || slug.isBlank()) {
            return "(Deps disabled: no project in context — use Grep/Read instead)";
        }

        String rel = toRelative(context.workingDir(), rawPath);
        List<Neighbor> hits = graph.query(slug, rel, direction, depth, maxNodes);
        if (hits.isEmpty()) {
            return "(Deps: no graph edges for \"" + rel + "\" — either it has no in-repo imports, "
                + "or the import graph isn't built yet. Rebuild via POST /api/projects/" + slug
                + "/graph/rebuild, or fall back to Grep/Search.)";
        }

        long out = hits.stream().filter(h -> h.direction().equals("out")).count();
        long in = hits.size() - out;
        StringBuilder sb = new StringBuilder();
        sb.append("Dependency edges for ").append(rel)
          .append("  (out=").append(out).append(", in=").append(in)
          .append(", depth≤").append(depth).append("):\n\n");
        for (Neighbor h : hits) {
            sb.append("- [").append(h.direction()).append("] ").append(h.path())
              .append("  (").append(h.importType()).append(" @L").append(h.line());
            if (h.depth() > 1) sb.append(", hop ").append(h.depth());
            sb.append(")\n");
        }
        sb.append("\nout = ").append(rel).append(" imports these; in = these import ").append(rel)
          .append(". Read the relevant ones.");
        return sb.toString();
    }

    /** Normalise an absolute-or-relative input to a repo-relative, forward-slash path. */
    private static String toRelative(Path workingDir, String raw) {
        String p = raw.replace('\\', '/');
        Path abs = Path.of(raw);
        if (abs.isAbsolute() && workingDir != null) {
            Path wd = workingDir.toAbsolutePath().normalize();
            Path norm = abs.normalize();
            if (norm.startsWith(wd)) return wd.relativize(norm).toString().replace('\\', '/');
        }
        while (p.startsWith("./")) p = p.substring(2);
        return p;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
