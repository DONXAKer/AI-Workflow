package com.workflow.core.expr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflow.core.BlockOutput;
import com.workflow.core.PipelineRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InterpolationTest {

    private ObjectMapper om;
    private PathResolver resolver;
    private StringInterpolator interpolator;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        resolver = new PathResolver(om);
        interpolator = new StringInterpolator(resolver);
    }

    private PipelineRun runWith(String blockId, Map<String, Object> output) throws Exception {
        PipelineRun run = new PipelineRun();
        BlockOutput bo = new BlockOutput();
        bo.setBlockId(blockId);
        bo.setOutputJson(om.writeValueAsString(output));
        run.setOutputs(new java.util.ArrayList<>(List.of(bo)));
        return run;
    }

    @Nested
    class PathResolverTest {
        @Test
        void resolvesTopLevelField() throws Exception {
            PipelineRun run = runWith("analysis", Map.of("complexity", "high"));
            assertEquals("high", resolver.resolve("analysis.complexity", run));
            assertEquals("high", resolver.resolve("$.analysis.complexity", run));
        }

        @Test
        void resolvesNestedPath() throws Exception {
            PipelineRun run = runWith("impl", Map.of(
                "meta", Map.of("author", "claude", "cost", 0.03)));
            assertEquals("claude", resolver.resolve("impl.meta.author", run));
            assertEquals(0.03, resolver.resolve("impl.meta.cost", run));
        }

        @Test
        void resolvesListValue() throws Exception {
            PipelineRun run = runWith("impl", Map.of(
                "tool_calls_made", List.of("Read", "Write")));
            Object v = resolver.resolve("impl.tool_calls_made", run);
            assertEquals(List.of("Read", "Write"), v);
        }

        @Test
        void missingBlockThrows() {
            PipelineRun run = new PipelineRun();
            run.setOutputs(new java.util.ArrayList<>());
            PathNotFoundException ex = assertThrows(PathNotFoundException.class,
                () -> resolver.resolve("nope.field", run));
            assertTrue(ex.getMessage().contains("no output for block 'nope'"));
        }

        @Test
        void missingFieldThrows() throws Exception {
            PipelineRun run = runWith("analysis", Map.of("complexity", "high"));
            PathNotFoundException ex = assertThrows(PathNotFoundException.class,
                () -> resolver.resolve("analysis.missing", run));
            assertTrue(ex.getMessage().contains("missing key 'missing'"));
            assertTrue(ex.getMessage().contains("complexity"));
        }

        @Test
        void cannotDescendIntoScalar() throws Exception {
            PipelineRun run = runWith("impl", Map.of("version", "1.0"));
            PathNotFoundException ex = assertThrows(PathNotFoundException.class,
                () -> resolver.resolve("impl.version.major", run));
            assertTrue(ex.getMessage().contains("cannot descend"));
        }

        @Test
        void shortPathThrows() throws Exception {
            PipelineRun run = runWith("impl", Map.of("x", 1));
            assertThrows(PathNotFoundException.class, () -> resolver.resolve("impl", run));
            assertThrows(PathNotFoundException.class, () -> resolver.resolve("", run));
            assertThrows(PathNotFoundException.class, () -> resolver.resolve(null, run));
        }
    }

    @Nested
    class StringInterpolatorTest {
        @Test
        void substitutesSinglePlaceholder() throws Exception {
            PipelineRun run = runWith("analysis", Map.of("summary", "add caching layer"));
            String out = interpolator.interpolate("Task: ${analysis.summary}", run);
            assertEquals("Task: add caching layer", out);
        }

        @Test
        void substitutesMultipleAndNested() throws Exception {
            PipelineRun run = runWith("impl", Map.of(
                "meta", Map.of("author", "claude"),
                "iters", 3));
            String out = interpolator.interpolate(
                "${impl.meta.author} ran ${impl.iters} iterations", run);
            assertEquals("claude ran 3 iterations", out);
        }

        @Test
        void noPlaceholdersIsIdentity() throws Exception {
            PipelineRun run = runWith("x", Map.of("y", 1));
            assertEquals("plain text", interpolator.interpolate("plain text", run));
        }

        @Test
        void missingPathThrows() throws Exception {
            PipelineRun run = runWith("analysis", Map.of("summary", "x"));
            assertThrows(PathNotFoundException.class,
                () -> interpolator.interpolate("${analysis.nope}", run));
        }

        @Test
        void inputPrefixReadsInputMap() throws Exception {
            PipelineRun run = new PipelineRun();
            run.setOutputs(new java.util.ArrayList<>());
            Map<String, Object> input = Map.of("requirement", "fix bug 42");
            String out = interpolator.interpolate("Req: ${input.requirement}", run, input);
            assertEquals("Req: fix bug 42", out);
        }

        @Test
        void inputAndOutputMixed() throws Exception {
            PipelineRun run = runWith("analysis", Map.of("summary", "plan A"));
            Map<String, Object> input = Map.of("ticket", "BUG-1");
            String out = interpolator.interpolate(
                "${input.ticket}: ${analysis.summary}", run, input);
            assertEquals("BUG-1: plan A", out);
        }

        @Test
        void missingInputKeyThrows() throws Exception {
            PipelineRun run = new PipelineRun();
            run.setOutputs(new java.util.ArrayList<>());
            assertThrows(PathNotFoundException.class,
                () -> interpolator.interpolate("${input.nope}", run, Map.of("x", 1)));
        }

        @Test
        void regexMetacharsInReplacementAreEscaped() throws Exception {
            PipelineRun run = runWith("impl", Map.of("code", "$1 $2 \\n"));
            String out = interpolator.interpolate("got: ${impl.code}", run);
            assertEquals("got: $1 $2 \\n", out);
        }

        @Test
        void nullTemplateIsNull() {
            PipelineRun run = new PipelineRun();
            assertNull(interpolator.interpolate(null, run));
        }
    }
}
