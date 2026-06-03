package com.workflow.preflight;

import com.workflow.config.BlockConfig;

/**
 * Small helpers for blocks declaring {@link Requirement}s in {@code preflightRequirements}, so the
 * common "read a literal config string" / "working-dir requirement" patterns are not copy-pasted.
 */
public final class PreflightRequirements {

    private PreflightRequirements() {}

    /**
     * Returns the {@code config[key]} value only if it is a non-blank literal string with no
     * unresolved {@code ${...}} interpolation — otherwise {@code null} (the value depends on
     * upstream block outputs that do not exist yet at run-start, so we cannot check it).
     */
    public static String literalString(BlockConfig config, String key) {
        Object v = config.getConfig().get(key);
        if (!(v instanceof String s)) return null;
        s = s.strip();
        if (s.isEmpty() || s.contains("${")) return null;
        return s;
    }

    /**
     * WorkingDir requirement for the block: validates an explicit literal {@code working_dir} if the
     * block declares one, otherwise the run-level working directory (path {@code null}).
     */
    public static Requirement.WorkingDir workingDir(BlockConfig config) {
        return new Requirement.WorkingDir(literalString(config, "working_dir"));
    }
}
