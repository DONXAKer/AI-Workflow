package com.workflow.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoProgressDetectorTest {

    private final NoProgressDetector detector = new NoProgressDetector();
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void identicalIssuesAreStuck() {
        List<List<String>> prior = List.of(List.of(
                "Field 'affected_components' must have at least 1 item",
                "Score below threshold: 4 < 7"));
        List<String> current = List.of(
                "Field 'affected_components' must have at least 1 item",
                "Score below threshold: 4 < 7");

        assertThat(detector.isStuck(prior, current, 0.8)).isTrue();
    }

    @Test
    void substantiallyDifferentIssuesAreNotStuck() {
        List<List<String>> prior = List.of(List.of(
                "Field 'affected_components' must have at least 1 item",
                "Score below threshold: 4 < 7"));
        List<String> current = List.of(
                "Test coverage is below 50%",
                "SQL injection risk in UserService.findByName",
                "Missing null-check in PaymentValidator");

        assertThat(detector.isStuck(prior, current, 0.8)).isFalse();
    }

    @Test
    void minorWordingDriftStillStuckAboveThreshold() {
        // Same semantic issues, slightly reworded — normalize strips trailing details.
        List<List<String>> prior = List.of(List.of(
                "Field affected_components must have at least 1 item.",
                "Score below threshold: 4 < 7"));
        List<String> current = List.of(
                "Field affected_components must have at least 1 item!",
                "Score below threshold: 4 < 7");

        assertThat(detector.isStuck(prior, current, 0.8)).isTrue();
    }

    @Test
    void firstIterationIsNeverStuck() {
        // No prior history → impossible to detect repetition.
        assertThat(detector.isStuck(List.of(), List.of("Some issue"), 0.8)).isFalse();
    }

    @Test
    void emptyCurrentIssuesAreNotStuck() {
        // Empty current means verify passed or check yielded nothing —
        // shouldn't masquerade as a stuck chain.
        List<List<String>> prior = List.of(List.of("Past issue"));
        assertThat(detector.isStuck(prior, List.of(), 0.8)).isFalse();
    }

    @Test
    void singleOverlappingIssueAmongManyIsNotStuck() {
        List<List<String>> prior = List.of(List.of(
                "Issue A", "Issue B", "Issue C", "Issue D"));
        List<String> current = List.of(
                "Issue A", "Brand new issue 1", "Brand new issue 2", "Brand new issue 3");

        // Jaccard = 1 / 7 ≈ 0.14 — clearly below 0.8.
        assertThat(detector.isStuck(prior, current, 0.8)).isFalse();
    }

    @Test
    void thresholdAboveOneDisablesDetector() {
        List<List<String>> prior = List.of(List.of("Same issue"));
        List<String> current = List.of("Same issue");

        assertThat(detector.isStuck(prior, current, 1.5)).isFalse();
    }

    @Test
    void extractPriorIssuesFiltersByLoopKey() throws Exception {
        String history = """
                [
                  {"from_block":"verify_code","to_block":"codegen","iteration":1,
                   "issues":["A","B"]},
                  {"from_block":"verify_other","to_block":"different_target","iteration":1,
                   "issues":["unrelated"]},
                  {"from_block":"verify_code","to_block":"codegen","iteration":2,
                   "issues":["C","D"]}
                ]
                """;
        List<List<String>> result = detector.extractPriorIssues(history, "verify_code", "codegen", om);
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsExactly("A", "B");
        assertThat(result.get(1)).containsExactly("C", "D");
    }

    @Test
    void extractPriorIssuesHandlesMalformedJson() {
        // Real-world: schema migration left garbage in column.
        List<List<String>> result = detector.extractPriorIssues(
                "{not valid json", "x", "y", om);
        assertThat(result).isEmpty();
    }

    @Test
    void extractPriorIssuesHandlesEmptyHistory() {
        assertThat(detector.extractPriorIssues("[]", "x", "y", om)).isEmpty();
        assertThat(detector.extractPriorIssues(null, "x", "y", om)).isEmpty();
        assertThat(detector.extractPriorIssues("", "x", "y", om)).isEmpty();
    }

    @Test
    void jaccardSimilarityHandlesUnicodeIssues() {
        List<List<String>> prior = List.of(List.of(
                "Не хватает поля affected_components",
                "Балл ниже порога: 4 < 7"));
        List<String> current = List.of(
                "Не хватает поля affected_components",
                "Балл ниже порога: 4 < 7");

        assertThat(detector.isStuck(prior, current, 0.8)).isTrue();
    }
}
