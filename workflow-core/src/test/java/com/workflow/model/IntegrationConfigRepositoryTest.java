package com.workflow.model;

import com.workflow.security.SecretStore;
import com.workflow.security.SecretStoreHolder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for FIX-LLM-001: {@link IntegrationConfigRepository#findByTypeAndIsDefaultTrue}
 * throws when several rows of one type carry {@code is_default=true} — the real DB
 * state, where every project keeps its own default integration row. The replacement
 * {@link IntegrationConfigRepository#findFirstByTypeAndIsDefaultTrue} is LIMIT-1 and
 * must return one row instead of blowing up the whole provider client.
 */
@DataJpaTest
class IntegrationConfigRepositoryTest {

    @Autowired
    private IntegrationConfigRepository repo;

    @BeforeAll
    static void initSecretStore() {
        // EncryptedStringConverter (on IntegrationConfig.token) reads SecretStoreHolder
        // statically; @DataJpaTest does not load the Spring-managed holder. Wire an
        // identity store so persisting rows does not blow up inside the JPA converter.
        new SecretStoreHolder().setSecretStore(new SecretStore() {
            @Override public String encrypt(String plaintext) { return plaintext; }
            @Override public String decrypt(String ciphertext) { return ciphertext; }
        });
    }

    private IntegrationConfig defaultRow(String name, String projectSlug) {
        IntegrationConfig c = new IntegrationConfig();
        c.setName(name);
        c.setType(IntegrationType.ALLTOKENS);
        c.setProjectSlug(projectSlug);
        c.setDefault(true);
        return c;
    }

    @Test
    void findFirst_returnsOneRow_whenSeveralDefaultsExist() {
        // One global-sentinel default + two per-project defaults — the exact shape
        // that made findByTypeAndIsDefaultTrue throw NonUniqueResultException.
        repo.save(defaultRow("alltokens-global", "default"));
        repo.save(defaultRow("alltokens-projA", "projA"));
        repo.save(defaultRow("alltokens-projB", "projB"));

        // Legacy single-result query still blows up on a non-unique state (Spring
        // translates Hibernate's NonUniqueResultException to a DataAccessException).
        assertThrows(RuntimeException.class,
            () -> repo.findByTypeAndIsDefaultTrue(IntegrationType.ALLTOKENS),
            "legacy findByTypeAndIsDefaultTrue must still throw when >1 default exists");

        // The fix tolerates it: returns exactly one default row, no exception.
        Optional<IntegrationConfig> found = repo.findFirstByTypeAndIsDefaultTrue(IntegrationType.ALLTOKENS);
        assertTrue(found.isPresent(), "findFirst must return a row");
        assertEquals(IntegrationType.ALLTOKENS, found.get().getType());
        assertTrue(found.get().isDefault());
    }

    @Test
    void findFirst_returnsEmpty_whenNoDefaultExists() {
        IntegrationConfig nonDefault = defaultRow("alltokens-nondefault", "projC");
        nonDefault.setDefault(false);
        repo.save(nonDefault);

        assertTrue(repo.findFirstByTypeAndIsDefaultTrue(IntegrationType.ALLTOKENS).isEmpty(),
            "no is_default=true row of this type => empty Optional");
    }
}
