package com.workflow.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link BlockOutputRepository#findByRunIdOrderByStartedAt} returns
 * records in ascending startedAt order with NULL timestamps placed last.
 */
@DataJpaTest
class BlockOutputRepositoryTest {

    @Autowired
    private BlockOutputRepository blockOutputRepository;

    @Autowired
    private PipelineRunRepository pipelineRunRepository;

    @Autowired
    private TestEntityManager em;

    private PipelineRun savedRun() {
        PipelineRun run = PipelineRun.builder()
            .id(UUID.randomUUID())
            .pipelineName("test-pipeline")
            .requirement("req")
            .status(RunStatus.RUNNING)
            .startedAt(Instant.now())
            .completedBlocks(new LinkedHashSet<>())
            .autoApprove(new LinkedHashSet<>())
            .outputs(new ArrayList<>())
            .build();
        return pipelineRunRepository.save(run);
    }

    @Test
    void findByRunIdOrderByStartedAt_returnsInChronologicalOrder_withNullsLast() {
        PipelineRun run = savedRun();

        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T10:00:30Z");

        // Save out-of-order: codegen first (later timestamp), analysis second (earlier)
        blockOutputRepository.save(BlockOutput.builder()
            .run(run).blockId("codegen").outputJson("{\"files\":1}")
            .startedAt(t2).completedAt(t2.plusSeconds(10)).build());

        blockOutputRepository.save(BlockOutput.builder()
            .run(run).blockId("analysis").outputJson("{\"score\":9}")
            .startedAt(t1).completedAt(t1.plusSeconds(5)).build());

        // Block with no timestamp — must appear last
        blockOutputRepository.save(BlockOutput.builder()
            .run(run).blockId("_loopback_analysis").outputJson("{}")
            .build());

        em.flush();
        em.clear();

        List<BlockOutput> results = blockOutputRepository.findByRunIdOrderByStartedAt(run.getId());

        assertEquals(3, results.size());
        assertEquals("analysis", results.get(0).getBlockId(), "earliest startedAt must come first");
        assertEquals("codegen", results.get(1).getBlockId(), "later startedAt must come second");
        assertEquals("_loopback_analysis", results.get(2).getBlockId(), "null startedAt must be last");

        assertNotNull(results.get(0).getStartedAt(), "analysis startedAt must be set");
        assertNotNull(results.get(0).getCompletedAt(), "analysis completedAt must be set");
        assertNull(results.get(2).getStartedAt(), "loopback startedAt must remain null");
    }

    @Test
    void findByRunIdOrderByStartedAt_returnsOnlyForMatchingRun() {
        PipelineRun run1 = savedRun();
        PipelineRun run2 = savedRun();

        Instant t = Instant.parse("2026-01-01T09:00:00Z");
        blockOutputRepository.save(BlockOutput.builder()
            .run(run1).blockId("block-a").outputJson("{}").startedAt(t).completedAt(t.plusSeconds(1)).build());
        blockOutputRepository.save(BlockOutput.builder()
            .run(run2).blockId("block-b").outputJson("{}").startedAt(t).completedAt(t.plusSeconds(1)).build());

        em.flush();
        em.clear();

        List<BlockOutput> results = blockOutputRepository.findByRunIdOrderByStartedAt(run1.getId());
        assertEquals(1, results.size());
        assertEquals("block-a", results.get(0).getBlockId());
    }

    @Test
    void findByRunIdOrderByStartedAt_returnsEmptyList_whenNoOutputs() {
        PipelineRun run = savedRun();
        em.flush();
        em.clear();

        List<BlockOutput> results = blockOutputRepository.findByRunIdOrderByStartedAt(run.getId());
        assertTrue(results.isEmpty());
    }
}
