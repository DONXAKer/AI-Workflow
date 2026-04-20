package com.workflow.integrations.vcs;

/**
 * Provider-agnostic reference to a merge request / pull request.
 *
 * @param provider      "gitlab" | "github"
 * @param id            numeric identifier (GitLab iid, GitHub number)
 * @param title         MR/PR title
 * @param sourceBranch  branch with the changes
 * @param targetBranch  branch being merged into
 * @param url           web URL to the MR/PR
 * @param state         open / merged / closed
 */
public record MergeRequestRef(
    String provider,
    long id,
    String title,
    String sourceBranch,
    String targetBranch,
    String url,
    String state
) {}
