package com.workflow.integrations.vcs;

public record VcsChange(String path, String content, Operation operation) {
    public enum Operation { CREATE, UPDATE, DELETE }
    public static VcsChange create(String path, String content) { return new VcsChange(path, content, Operation.CREATE); }
    public static VcsChange update(String path, String content) { return new VcsChange(path, content, Operation.UPDATE); }
    public static VcsChange delete(String path) { return new VcsChange(path, null, Operation.DELETE); }
}
