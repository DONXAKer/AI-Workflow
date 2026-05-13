package com.workflow.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Qdrant-backed semantic search over indexed source code.
 *
 * <p>Activated when {@code workflow.knowledge.qdrant.url} is set in {@code application.yaml}.
 * Falls back to {@link NoOpKnowledgeBase} when not configured. Inputs are embedded via
 * {@link OllamaEmbedder} and looked up in the per-project collection
 * {@code code_<projectSlug>} populated by {@link ProjectIndexer}.
 *
 * <p>{@link #query(String, int)} preserves the legacy concatenated-text shape for
 * {@code QueryKnowledgeBaseSkill}. New callers should use {@link #search} for
 * structured hits with file/line metadata.
 */
@Service("qdrantKnowledgeBase")
@ConditionalOnProperty(name = "workflow.knowledge.qdrant.url")
public class QdrantKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(QdrantKnowledgeBase.class);

    private final QdrantClient qdrant;
    private final OllamaEmbedder embedder;

    @Autowired
    public QdrantKnowledgeBase(QdrantClient qdrant, OllamaEmbedder embedder) {
        this.qdrant = qdrant;
        this.embedder = embedder;
    }

    @Override
    public List<KnowledgeHit> search(String projectSlug, String query, int nResults) {
        if (projectSlug == null || projectSlug.isBlank()) {
            log.debug("KB search called without project slug — returning empty");
            return List.of();
        }
        if (query == null || query.isBlank()) return List.of();

        float[] vector = embedder.embedOne(query);
        if (vector == null) {
            log.warn("KB embed failed for query (len={}): falling back to no results", query.length());
            return List.of();
        }

        String collection = collectionName(projectSlug);
        List<QdrantClient.SearchResult> raw = qdrant.search(collection, vector, nResults);
        List<KnowledgeHit> out = new ArrayList<>(raw.size());
        for (QdrantClient.SearchResult r : raw) {
            JsonNode p = r.payload();
            out.add(new KnowledgeHit(
                projectSlug,
                p.path("path").asText(""),
                p.path("start_line").asInt(0),
                p.path("end_line").asInt(0),
                p.path("content").asText(""),
                r.score()
            ));
        }
        return out;
    }

    @Override
    public String query(String query, int nResults) {
        // Legacy form — derive project from current ProjectContext if available, else
        // return empty (caller has to use the structured search() to pass a slug).
        String slug = com.workflow.project.ProjectContext.get();
        if (slug == null || slug.isBlank()) return "";
        List<KnowledgeHit> hits = search(slug, query, nResults);
        if (hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (KnowledgeHit h : hits) {
            sb.append("### ").append(h.path())
              .append(':').append(h.startLine()).append('-').append(h.endLine())
              .append("\n").append(h.content()).append("\n\n");
        }
        return sb.toString();
    }

    /** Per-project Qdrant collection — slug-keyed so projects don't pollute each other. */
    public static String collectionName(String projectSlug) {
        String safe = projectSlug == null ? "default" : projectSlug;
        return "code_" + safe.toLowerCase().replaceAll("[^a-z0-9_]+", "_");
    }
}
