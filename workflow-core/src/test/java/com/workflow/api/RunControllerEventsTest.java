package com.workflow.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.workflow.core.BlockOutput;
import com.workflow.core.BlockOutputRepository;
import com.workflow.core.PipelineRun;
import com.workflow.core.PipelineRunRepository;
import com.workflow.core.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GET /api/runs/{id} — verifies that the response includes
 * the {@code events} field with chronologically ordered, timing-enriched entries
 * while preserving the existing {@code completedBlocks} field.
 */
class RunControllerEventsTest {

    @SuppressWarnings("unchecked")
    @Test
    void getRun_includesEventsInChronologicalOrder_andExcludesInternalEntries() {
        UUID runId = UUID.randomUUID();

        PipelineRun run = PipelineRun.builder()
            .id(runId)
            .pipelineName("test-pipeline")
            .requirement("test req")
            .status(RunStatus.COMPLETED)
            .startedAt(Instant.parse("2026-01-01T10:00:00Z"))
            .completedBlocks(new LinkedHashSet<>(List.of("analysis", "codegen")))
            .autoApprove(new LinkedHashSet<>())
            .outputs(new ArrayList<>())
            .build();

        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T10:00:05Z");
        Instant t3 = Instant.parse("2026-01-01T10:00:20Z");
        Instant t4 = Instant.parse("2026-01-01T10:01:00Z");

        BlockOutput b1 = BlockOutput.builder()
            .blockId("analysis").outputJson("{\"score\":8}")
            .startedAt(t1).completedAt(t2).build();
        BlockOutput b2 = BlockOutput.builder()
            .blockId("codegen").outputJson("{\"files\":1}")
            .startedAt(t3).completedAt(t4).build();
        // Internal loopback entry — must be excluded from events
        BlockOutput loopback = BlockOutput.builder()
            .blockId("_loopback_analysis").outputJson("{}")
            .build();

        PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
        BlockOutputRepository blockRepo = mock(BlockOutputRepository.class);
        when(runRepo.findWithCollectionsById(runId)).thenReturn(Optional.of(run));
        when(blockRepo.findByRunIdOrderByStartedAt(runId)).thenReturn(List.of(b1, b2, loopback));

        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        RunController controller = new RunController();
        ReflectionTestUtils.setField(controller, "pipelineRunRepository", runRepo);
        ReflectionTestUtils.setField(controller, "blockOutputRepository", blockRepo);
        ReflectionTestUtils.setField(controller, "objectMapper", mapper);
        ReflectionTestUtils.setField(controller, "projectRepository", null);

        ResponseEntity<?> response = controller.getRun(runId.toString());

        assertEquals(200, response.getStatusCode().value());
        assertInstanceOf(Map.class, response.getBody());
        Map<String, Object> body = (Map<String, Object>) response.getBody();

        assertTrue(body.containsKey("completedBlocks"), "completedBlocks must be preserved");

        assertTrue(body.containsKey("events"), "events field must be present");
        List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
        assertNotNull(events);

        assertEquals(2, events.size(), "_loopback_ entry must be excluded");
        assertEquals("analysis", events.get(0).get("blockId"), "analysis block must come first");
        assertEquals("codegen", events.get(1).get("blockId"), "codegen block must come second");

        // durationMs = completedAt - startedAt in millis
        assertEquals(5000L, events.get(0).get("durationMs"), "analysis duration must be 5000ms");
        assertEquals(40000L, events.get(1).get("durationMs"), "codegen duration must be 40000ms");

        assertNotNull(events.get(0).get("startedAt"));
        assertNotNull(events.get(0).get("completedAt"));
    }

    @Test
    void getRun_returnsNotFound_whenRunMissing() {
        UUID runId = UUID.randomUUID();
        PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
        BlockOutputRepository blockRepo = mock(BlockOutputRepository.class);
        when(runRepo.findWithCollectionsById(runId)).thenReturn(Optional.empty());

        RunController controller = new RunController();
        ReflectionTestUtils.setField(controller, "pipelineRunRepository", runRepo);
        ReflectionTestUtils.setField(controller, "blockOutputRepository", blockRepo);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper().registerModule(new JavaTimeModule()));
        ReflectionTestUtils.setField(controller, "projectRepository", null);

        ResponseEntity<?> response = controller.getRun(runId.toString());
        assertEquals(404, response.getStatusCode().value());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getRun_handlesNullTimestamps_withNullDurationMs() {
        UUID runId = UUID.randomUUID();
        PipelineRun run = PipelineRun.builder()
            .id(runId).pipelineName("p").requirement("r")
            .status(RunStatus.COMPLETED)
            .startedAt(Instant.now())
            .completedBlocks(new LinkedHashSet<>())
            .autoApprove(new LinkedHashSet<>())
            .outputs(new ArrayList<>())
            .build();

        // Block with no timestamps (legacy / skipped block)
        BlockOutput bo = BlockOutput.builder()
            .blockId("skipped").outputJson("{\"_skipped\":true}")
            .build();

        PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
        BlockOutputRepository blockRepo = mock(BlockOutputRepository.class);
        when(runRepo.findWithCollectionsById(runId)).thenReturn(Optional.of(run));
        when(blockRepo.findByRunIdOrderByStartedAt(runId)).thenReturn(List.of(bo));

        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        RunController controller = new RunController();
        ReflectionTestUtils.setField(controller, "pipelineRunRepository", runRepo);
        ReflectionTestUtils.setField(controller, "blockOutputRepository", blockRepo);
        ReflectionTestUtils.setField(controller, "objectMapper", mapper);
        ReflectionTestUtils.setField(controller, "projectRepository", null);

        ResponseEntity<?> response = controller.getRun(runId.toString());
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
        assertNotNull(events);
        assertEquals(1, events.size());
        assertNull(events.get(0).get("durationMs"), "durationMs must be null when timestamps absent");
    }
}
