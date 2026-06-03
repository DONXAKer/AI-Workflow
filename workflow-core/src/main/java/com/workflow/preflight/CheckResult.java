package com.workflow.preflight;

/**
 * Outcome of verifying a single {@link Requirement}. Produced by {@link RequirementChecker};
 * {@link RunDiagnostics} maps it to a {@link com.workflow.config.ValidationError} (applying the
 * conditional-block downgrade) for the run-start envelope.
 */
public record CheckResult(Requirement requirement, Kind kind, String detail) {

    public enum Kind {
        /** Requirement satisfied. */
        PASS,
        /**
         * Deterministic local/presence failure — the block will certainly fail on this
         * (missing dir / binary / token, no git repo). Blocks the run start, <em>unless</em>
         * the requirement came only from {@code condition:}-gated blocks (then downgraded to WARN).
         */
        HARD_FAIL,
        /**
         * Network reachability failure or inconclusive probe (timeout / connection error).
         * Never blocks the start — surfaced as WARN, because "could not confirm" ≠ "broken".
         */
        SOFT_FAIL
    }

    public static CheckResult pass(Requirement r) {
        return new CheckResult(r, Kind.PASS, null);
    }

    public static CheckResult hardFail(Requirement r, String detail) {
        return new CheckResult(r, Kind.HARD_FAIL, detail);
    }

    public static CheckResult softFail(Requirement r, String detail) {
        return new CheckResult(r, Kind.SOFT_FAIL, detail);
    }
}
