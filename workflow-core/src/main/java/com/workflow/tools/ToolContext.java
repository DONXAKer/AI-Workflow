package com.workflow.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Per-invocation environment handed to every {@link Tool#execute} call.
 */
public record ToolContext(
    Path workingDir,
    List<String> bashAllowlist,
    UUID runId,
    String blockId
) {
    public ToolContext {
        if (workingDir == null) throw new IllegalArgumentException("workingDir required");
        bashAllowlist = bashAllowlist == null ? List.of() : List.copyOf(bashAllowlist);
    }

    public ToolContext(Path workingDir, List<String> bashAllowlist) {
        this(workingDir, bashAllowlist, null, null);
    }

    public static ToolContext of(Path workingDir) {
        return new ToolContext(workingDir, List.of(), null, null);
    }
}
