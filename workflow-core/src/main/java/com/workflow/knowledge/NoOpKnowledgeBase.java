package com.workflow.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fallback {@link KnowledgeBase} that returns nothing — used when no Qdrant URL is
 * configured or the project is not indexed yet. {@code @ConditionalOnMissingBean}
 * means {@code QdrantKnowledgeBase} (when enabled) takes precedence.
 */
@Service
@ConditionalOnMissingBean(name = "qdrantKnowledgeBase")
public class NoOpKnowledgeBase implements KnowledgeBase {

    @Override
    public List<KnowledgeHit> search(String projectSlug, String query, int nResults) {
        return List.of();
    }

    @Override
    public String query(String query, int nResults) {
        return "";
    }
}
