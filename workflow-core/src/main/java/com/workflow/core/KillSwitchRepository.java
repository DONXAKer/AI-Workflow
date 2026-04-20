package com.workflow.core;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface KillSwitchRepository extends JpaRepository<KillSwitch, Long> {
    Optional<KillSwitch> findByProjectSlug(String projectSlug);
}
