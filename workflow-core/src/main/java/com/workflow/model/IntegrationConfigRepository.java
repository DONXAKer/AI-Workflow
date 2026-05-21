package com.workflow.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IntegrationConfigRepository extends JpaRepository<IntegrationConfig, Long> {

    List<IntegrationConfig> findByType(IntegrationType type);

    Optional<IntegrationConfig> findByName(String name);

    /**
     * @deprecated Throws {@code NonUniqueResultException} (translated to
     * {@code IncorrectResultSizeDataAccessException}) when more than one row of
     * {@code type} has {@code is_default=true} — the real DB state has several
     * per-project default rows. Use {@link #findFirstByTypeAndIsDefaultTrue}
     * which is {@code LIMIT 1} and never throws. See task FIX-LLM-001.
     */
    @Deprecated
    Optional<IntegrationConfig> findByTypeAndIsDefaultTrue(IntegrationType type);

    /**
     * Global-default lookup that tolerates a non-unique state: returns the first
     * {@code is_default=true} row of {@code type} (LIMIT 1) or empty, instead of
     * throwing when several projects each carry a default row of that type.
     * Drop-in replacement for {@link #findByTypeAndIsDefaultTrue} at every
     * fallback call-site (FIX-LLM-001).
     */
    Optional<IntegrationConfig> findFirstByTypeAndIsDefaultTrue(IntegrationType type);

    List<IntegrationConfig> findByProjectSlug(String projectSlug);

    Optional<IntegrationConfig> findByNameAndProjectSlug(String name, String projectSlug);

    Optional<IntegrationConfig> findByTypeAndIsDefaultTrueAndProjectSlug(IntegrationType type, String projectSlug);
}
