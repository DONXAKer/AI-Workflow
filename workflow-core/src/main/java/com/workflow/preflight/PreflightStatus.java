package com.workflow.preflight;

/**
 * Three-state outcome of a preflight check.
 *
 * <ul>
 *   <li>{@link #PASSED} — build OK, all tests OK; downstream blocks proceed normally.</li>
 *   <li>{@link #WARNING} — build/test surfaced pre-existing failures but operator policy
 *       ({@code on_red: warn}) allows continuation; downstream {@code verify}/{@code ci}
 *       blocks consult {@code baseline_failures} to distinguish regressions from
 *       pre-existing red state.</li>
 *   <li>{@link #RED_BLOCKED} — build/test failed and operator policy is {@code on_red: block};
 *       the pipeline should pause for operator intervention (handled by the runner, not
 *       the block itself).</li>
 * </ul>
 */
public enum PreflightStatus {
    PASSED,
    WARNING,
    RED_BLOCKED
}
