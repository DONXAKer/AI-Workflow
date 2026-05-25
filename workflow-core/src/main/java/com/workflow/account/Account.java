package com.workflow.account;

import jakarta.persistence.*;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;

/**
 * A customer tenant. One account owns N {@link com.workflow.security.User}s, projects,
 * runs, integrations and a {@link com.workflow.billing.Wallet}. MVP onboards one user per
 * account, but the model is N-users&rarr;1-account so multi-seat is an additive change.
 *
 * <p>This class also declares the global {@code accountFilter} ({@link FilterDef}). The
 * filter is auto-enabled on every Hibernate session and its {@code accountId} parameter is
 * resolved by {@link TenantFilterResolver} from {@link TenantContext}. Every tenant-owned
 * entity carries a bare {@code @Filter(name = "accountFilter")} so cross-account reads are
 * blocked transparently — see {@link com.workflow.core.PipelineRun} et al.
 *
 * <p>The {@code defaultCondition} degrades to a no-op when the resolver returns
 * {@link TenantContext#NO_SCOPE} (platform staff, startup initializers, tests).
 */
@Entity
@Table(name = "account", indexes = { @Index(name = "idx_account_slug", columnList = "slug") })
@FilterDef(
    name = "accountFilter",
    autoEnabled = true,
    defaultCondition = "(:accountId = -1 or account_id = :accountId)",
    parameters = @ParamDef(name = "accountId", type = Long.class, resolver = TenantFilterResolver.class))
public class Account {

    /** Slug of the account that owns all pre-multi-tenancy rows; created by {@link AccountBackfillInitializer}. */
    public static final String LEGACY_SLUG = "legacy";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status = AccountStatus.ACTIVE;

    /** Plan tier — plain string for MVP; Phase 4 introduces a Tier/Plan entity. */
    @Column(nullable = false)
    private String tier = "free";

    /** Set when the customer finishes the onboarding wizard; null until then. */
    private Instant onboardedAt;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier != null ? tier : "free"; }
    public Instant getOnboardedAt() { return onboardedAt; }
    public void setOnboardedAt(Instant onboardedAt) { this.onboardedAt = onboardedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
