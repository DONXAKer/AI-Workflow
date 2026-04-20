package com.workflow.tools;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ToolCallAuditRepository extends JpaRepository<ToolCallAudit, Long> {

    List<ToolCallAudit> findByRunIdOrderByTimestampAsc(UUID runId);

    List<ToolCallAudit> findByRunIdAndBlockIdOrderByTimestampAsc(UUID runId, String blockId);

    long countByToolNameAndIsErrorTrue(String toolName);
}
