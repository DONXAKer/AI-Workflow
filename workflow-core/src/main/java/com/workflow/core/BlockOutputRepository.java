package com.workflow.core;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BlockOutputRepository extends JpaRepository<BlockOutput, Long> {

    List<BlockOutput> findByRunId(java.util.UUID runId);

    List<BlockOutput> findByRunIdAndBlockId(java.util.UUID runId, String blockId);
}
