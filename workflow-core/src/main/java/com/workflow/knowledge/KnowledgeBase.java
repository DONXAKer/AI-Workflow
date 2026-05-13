package com.workflow.knowledge;

import java.util.List;

/**
 * Project-scoped semantic search over indexed source code.
 *
 * <p>Implementations: {@link NoOpKnowledgeBase} (returns nothing — used when RAG
 * is disabled or no project is set) and {@code QdrantKnowledgeBase} (queries a
 * per-project Qdrant collection populated by {@code ProjectIndexer}).
 *
 * <p>The legacy {@link #query(String, int)} method returns concatenated text for
 * old call-sites. New code should use {@link #search(String, String, int)} to
 * get structured {@link KnowledgeHit}s with file/line metadata.
 */
public interface KnowledgeBase {

    /**
     * Semantic search inside {@code projectSlug}'s indexed code.
     *
     * @param projectSlug project to search within (must be indexed beforehand)
     * @param query       natural-language query, typically the task description
     * @param nResults    top-K limit (1..20)
     * @return list ordered by descending similarity. Empty if project not indexed,
     *         knowledge layer disabled, or upstream service unreachable.
     */
    List<KnowledgeHit> search(String projectSlug, String query, int nResults);

    /**
     * Legacy form: returns formatted concatenated text instead of structured hits.
     * Kept for the {@code QueryKnowledgeBaseSkill} call-site; new code should
     * prefer {@link #search} so the caller can render hits with file/line headers.
     */
    String query(String query, int nResults);
}
