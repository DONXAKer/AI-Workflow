package com.workflow.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workflow.llm.tooluse.StopReason;
import com.workflow.llm.tooluse.ToolCall;
import com.workflow.llm.tooluse.ToolDefinition;
import com.workflow.llm.tooluse.ToolExecutor;
import com.workflow.llm.tooluse.ToolResult;
import com.workflow.llm.tooluse.ToolUseRequest;
import com.workflow.llm.tooluse.ToolUseResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hits real OpenRouter to prove the tool-use loop works end-to-end.
 *
 * <p>Skipped when {@code OPENROUTER_API_KEY} is unset — contributors without
 * a key can still run the rest of the test suite.
 *
 * <p>Uses an in-memory H2 so the shared dev DB (workflow-db) is not touched
 * and assertions on {@link LlmCallRepository} run against a clean slate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:tooluse-it;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "workflow.mode=cli"
})
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
@Tag("integration")
class LlmClientToolUseIT {

    @Autowired private LlmClient llmClient;
    @Autowired private LlmCallRepository llmCallRepository;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void toyCalculateToolResolvesArithmeticQuery() {
        ToolDefinition calculate = new ToolDefinition(
            "calculate",
            "Performs a single arithmetic operation on two numbers. "
                + "Use this for every arithmetic step — do not compute in text.",
            buildCalculateSchema());

        List<ToolCall> invocations = new ArrayList<>();
        ToolExecutor executor = call -> {
            invocations.add(call);
            JsonNode in = call.input();
            double a = in.path("a").asDouble();
            double b = in.path("b").asDouble();
            String op = in.path("op").asText();
            double result = switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> a / b;
                default -> Double.NaN;
            };
            String formatted = (result == Math.floor(result) && !Double.isInfinite(result))
                ? String.valueOf((long) result)
                : String.valueOf(result);
            return ToolResult.ok(call.id(), formatted);
        };

        ToolUseRequest request = ToolUseRequest.builder()
            .model("anthropic/claude-haiku-4-5")
            .systemPrompt("You solve math word problems using the calculate tool. "
                + "You MUST call calculate for every arithmetic operation — never "
                + "compute numbers in your head or inline in prose. "
                + "Chain multiple calls when an expression has several operations.")
            .userMessage("What is 17 * 23 + 5?")
            .tools(List.of(calculate))
            .maxTokens(1024)
            .temperature(0.0)
            .maxIterations(10)
            .budgetUsdCap(1.0)
            .build();

        long rowsBefore = llmCallRepository.count();
        ToolUseResponse response = llmClient.completeWithTools(request, executor);

        assertEquals(StopReason.END_TURN, response.stopReason(),
            () -> "expected END_TURN, got " + response.stopReason()
                + " — history=" + response.toolCallHistory());
        assertFalse(response.toolCallHistory().isEmpty(),
            "LLM should have invoked the calculate tool at least once");
        assertFalse(invocations.isEmpty(),
            "executor should have been called at least once");
        assertTrue(response.finalText().contains("396"),
            () -> "Expected final text to contain '396', got: " + response.finalText());

        List<LlmCall> toolUseRows = llmCallRepository.findAll().stream()
            .filter(c -> c.getIteration() >= 1)
            .toList();
        assertFalse(toolUseRows.isEmpty(),
            "Expected LlmCall rows with iteration >= 1 to be persisted");
        assertTrue(llmCallRepository.count() > rowsBefore,
            "Row count should increase after the tool-use loop");

        LlmCall firstIter = toolUseRows.stream()
            .filter(c -> c.getIteration() == 1)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no LlmCall row with iteration=1"));
        assertNotNull(firstIter.getToolCallsMadeJson(),
            "iteration-1 row should record tool names invoked that round");
        assertTrue(firstIter.getToolCallsMadeJson().contains("calculate"),
            () -> "toolCallsMadeJson should list 'calculate', got: "
                + firstIter.getToolCallsMadeJson());
    }

    private ObjectNode buildCalculateSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("a").put("type", "number");
        props.putObject("b").put("type", "number");
        ObjectNode op = props.putObject("op");
        op.put("type", "string");
        ArrayNode enumArr = op.putArray("enum");
        enumArr.add("+"); enumArr.add("-"); enumArr.add("*"); enumArr.add("/");
        ArrayNode required = schema.putArray("required");
        required.add("a"); required.add("b"); required.add("op");
        return schema;
    }
}
