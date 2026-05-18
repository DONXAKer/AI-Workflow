package com.workflow.core;

import com.workflow.config.BlockConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DagLevelExecutorTest {

    private final DagLevelExecutor executor = new DagLevelExecutor();

    @Test
    void linearChainProducesOneBlockPerLevel() {
        BlockConfig a = block("a");
        BlockConfig b = block("b", "a");
        BlockConfig c = block("c", "b");

        List<List<BlockConfig>> levels = executor.computeLevels(List.of(a, b, c), Set.of());

        assertThat(levels).hasSize(3);
        assertThat(levels.get(0)).extracting(BlockConfig::getId).containsExactly("a");
        assertThat(levels.get(1)).extracting(BlockConfig::getId).containsExactly("b");
        assertThat(levels.get(2)).extracting(BlockConfig::getId).containsExactly("c");
    }

    @Test
    void diamondTopologyMergesParallelBlocksIntoOneLevel() {
        // root → (left, right) → join
        BlockConfig root  = block("root");
        BlockConfig left  = block("left",  "root");
        BlockConfig right = block("right", "root");
        BlockConfig join  = block("join",  "left", "right");

        List<List<BlockConfig>> levels = executor.computeLevels(List.of(root, left, right, join), Set.of());

        assertThat(levels).hasSize(3);
        assertThat(levels.get(0)).extracting(BlockConfig::getId).containsExactly("root");
        assertThat(levels.get(1)).extracting(BlockConfig::getId).containsExactlyInAnyOrder("left", "right");
        assertThat(levels.get(2)).extracting(BlockConfig::getId).containsExactly("join");
    }

    @Test
    void independentBranchesShareTopLevel() {
        // a, b, c with no deps — all in level 0
        BlockConfig a = block("a");
        BlockConfig b = block("b");
        BlockConfig c = block("c");

        List<List<BlockConfig>> levels = executor.computeLevels(List.of(a, b, c), Set.of());

        assertThat(levels).hasSize(1);
        assertThat(levels.get(0)).hasSize(3);
    }

    @Test
    void completedBlocksCountAsSatisfiedDeps() {
        BlockConfig a = block("a");
        BlockConfig b = block("b", "a");

        // 'a' was completed in a prior partial run — 'b' should land in level 0.
        List<List<BlockConfig>> levels = executor.computeLevels(List.of(b), Set.of("a"));

        assertThat(levels).hasSize(1);
        assertThat(levels.get(0)).extracting(BlockConfig::getId).containsExactly("b");
    }

    @Test
    void unknownDependencyIsTreatedAsLevelZeroSatisfied() {
        // 'b' depends on 'x' which isn't in the run window (injected output, external).
        // Should not throw — degrades to level 0 with a warning.
        BlockConfig b = block("b", "x");

        List<List<BlockConfig>> levels = executor.computeLevels(List.of(b), Set.of());

        assertThat(levels).hasSize(1);
        assertThat(levels.get(0)).extracting(BlockConfig::getId).containsExactly("b");
    }

    @Test
    void forwardReferenceDegradesToSequential() {
        // 'a' precedes 'b' in the list but depends on 'b' — caller violated topological-sort
        // precondition. Executor falls back to wrap-each-as-level (effectively sequential).
        BlockConfig a = block("a", "b");
        BlockConfig b = block("b");

        List<List<BlockConfig>> levels = executor.computeLevels(List.of(a, b), Set.of());

        assertThat(levels).hasSize(2);
        // Each block in its own level — safe fallback.
        assertThat(levels.get(0)).hasSize(1);
        assertThat(levels.get(1)).hasSize(1);
    }

    @Test
    void canParallelizeReturnsFalseForApprovalBlocks() {
        BlockConfig a = parallelBlock("a");
        BlockConfig b = parallelBlock("b");
        a.setApproval(true);

        // 'a' has approval — level isn't parallelizable.
        assertThat(executor.canParallelizeLevel(List.of(a, b))).isFalse();
    }

    @Test
    void canParallelizeReturnsFalseForSingletonLevel() {
        // No point parallelizing a single block.
        assertThat(executor.canParallelizeLevel(List.of(parallelBlock("only")))).isFalse();
    }

    @Test
    void canParallelizeReturnsTrueForCleanLevel() {
        BlockConfig a = parallelBlock("a");
        BlockConfig b = parallelBlock("b");

        assertThat(executor.canParallelizeLevel(List.of(a, b))).isTrue();
    }

    @Test
    void canParallelizeReturnsFalseWhenNotAllBlocksOptIn() {
        // Opt-in gate: every block must explicitly set parallel=true, otherwise the
        // level falls back to sequential. Protects existing pipelines from accidental
        // concurrency when they happen to be at the same DAG depth.
        BlockConfig a = parallelBlock("a");
        BlockConfig b = block("b");  // not parallel

        assertThat(executor.canParallelizeLevel(List.of(a, b))).isFalse();
    }

    @Test
    void canParallelizeRejectsRequiredGates() {
        BlockConfig a = parallelBlock("a");
        BlockConfig b = parallelBlock("b");
        a.setRequiredGates(List.of(new com.workflow.config.GateConfig()));

        assertThat(executor.canParallelizeLevel(List.of(a, b))).isFalse();
    }

    @Test
    void featureFlagDefaultIsOff() {
        // No application context — Spring's @Value default kicks in only with PostProcessor.
        // Verify the setter / getter contract directly.
        assertThat(executor.isEnabled()).isFalse();
        executor.setParallelExecutionEnabled(true);
        assertThat(executor.isEnabled()).isTrue();
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(executor.computeLevels(null, Set.of())).isEmpty();
        assertThat(executor.computeLevels(List.of(), Set.of())).isEmpty();
    }

    @Test
    void idsAcrossLevelsExtractsDistinct() {
        BlockConfig a = block("a");
        BlockConfig b = block("b", "a");
        List<List<BlockConfig>> levels = executor.computeLevels(List.of(a, b), Set.of());

        assertThat(executor.idsAcrossLevels(levels)).containsExactly("a", "b");
    }

    private static BlockConfig block(String id, String... deps) {
        BlockConfig b = new BlockConfig();
        b.setId(id);
        b.setBlock(id + "_type");
        b.setApproval(false);
        if (deps.length > 0) b.setDependsOn(List.of(deps));
        return b;
    }

    /** Same as {@link #block} but marked parallel:true (the opt-in flag for level concurrency). */
    private static BlockConfig parallelBlock(String id, String... deps) {
        BlockConfig b = block(id, deps);
        b.setParallel(true);
        return b;
    }
}
