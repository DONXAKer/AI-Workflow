package com.workflow.blocks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers parsing of failed test FQNs from various test-runner console outputs.
 * Driven by real WarCard preflight log excerpts that surfaced the gap.
 */
class PreflightFqnExtractionTest {

    @Test
    void gradleJunit5_extractsClassDotMethod() {
        String log = """
                > Task :test

                RollDiceCommandHandlerTest > testDiceRoll2TransitionsToMulligan() FAILED
                    java.lang.ClassCastException at RollDiceCommandHandlerTest.java:129

                RollDiceCommandHandlerTest > testRejectsRollDiceFromNonPlayer() FAILED
                    java.lang.ClassCastException at RollDiceCommandHandlerTest.java:209

                SurrenderCommandHandlerTest > testSurrenderNotPlayerTurn() FAILED
                    java.lang.IllegalStateException at SurrenderCommandHandlerTest.java:191

                GameCommandIntegrationTest > testCommandImplementsGameCommand() FAILED
                    java.lang.IllegalStateException at DefaultCacheAwareContextLoaderDelegate.java:145

                123 tests completed, 4 failed
                """;
        List<String> fqns = PreflightBlock.extractFailedTestFqns(log, "junit5");
        assertEquals(4, fqns.size());
        assertTrue(fqns.contains("RollDiceCommandHandlerTest.testDiceRoll2TransitionsToMulligan"));
        assertTrue(fqns.contains("RollDiceCommandHandlerTest.testRejectsRollDiceFromNonPlayer"));
        assertTrue(fqns.contains("SurrenderCommandHandlerTest.testSurrenderNotPlayerTurn"));
        assertTrue(fqns.contains("GameCommandIntegrationTest.testCommandImplementsGameCommand"));
    }

    @Test
    void gradleJunit_dedupesRepeatedFailures() {
        // Same test reported twice (e.g. once in summary, once in details)
        String log = """
                FooTest > testBar() FAILED
                    java.lang.AssertionError

                FooTest > testBar() FAILED
                    something
                """;
        List<String> fqns = PreflightBlock.extractFailedTestFqns(log, "junit5");
        assertEquals(1, fqns.size());
        assertEquals("FooTest.testBar", fqns.get(0));
    }

    @Test
    void gradleJunit_handlesParameterizedNames() {
        String log = "MyTest > testBoundary(int)[1] FAILED\n";
        List<String> fqns = PreflightBlock.extractFailedTestFqns(log, "junit5");
        assertEquals(1, fqns.size());
        assertEquals("MyTest.testBoundary", fqns.get(0));
    }

    @Test
    void pytest_extractsTestNodeIds() {
        String log = """
                ============================== short test summary ==============================
                FAILED tests/unit/test_foo.py::TestClass::test_method - AssertionError
                FAILED tests/integration/test_bar.py::test_alone - ValueError: ...
                =================== 2 failed, 5 passed in 0.42s ===========================
                """;
        List<String> fqns = PreflightBlock.extractFailedTestFqns(log, "pytest");
        assertEquals(2, fqns.size());
        assertTrue(fqns.contains("tests/unit/test_foo.py::TestClass::test_method"));
        assertTrue(fqns.contains("tests/integration/test_bar.py::test_alone"));
    }

    @Test
    void jest_extractsFailedFiles() {
        String log = """
                FAIL src/components/Button.test.tsx
                  ● Button › renders correctly

                FAIL src/utils/parser.test.ts
                  ● parser › handles empty input
                """;
        List<String> fqns = PreflightBlock.extractFailedTestFqns(log, "jest");
        assertEquals(2, fqns.size());
        assertTrue(fqns.contains("src/components/Button.test.tsx"));
        assertTrue(fqns.contains("src/utils/parser.test.ts"));
    }

    @Test
    void goTest_extractsTestNames() {
        String log = """
                === RUN   TestFooBar
                --- FAIL: TestFooBar (0.00s)
                === RUN   TestQuxBaz
                --- FAIL: TestQuxBaz (0.01s)
                FAIL
                """;
        List<String> fqns = PreflightBlock.extractFailedTestFqns(log, "go");
        assertEquals(2, fqns.size());
        assertTrue(fqns.contains("TestFooBar"));
        assertTrue(fqns.contains("TestQuxBaz"));
    }

    @Test
    void unknownFormat_returnsEmpty() {
        String log = "FooTest > testBar() FAILED";
        assertTrue(PreflightBlock.extractFailedTestFqns(log, "none").isEmpty());
        assertTrue(PreflightBlock.extractFailedTestFqns(log, "weird").isEmpty());
    }

    @Test
    void emptyLog_returnsEmpty() {
        assertTrue(PreflightBlock.extractFailedTestFqns("", "junit5").isEmpty());
        assertTrue(PreflightBlock.extractFailedTestFqns(null, "junit5").isEmpty());
    }

    @Test
    void noFailuresInLog_returnsEmpty() {
        String log = """
                > Task :test
                123 tests completed, 0 failed

                BUILD SUCCESSFUL
                """;
        assertTrue(PreflightBlock.extractFailedTestFqns(log, "junit5").isEmpty());
    }
}
