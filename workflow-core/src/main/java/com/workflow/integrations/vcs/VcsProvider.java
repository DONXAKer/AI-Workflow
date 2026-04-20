package com.workflow.integrations.vcs;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic VCS contract. Operations surface the common MR/PR + CI flow;
 * merge-conflict detection returns a dedicated result so the runner can trigger
 * loopback.
 */
public interface VcsProvider {

    String providerName();

    void createBranch(String branchName, String baseBranch, Map<String, Object> config) throws Exception;

    /** Apply a batch of file operations to the branch. Callers handle commit-message batching. */
    void applyChanges(String branch, List<VcsChange> changes, String commitMessage,
                       Map<String, Object> config) throws Exception;

    MergeRequestRef openMergeRequest(String sourceBranch, String targetBranch, String title,
                                      String description, List<String> labels,
                                      Map<String, Object> config) throws Exception;

    /** Wait for the latest CI attached to the MR/PR (polling). */
    CiPipelineStatus waitForCi(long mrId, int timeoutSeconds, Map<String, Object> config) throws Exception;

    /**
     * Merge an approved MR/PR using the given strategy.
     *
     * @return merge result with {@code status}: {@code merged | conflict}, plus optional
     *         {@code conflicts} list when status is conflict.
     */
    MergeResult merge(long mrId, MergeStrategy strategy, boolean deleteBranchAfter,
                       Map<String, Object> config) throws Exception;

    enum MergeStrategy { MERGE, SQUASH, REBASE }

    record MergeResult(String status, String mergeSha, List<String> conflicts) {
        public static MergeResult merged(String sha) { return new MergeResult("merged", sha, List.of()); }
        public static MergeResult conflict(List<String> conflicts) { return new MergeResult("conflict", null, conflicts); }
    }
}
