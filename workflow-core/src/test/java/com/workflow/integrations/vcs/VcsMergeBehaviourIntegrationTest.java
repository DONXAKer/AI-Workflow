package com.workflow.integrations.vcs;

import com.workflow.integrations.vcs.VcsProvider.MergeResult;
import com.workflow.integrations.vcs.VcsProvider.MergeStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class VcsMergeBehaviourIntegrationTest {

    @Test
    void mergeStrategy_Merge_ReturnsMergedResult() {
        // Test merge strategy enum
        MergeStrategy strategy = MergeStrategy.MERGE;
        assertEquals("MERGE", strategy.name());
        
        // Test merge result creation
        MergeResult result = MergeResult.merged("abc123");
        assertEquals("merged", result.status());
        assertEquals("abc123", result.mergeSha());
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void mergeStrategy_Squash_ReturnsMergedResult() {
        MergeStrategy strategy = MergeStrategy.SQUASH;
        assertEquals("SQUASH", strategy.name());
        
        MergeResult result = MergeResult.merged("def456");
        assertEquals("merged", result.status());
        assertEquals("def456", result.mergeSha());
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void mergeStrategy_Rebase_ReturnsMergedResult() {
        MergeStrategy strategy = MergeStrategy.REBASE;
        assertEquals("REBASE", strategy.name());
        
        MergeResult result = MergeResult.merged("ghi789");
        assertEquals("merged", result.status());
        assertEquals("ghi789", result.mergeSha());
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void mergeResult_Conflict_ReturnsConflictResult() {
        List<String> conflicts = List.of("file1.txt", "src/main/java/Conflict.java");
        MergeResult result = MergeResult.conflict(conflicts);
        
        assertEquals("conflict", result.status());
        assertNull(result.mergeSha());
        assertEquals(conflicts, result.conflicts());
        assertFalse(result.conflicts().isEmpty());
        assertEquals(2, result.conflicts().size());
    }

    @Test
    void mergeResult_EmptyConflicts_ReturnsConflictResultWithEmptyList() {
        List<String> conflicts = List.of();
        MergeResult result = MergeResult.conflict(conflicts);
        
        assertEquals("conflict", result.status());
        assertNull(result.mergeSha());
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void mergeStrategy_Values_AllStrategiesPresent() {
        MergeStrategy[] strategies = MergeStrategy.values();
        assertEquals(3, strategies.length);
        
        boolean hasMerge = false;
        boolean hasSquash = false;
        boolean hasRebase = false;
        
        for (MergeStrategy strategy : strategies) {
            if (strategy == MergeStrategy.MERGE) hasMerge = true;
            if (strategy == MergeStrategy.SQUASH) hasSquash = true;
            if (strategy == MergeStrategy.REBASE) hasRebase = true;
        }
        
        assertTrue(hasMerge);
        assertTrue(hasSquash);
        assertTrue(hasRebase);
    }

    @Test
    void mergeResult_RecordMethods_WorkCorrectly() {
        List<String> conflicts = List.of("test.txt");
        MergeResult result = new MergeResult("merged", "sha123", conflicts);
        
        assertEquals("merged", result.status());
        assertEquals("sha123", result.mergeSha());
        assertEquals(conflicts, result.conflicts());
        
        // Test equals
        MergeResult sameResult = new MergeResult("merged", "sha123", conflicts);
        assertEquals(result, sameResult);
        
        // Test hashCode
        assertEquals(result.hashCode(), sameResult.hashCode());
        
        // Test toString
        String toString = result.toString();
        assertTrue(toString.contains("merged"));
        assertTrue(toString.contains("sha123"));
    }
}