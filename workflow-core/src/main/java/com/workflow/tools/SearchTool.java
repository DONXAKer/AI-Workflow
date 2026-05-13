package com.workflow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.knowledge.KnowledgeBase;
import com.workflow.knowledge.KnowledgeHit;
import com.workflow.project.ProjectContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent-facing semantic search over the project's indexed source code.
 *
 * <p>Resolves the current project from {@link ProjectContext} (set by
 * {@code PipelineRunner} at the start of every run) and queries the per-project
 * Qdrant collection populated by {@code ProjectIndexer}. Returns a Markdown-formatted
 * list of hits ordered by similarity, with file paths + line ranges + content blocks
 * — same shape the agent already groks from {@code Read} output.
 *
 * <p>Opt-in: blocks must add {@code "Search"} to their {@code allowed_tools} list.
 * When Qdrant is not configured or the project has no index yet, the tool returns
 * a clear "no results" message so the agent can fall back to Grep/Read without
 * looping on a broken tool.
 */
@Component
public class SearchTool implements Tool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 15;
    private static final int CHUNK_PREVIEW_MAX = 4000;

    private final KnowledgeBase knowledgeBase;

    @Autowired
    public SearchTool(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @Override public String name() { return "Search"; }

    @Override
    public String description() {
        return "Search the project's source code semantically. Returns the top-K code "
            + "chunks whose meaning is closest to your query — much faster than running "
            + "Grep multiple times when you need to find HOW something is implemented or "
            + "used. Each result is a (file_path, line_range, content) tuple. Falls back "
            + "to an empty result if the project index is not available — use Grep then.";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper objectMapper) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description",
            "Natural-language description of what you're looking for. Examples: "
            + "\"AP indicator widget binding\", \"how is enum UnitType used in damage calculation\", "
            + "\"WebSocket handler for game state updates\". Be specific; semantic search "
            + "rewards descriptive queries.");

        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "Max number of results to return (1-15). Default 5.");
        limit.put("minimum", 1);
        limit.put("maximum", MAX_LIMIT);

        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public String execute(ToolContext context, JsonNode input) throws Exception {
        String query = input.path("query").asText("").trim();
        if (query.isEmpty()) {
            throw new ToolInvocationException("Search: 'query' must not be empty");
        }
        int limit = input.path("limit").asInt(DEFAULT_LIMIT);
        if (limit < 1) limit = 1;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;

        String projectSlug = ProjectContext.get();
        if (projectSlug == null || projectSlug.isBlank()) {
            return "(Search disabled: no project in context — falling back to native tools is OK)";
        }

        List<KnowledgeHit> hits = knowledgeBase.search(projectSlug, query, limit);
        if (hits.isEmpty()) {
            return "(Search returned 0 hits for query \"" + abbreviate(query, 100)
                + "\" — project may not be indexed yet, or the query is too specific. "
                + "Try a broader phrasing, or use Grep/Glob.)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Search hits for ").append(quote(abbreviate(query, 100))).append(":\n\n");
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeHit h = hits.get(i);
            sb.append("### Hit ").append(i + 1)
              .append(" — ").append(h.path())
              .append(':').append(h.startLine()).append('-').append(h.endLine())
              .append("  (score=").append(String.format("%.3f", h.score())).append(")\n");
            sb.append(abbreviate(h.content(), CHUNK_PREVIEW_MAX)).append("\n\n");
        }
        return sb.toString();
    }

    private static String quote(String s) { return "\"" + s + "\""; }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
