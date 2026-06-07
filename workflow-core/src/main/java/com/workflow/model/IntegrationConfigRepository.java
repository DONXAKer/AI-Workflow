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
     * per-project default rows. Use
     * {@link #findByTypeAndIsDefaultTrueAndProjectSlug(IntegrationType, String)}
     * with {@code projectSlug = "default"} for the global sentinel, or with a
     * concrete project slug for project-scoped lookups. See FIX-LLM-001.
     */
    @Deprecated
    Optional<IntegrationConfig> findByTypeAndIsDefaultTrue(IntegrationType type);

    /**
     * @deprecated LIMIT-1 fallback that returns an arbitrary default row when
     * several projects each carry a row of {@code type} with {@code is_default=true}.
     * Semantically incorrect — it may return a project-scoped default when the
     * caller expects the global sentinel (whose {@code projectSlug} is
     * {@code "default"}). Use
     * {@link #findByTypeAndIsDefaultTrueAndProjectSlug(IntegrationType, String)}
     * instead. See FIX-LLM-001.
     */
    @Deprecated
    Optional<IntegrationConfig> findFirstByTypeAndIsDefaultTrue(IntegrationType type);

    List<IntegrationConfig> findByProjectSlug(String projectSlug);

    Optional<IntegrationConfig> findByNameAndProjectSlug(String name, String projectSlug);

    /**
     * Lookup the default integration of {@code type} scoped to {@code projectSlug}.
     * For the global sentinel row use projectSlug {@code "default"} — the entity
     * enforces {@code projectSlug NOT NULL} with a default of {@code "default"},
     * so the sentinel value is {@code "default"}, not {@code null}.
     * See FIX-LLM-001.
     */
    Optional<IntegrationConfig> findByTypeAndIsDefaultTrueAndProjectSlug(IntegrationType type, String projectSlug);
}
