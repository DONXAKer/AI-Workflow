package com.workflow.knowledge;

import com.workflow.knowledge.ImportGraphService.Neighbor;
import com.workflow.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BFS query semantics of {@link ImportGraphService#query} — direction, depth, cap. */
class ImportGraphServiceQueryTest {

    private ProjectIndexEdgeRepository edges;
    private ImportGraphService svc;

    private static ProjectIndexEdge e(String from, String to) {
        return new ProjectIndexEdge("p", from, to, "import", 1);
    }

    @BeforeEach
    void setUp() {
        edges = mock(ProjectIndexEdgeRepository.class);
        svc = new ImportGraphService(mock(ProjectRepository.class), edges);
        // Graph: A→B, B→C, and X→A (X imports A).
        when(edges.findByProjectSlugAndFromPath("p", "A")).thenReturn(List.of(e("A", "B")));
        when(edges.findByProjectSlugAndFromPath("p", "B")).thenReturn(List.of(e("B", "C")));
        when(edges.findByProjectSlugAndToPath("p", "A")).thenReturn(List.of(e("X", "A")));
    }

    @Test
    void out_depth1_returnsDirectDependencies() {
        List<Neighbor> r = svc.query("p", "A", "out", 1, 50);
        assertThat(r).extracting(Neighbor::path).containsExactly("B");
        assertThat(r.get(0).direction()).isEqualTo("out");
    }

    @Test
    void out_depth2_followsTransitively() {
        List<Neighbor> r = svc.query("p", "A", "out", 2, 50);
        assertThat(r).extracting(Neighbor::path).containsExactly("B", "C");
        assertThat(r).filteredOn(n -> n.path().equals("C")).first()
            .extracting(Neighbor::depth).isEqualTo(2);
    }

    @Test
    void in_depth1_returnsDependents() {
        List<Neighbor> r = svc.query("p", "A", "in", 1, 50);
        assertThat(r).extracting(Neighbor::path).containsExactly("X");
        assertThat(r.get(0).direction()).isEqualTo("in");
    }

    @Test
    void both_unionsOutAndIn() {
        List<Neighbor> r = svc.query("p", "A", "both", 1, 50);
        assertThat(r).extracting(Neighbor::path).containsExactlyInAnyOrder("B", "X");
    }

    @Test
    void maxNodes_capsResults() {
        List<Neighbor> r = svc.query("p", "A", "out", 3, 1);
        assertThat(r).hasSize(1);
    }

    @Test
    void noEdges_returnsEmpty() {
        assertThat(svc.query("p", "Z", "both", 2, 50)).isEmpty();
    }
}
