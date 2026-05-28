package com.workflow.billing;

/**
 * Kind of {@link LedgerEntry}. The ledger is append-only — entries are never updated or
 * deleted, so the running balance is fully auditable.
 *
 * <ul>
 *   <li>{@link #TOPUP} — customer added funds (positive amount).</li>
 *   <li>{@link #DEBIT} — LLM usage charge: {@code costUsd × markup} (negative amount).</li>
 *   <li>{@link #REFUND} — funds returned to the customer (positive amount).</li>
 *   <li>{@link #ADJUSTMENT} — manual correction by platform staff (signed).</li>
 * </ul>
 */
public enum LedgerEntryType {
    TOPUP,
    DEBIT,
    REFUND,
    ADJUSTMENT
}
