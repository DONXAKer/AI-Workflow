package com.workflow.billing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /** Idempotency guard for LLM-usage debits — see {@link BillingService#debitForLlmCall}. */
    boolean existsByLlmCallIdAndType(Long llmCallId, LedgerEntryType type);

    /** Idempotency lookup for payment-provider top-ups (duplicate / replayed webhooks). */
    Optional<LedgerEntry> findByPaymentRef(String paymentRef);

    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);
}
