package com.workflow.preflight;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers cache TTL semantics + config-hash composition. Repository interactions are
 * mocked — these tests document the policy without dragging in JPA.
 */
class PreflightCacheServiceTest {

    private PreflightSnapshotRepository repo;
    private PreflightCacheService service;

    @BeforeEach
    void setUp() {
        repo = mock(PreflightSnapshotRepository.class);
        service = new PreflightCacheService(repo, Duration.ofDays(7));
    }

    @Test
    void lookup_freshSnapshot_returnsIt() {
        PreflightSnapshot s = sampleSnapshot(Instant.now().minus(Duration.ofHours(1)));
        when(repo.findFreshest("slug", "abc", "h")).thenReturn(Optional.of(s));

        Optional<PreflightSnapshot> got = service.lookup("slug", "abc", "h");
        assertTrue(got.isPresent());
        assertEquals(s, got.get());
    }

    @Test
    void lookup_staleSnapshot_returnsEmpty() {
        PreflightSnapshot s = sampleSnapshot(Instant.now().minus(Duration.ofDays(8)));
        when(repo.findFreshest("slug", "abc", "h")).thenReturn(Optional.of(s));

        assertTrue(service.lookup("slug", "abc", "h").isEmpty());
    }

    @Test
    void lookup_noMatch_returnsEmpty() {
        when(repo.findFreshest(any(), any(), any())).thenReturn(Optional.empty());
        assertTrue(service.lookup("slug", "abc", "h").isEmpty());
    }

    @Test
    void lookup_blankKey_returnsEmptyWithoutHittingRepo() {
        assertTrue(service.lookup(null, "abc", "h").isEmpty());
        assertTrue(service.lookup("slug", "", "h").isEmpty());
        assertTrue(service.lookup("slug", "abc", null).isEmpty());
        verify(repo, never()).findFreshest(any(), any(), any());
    }

    @Test
    void store_setsCreatedAtWhenNull() {
        PreflightSnapshot s = sampleSnapshot(null);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.store(s);

        ArgumentCaptor<PreflightSnapshot> cap = ArgumentCaptor.forClass(PreflightSnapshot.class);
        verify(repo).save(cap.capture());
        assertNotNull(cap.getValue().getCreatedAt());
    }

    @Test
    void invalidateForProject_delegatesToRepo() {
        when(repo.deleteByProjectSlug("warcard")).thenReturn(3);
        assertEquals(3, service.invalidateForProject("warcard"));
        verify(repo).deleteByProjectSlug("warcard");
    }

    @Test
    void computeConfigHash_isDeterministicAndDistinguishesDifferentCommands() {
        PreflightCommands a = new PreflightCommands("gradle build", "gradle test", "junit5",
                PreflightCommands.SOURCE_CLAUDE_MD, null);
        PreflightCommands b = new PreflightCommands("gradle build", "gradle test", "junit5",
                PreflightCommands.SOURCE_CLAUDE_MD, null);
        PreflightCommands c = new PreflightCommands("mvn package", "mvn test", "junit5",
                PreflightCommands.SOURCE_CLAUDE_MD, null);

        assertEquals(PreflightCacheService.computeConfigHash(a),
                PreflightCacheService.computeConfigHash(b),
                "Identical commands must produce identical hashes");
        assertNotEquals(PreflightCacheService.computeConfigHash(a),
                PreflightCacheService.computeConfigHash(c),
                "Different commands must produce different hashes");
    }

    @Test
    void computeConfigHash_isHexSha256() {
        PreflightCommands a = new PreflightCommands("b", "t", "junit5",
                PreflightCommands.SOURCE_CLAUDE_MD, null);
        String hash = PreflightCacheService.computeConfigHash(a);
        assertEquals(64, hash.length(), "SHA-256 hex == 64 chars");
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void computeConfigHash_treatsNullsAsEmpty() {
        PreflightCommands withNulls = new PreflightCommands(null, null, null,
                PreflightCommands.SOURCE_FALLBACK, null);
        PreflightCommands withEmpty = new PreflightCommands("", "", "",
                PreflightCommands.SOURCE_FALLBACK, null);
        assertEquals(PreflightCacheService.computeConfigHash(withNulls),
                PreflightCacheService.computeConfigHash(withEmpty));
    }

    private PreflightSnapshot sampleSnapshot(Instant createdAt) {
        PreflightSnapshot s = new PreflightSnapshot();
        s.setProjectSlug("slug");
        s.setMainCommitSha("abc");
        s.setConfigHash("h");
        s.setStatus(PreflightStatus.PASSED);
        s.setBuildOk(true);
        s.setTestOk(true);
        s.setBaselineFailuresJson("[]");
        s.setCreatedAt(createdAt);
        return s;
    }
}
