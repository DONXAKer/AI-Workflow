package com.workflow.billing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only ledger row: one immutable record per balance change on a {@link Wallet}.
 * Never updated or deleted — the sum of {@code amountUsd} for an account always equals
 * its current {@code Wallet.balanceUsd}.
 *
 * <p>The unique constraint on {@code (llm_call_id, type)} is the hard backstop against
 * double-charging: a retried debit transaction's second {@code DEBIT} insert collides and
 * rolls back. TOPUP rows have a null {@code llm_call_id} (SQL treats NULLs as distinct, so
 * they never collide).
 */
@Entity
@Table(name = "ledger_entry",
    uniqueConstraints = @UniqueConstraint(name = "uq_ledger_llmcall_type",
        columnNames = {"llm_call_id", "type"}),
    indexes = {
        @Index(name = "idx_ledger_account", columnList = "account_id, createdAt"),
        @Index(name = "idx_ledger_payment", columnList = "payment_ref")
    })
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LedgerEntryType type;

    /** Signed: positive for TOPUP/REFUND, negative for DEBIT. */
    @Column(name = "amount_usd", nullable = false, precision = 12, scale = 4)
    private BigDecimal amountUsd;

    /** Wallet balance immediately after this entry was applied. */
    @Column(name = "balance_after_usd", nullable = false, precision = 12, scale = 4)
    private BigDecimal balanceAfterUsd;

    /** Run that triggered the charge (DEBIT entries); null otherwise. */
    @Column(name = "run_id")
    private UUID runId;

    /** LlmCall that triggered the charge (DEBIT entries) — idempotency key; null otherwise. */
    @Column(name = "llm_call_id")
    private Long llmCallId;

    /** Payment provider reference (TOPUP entries) — idempotency key for webhooks; null otherwise. */
    @Column(name = "payment_ref")
    private String paymentRef;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public LedgerEntryType getType() { return type; }
    public void setType(LedgerEntryType type) { this.type = type; }
    public BigDecimal getAmountUsd() { return amountUsd; }
    public void setAmountUsd(BigDecimal amountUsd) { this.amountUsd = amountUsd; }
    public BigDecimal getBalanceAfterUsd() { return balanceAfterUsd; }
    public void setBalanceAfterUsd(BigDecimal balanceAfterUsd) { this.balanceAfterUsd = balanceAfterUsd; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public Long getLlmCallId() { return llmCallId; }
    public void setLlmCallId(Long llmCallId) { this.llmCallId = llmCallId; }
    public String getPaymentRef() { return paymentRef; }
    public void setPaymentRef(String paymentRef) { this.paymentRef = paymentRef; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getCreatedAt() { return createdAt; }
}
