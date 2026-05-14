package com.workflow.preflight;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Resolved preflight commands for a target project. Produced by
 * {@link PreflightConfigResolver} from either an explicit {@code ## Preflight}
 * section in the target's {@code CLAUDE.md} or auto-detection from build
 * manifests.
 *
 * @param buildCmd  shell command to compile / package without running tests
 * @param testCmd   shell command to run the test suite
 * @param fqnFormat one of {@code junit5}, {@code junit4}, {@code pytest}, {@code jest},
 *                  {@code go}, {@code none}; drives baseline-failure FQN extraction
 * @param source    where the config came from: {@code claude_md}, {@code auto_detect},
 *                  or {@code fallback}
 * @param detected  human-readable description of the manifest that triggered
 *                  auto-detection, or {@code null} for the {@code claude_md} source
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PreflightCommands(
        String buildCmd,
        String testCmd,
        String fqnFormat,
        String source,
        String detected
) {
    public static final String SOURCE_CLAUDE_MD  = "claude_md";
    public static final String SOURCE_AUTO       = "auto_detect";
    public static final String SOURCE_FALLBACK   = "fallback";

    public boolean isFallback() { return SOURCE_FALLBACK.equals(source); }

    public boolean hasCommands() {
        return (buildCmd != null && !buildCmd.isBlank())
                || (testCmd != null && !testCmd.isBlank());
    }
}
