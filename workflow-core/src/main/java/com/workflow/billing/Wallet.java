package com.workflow.billing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Prepaid USD balance for one {@link com.workflow.account.Account}. Customers top it up;
 * every LLM call debits {@code costUsd × markup} via {@code BillingService}. A run halts
 * once the balance is exhausted.
 *
 * <p>{@code balanceUsd} is a {@link BigDecimal} — money is never a {@code double}.
 * {@code @Version} guards against lost updates when parallel pipeline blocks debit the
 * same wallet concurrently (the {@code FOR UPDATE} lock in {@code WalletRepository} is the
 * primary guard; the version is a secondary backstop).
 */
@Entity
@Table(name = "wallet", indexes = {
    @Index(name = "uq_wallet_account", columnList = "account_id", unique = true)
})
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, unique = true)
    private Long accountId;

    @Column(name = "balance_usd", nullable = false, precision = 12, scale = 4)
    private BigDecimal balanceUsd = BigDecimal.ZERO;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
        if (balanceUsd == null) balanceUsd = BigDecimal.ZERO;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public Wallet() {}

    public Wallet(Long accountId) {
        this.accountId = accountId;
        this.balanceUsd = BigDecimal.ZERO;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public BigDecimal getBalanceUsd() { return balanceUsd != null ? balanceUsd : BigDecimal.ZERO; }
    public void setBalanceUsd(BigDecimal balanceUsd) { this.balanceUsd = balanceUsd; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
