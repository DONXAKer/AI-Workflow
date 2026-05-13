package com.workflow.knowledge;

/**
 * One semantic search result over indexed source code.
 *
 * @param projectSlug owning project
 * @param path        file path relative to {@code Project.workingDir}
 * @param startLine   1-based first line of the chunk (inclusive)
 * @param endLine     1-based last line of the chunk (inclusive)
 * @param content     raw chunk text — what the agent reads
 * @param score       cosine similarity, higher = more relevant
 */
public record KnowledgeHit(
    String projectSlug,
    String path,
    int startLine,
    int endLine,
    String content,
    double score
) {}
