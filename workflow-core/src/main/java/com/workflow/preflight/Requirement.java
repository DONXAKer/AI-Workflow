package com.workflow.preflight;

import com.workflow.llm.LlmProvider;
import com.workflow.model.IntegrationType;

/**
 * A pre-run environment prerequisite that a block (or the run itself) needs in order to
 * execute without dying mid-pipeline on a "wrong path / missing token / missing binary"
 * failure. Blocks declare these via {@link com.workflow.blocks.Block#preflightRequirements}
 * and {@link RunDiagnostics} verifies them before the run thread starts.
 *
 * <p>The variant set is closed (sealed) so {@link RequirementChecker} can exhaustively
 * pattern-match. Each variant carries the {@code blockId} that declared it (filled in by
 * {@link RunDiagnostics#gather} via {@link #withBlockId}; {@code null} for the run-level
 * base requirements) and a {@link #dedupKey()} so identical requirements from several
 * blocks collapse to a single check.
 */
public sealed interface Requirement
        permits Requirement.WorkingDir, Requirement.Binary,
                Requirement.Provider, Requirement.Integration, Requirement.GitRepo {

    /** Stable code fragment used to build {@code PREFLIGHT_*} error codes and metric tags. */
    String code();

    /** Block that declared this requirement, or {@code null} for run-level base requirements. */
    String blockId();

    /** Returns a copy of this requirement tagged with the given block id. */
    Requirement withBlockId(String blockId);

    /** Identical keys collapse to one check (and merge their conditional flags). */
    String dedupKey();

    /** Short human label for the requirement (used in error messages). */
    String label();

    /**
     * A filesystem working directory must exist, be a directory and be writable.
     * {@code path == null} means "the run's effective working directory" (resolved by the
     * checker from {@link PreflightContext#workingDir()}) — this is what FS-touching blocks
     * emit so that a completely unconfigured working dir is still caught.
     */
    record WorkingDir(String path, String blockId) implements Requirement {
        public WorkingDir(String path) { this(path, null); }
        @Override public String code() { return "WORKING_DIR"; }
        @Override public Requirement withBlockId(String b) { return new WorkingDir(path, b); }
        @Override public String dedupKey() { return "workingdir:" + (path == null ? "<run>" : path); }
        @Override public String label() { return "working directory" + (path == null ? "" : " '" + path + "'"); }
    }

    /** An executable must be resolvable on {@code PATH} (or as an absolute path). */
    record Binary(String name, String blockId) implements Requirement {
        public Binary(String name) { this(name, null); }
        @Override public String code() { return "BINARY"; }
        @Override public Requirement withBlockId(String b) { return new Binary(name, b); }
        @Override public String dedupKey() { return "binary:" + name; }
        @Override public String label() { return "binary '" + name + "'"; }
    }

    /** The selected LLM provider must be configured (token/DB or env) and — for HTTP providers — reachable. */
    record Provider(LlmProvider provider, String blockId) implements Requirement {
        public Provider(LlmProvider provider) { this(provider, null); }
        @Override public String code() { return "PROVIDER"; }
        @Override public Requirement withBlockId(String b) { return new Provider(provider, b); }
        @Override public String dedupKey() { return "provider:" + provider; }
        @Override public String label() { return "LLM provider " + provider; }
    }

    /** An integration of the given type must be resolvable with a non-blank token. */
    record Integration(IntegrationType type, String blockId) implements Requirement {
        public Integration(IntegrationType type) { this(type, null); }
        @Override public String code() { return "INTEGRATION"; }
        @Override public Requirement withBlockId(String b) { return new Integration(type, b); }
        @Override public String dedupKey() { return "integration:" + type; }
        @Override public String label() { return type + " integration"; }
    }

    /** The run's working directory must be a git checkout (a {@code .git} entry exists). */
    record GitRepo(String blockId) implements Requirement {
        public GitRepo() { this((String) null); }
        @Override public String code() { return "GIT_REPO"; }
        @Override public Requirement withBlockId(String b) { return new GitRepo(b); }
        @Override public String dedupKey() { return "gitrepo"; }
        @Override public String label() { return "git repository in working directory"; }
    }
}
