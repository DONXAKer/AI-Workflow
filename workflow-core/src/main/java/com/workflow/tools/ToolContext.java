package com.workflow.tools;

import java.nio.file.Path;
import java.util.List;

/**
 * Per-invocation environment handed to every {@link Tool#execute} call.
 *
 * <p>{@code workingDir} is the project root that bounds filesystem access — see
 * {@link PathScope}. All relative paths from the LLM are resolved against it and rejected
 * if they escape after canonicalization (including symlink resolution where the target
 * exists).
 *
 * <p>{@code bashAllowlist} is a list of Claude-Code-style patterns (e.g. {@code Bash(git
 * *)}, {@code Bash(gradle *)}). Applied only by the Bash tool — other tools ignore it.
 * Empty list = no bash commands allowed.
 *
 * <p>The deny-list is not carried here: it is a compile-time constant enforced inside the
 * Bash and Write tools themselves, since it is never supposed to be weakened.
 */
public record ToolContext(
    Path workingDir,
    List<String> bashAllowlist
) {
    public ToolContext {
        if (workingDir == null) throw new IllegalArgumentException("workingDir required");
        bashAllowlist = bashAllowlist == null ? List.of() : List.copyOf(bashAllowlist);
    }

    public static ToolContext of(Path workingDir) {
        return new ToolContext(workingDir, List.of());
    }
}
