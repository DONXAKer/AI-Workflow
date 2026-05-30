package com.workflow.blocks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.config.BlockConfig;
import com.workflow.core.PipelineRun;
import com.workflow.knowledge.KnowledgeBase;
import com.workflow.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PR-4 regression: a code-generation response that doesn't parse as JSON must be
 * <em>surfaced</em> (parse_failures / failed_tasks), not silently dropped — the old
 * {@code catch → continue} lost the whole task with no trace. Also asserts JSON mode is
 * requested ({@code responseFormat="json"}) and that a valid response still parses.
 */
class CodeGenerationBlockTest {

    private CodeGenerationBlock block;
    private LlmClient llmClient;
    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        block = new CodeGenerationBlock();
        llmClient = mock(LlmClient.class);
        knowledgeBase = mock(KnowledgeBase.class);
        when(knowledgeBase.query(anyString(), anyInt())).thenReturn("");
        ReflectionTestUtils.setField(block, "llmClient", llmClient);
        ReflectionTestUtils.setField(block, "knowledgeBase", knowledgeBase);
        ReflectionTestUtils.setField(block, "objectMapper", new ObjectMapper());
        // projectRepository / techContextInjector left null (both @Autowired(required=false)).
    }

    private Map<String, Object> input(String... taskSummaries) {
        var tasks = new java.util.ArrayList<Map<String, Object>>();
        for (String s : taskSummaries) {
            tasks.add(Map.of("summary", s, "description", "desc of " + s));
        }
        return Map.of("tasks", Map.of("tasks", tasks));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unparseableResponse_surfacesParseFailure_notSilentDrop() throws Exception {
        when(llmClient.complete(anyString(), anyString(), anyString(), anyInt(), anyDouble(), eq("json")))
            .thenReturn("Sorry, I cannot produce JSON. Here is some prose instead.");

        Map<String, Object> result = block.run(input("Add login"), new BlockConfig(), new PipelineRun());

        assertEquals(1, result.get("parse_failures"), "parse failure must be counted");
        assertTrue(((List<String>) result.get("failed_tasks")).contains("Add login"),
            "failed task summary must be recorded");
        assertEquals(0, result.get("tasks_generated"));
        assertTrue(((List<?>) result.get("changes")).isEmpty(), "no fabricated changes on failure");
    }

    @Test
    @SuppressWarnings("unchecked")
    void validResponse_parsesAndProducesChanges() throws Exception {
        String json = "{\"branch_name\":\"feature/x\",\"commit_message\":\"feat: x\","
            + "\"changes\":[{\"file_path\":\"A.java\",\"action\":\"create\",\"content\":\"class A{}\"}],"
            + "\"test_changes\":[]}";
        when(llmClient.complete(anyString(), anyString(), anyString(), anyInt(), anyDouble(), eq("json")))
            .thenReturn(json);

        Map<String, Object> result = block.run(input("Add X"), new BlockConfig(), new PipelineRun());

        assertEquals(0, result.get("parse_failures"));
        assertEquals(1, result.get("tasks_generated"));
        assertEquals(1, ((List<?>) result.get("changes")).size());
        assertEquals("feature/x", result.get("branch_name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void proseWrappedJson_recoveredByExtractor() throws Exception {
        // Weak model wraps JSON in prose — JsonExtractor must still recover it.
        String wrapped = "Конечно, вот изменения:\n"
            + "{\"branch_name\":\"feature/y\",\"changes\":[{\"file_path\":\"B.java\",\"content\":\"x\"}],"
            + "\"test_changes\":[],\"commit_message\":\"feat: y\"}\nГотово!";
        when(llmClient.complete(anyString(), anyString(), anyString(), anyInt(), anyDouble(), eq("json")))
            .thenReturn(wrapped);

        Map<String, Object> result = block.run(input("Add Y"), new BlockConfig(), new PipelineRun());

        assertEquals(0, result.get("parse_failures"));
        assertEquals(1, ((List<?>) result.get("changes")).size());
    }
}
