package com.workflow.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IntegrationConfigRepository extends JpaRepository<IntegrationConfig, Long> {

    List<IntegrationConfig> findByType(IntegrationType type);

    Optional<IntegrationConfig> findByName(String name);

    Optional<IntegrationConfig> findByTypeAndIsDefaultTrue(IntegrationType type);

    List<IntegrationConfig> findByProjectSlug(String projectSlug);

    Optional<IntegrationConfig> findByNameAndProjectSlug(String name, String projectSlug);

    Optional<IntegrationConfig> findByTypeAndIsDefaultTrueAndProjectSlug(IntegrationType type, String projectSlug);
}
