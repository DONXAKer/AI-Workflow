package com.workflow.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Path validation for tools that touch the filesystem.
 *
 * <p>Every user-supplied path is resolved against {@link ToolContext#workingDir()},
 * normalized (removes {@code .} and {@code ..}), and then checked to remain inside the
 * canonical real path of the working dir. Symlinks are followed for any ancestor that
 * exists, preventing escape via a symlink that points outside the scope.
 *
 * <p>Rejections throw {@link ToolInvocationException} — the executor surfaces these as
 * {@code is_error:true} tool_results so the LLM can correct itself.
 */
public final class PathScope {

    private PathScope() {}

    /**
     * Resolve a user-supplied path against the working directory and verify it does not
     * escape the scope. Does not require the target to exist (supports Write creating
     * new files), but the nearest existing ancestor is canonicalized so symlinks cannot
     * be used to break out.
     *
     * @param context tool context carrying the working dir
     * @param userPath path as supplied by the LLM (absolute or relative)
     * @return absolute, normalized path safe for filesystem operations
     * @throws ToolInvocationException if the resolved path escapes the working dir
     */
    public static Path resolve(ToolContext context, String userPath) {
        if (userPath == null || userPath.isBlank()) {
            throw new ToolInvocationException("path is required");
        }

        Path root;
        try {
            root = context.workingDir().toRealPath();
        } catch (IOException e) {
            throw new ToolInvocationException(
                "working directory does not exist or is not accessible: " + context.workingDir(), e);
        }

        Path target = root.resolve(userPath).toAbsolutePath().normalize();

        if (!target.startsWith(root)) {
            throw new ToolInvocationException(
                "path escapes working directory: '" + userPath + "' -> " + target
                    + " (root=" + root + ")");
        }

        // Follow symlinks on the longest existing prefix. A symlink pointing outside the
        // root would be caught here even if its textual path still starts with root.
        Path probe = target;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe != null) {
            Path real;
            try {
                real = probe.toRealPath();
            } catch (IOException e) {
                throw new ToolInvocationException(
                    "failed to canonicalize path: " + probe, e);
            }
            if (!real.startsWith(root)) {
                throw new ToolInvocationException(
                    "path escapes working directory through symlink: '" + userPath
                        + "' -> " + real + " (root=" + root + ")");
            }
        }

        return target;
    }
}
