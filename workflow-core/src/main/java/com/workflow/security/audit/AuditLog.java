package com.workflow.security.audit;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Immutable append-only record of security-relevant actions: who did what, when, against
 * which target, with what outcome. Never update or delete rows — integrity is the whole
 * point of an audit trail.
 *
 * <p>Read path: {@code GET /api/audit} (ADMIN-only), filterable by actor/action/target/date.
 */
@Entity
@Table(name = "audit_log", indexes = {
    @Index(name = "idx_audit_timestamp", columnList = "timestamp"),
    @Index(name = "idx_audit_actor", columnList = "actor"),
    @Index(name = "idx_audit_target", columnList = "targetType,targetId"),
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String action;

    private String targetType;
    private String targetId;

    @Column(columnDefinition = "TEXT")
    private String detailsJson;

    @Column(nullable = false)
    private String outcome = "SUCCESS";

    private String remoteAddr;

    @Column(nullable = false)
    private String projectSlug = "default";

    public AuditLog() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getDetailsJson() { return detailsJson; }
    public void setDetailsJson(String detailsJson) { this.detailsJson = detailsJson; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public String getRemoteAddr() { return remoteAddr; }
    public void setRemoteAddr(String remoteAddr) { this.remoteAddr = remoteAddr; }

    public String getProjectSlug() { return projectSlug; }
    public void setProjectSlug(String projectSlug) {
        this.projectSlug = projectSlug != null ? projectSlug : "default";
    }
}
