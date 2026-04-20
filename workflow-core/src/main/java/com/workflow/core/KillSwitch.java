package com.workflow.core;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Per-project kill switch. One row per project (keyed by {@code projectSlug}). When
 * {@code active}, new runs in that project are rejected and, optionally, active runs
 * are cancelled.
 *
 * <p>Legacy singleton-id row (before project scoping) is auto-migrated on startup into
 * a row with slug {@code default} via Hibernate ddl-auto update.
 */
@Entity
@Table(name = "kill_switch", uniqueConstraints = @UniqueConstraint(columnNames = "projectSlug"))
public class KillSwitch {

    public static final Long SINGLETON_ID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String projectSlug = "default";

    @Column(nullable = false)
    private boolean active;

    @Column(columnDefinition = "TEXT")
    private String reason;

    private String activatedBy;
    private Instant activatedAt;

    public KillSwitch() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProjectSlug() { return projectSlug; }
    public void setProjectSlug(String projectSlug) {
        this.projectSlug = projectSlug != null ? projectSlug : "default";
    }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getActivatedBy() { return activatedBy; }
    public void setActivatedBy(String activatedBy) { this.activatedBy = activatedBy; }

    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }
}
