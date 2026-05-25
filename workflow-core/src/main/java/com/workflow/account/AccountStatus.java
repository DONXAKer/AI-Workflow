package com.workflow.account;

/**
 * Lifecycle state of a customer {@link Account}.
 *
 * <ul>
 *   <li>{@link #ACTIVE} — normal operation; runs allowed.</li>
 *   <li>{@link #SUSPENDED} — temporarily blocked (non-payment, abuse); login allowed, runs rejected.</li>
 *   <li>{@link #CLOSED} — terminated; retained for audit/billing history only.</li>
 * </ul>
 */
public enum AccountStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED
}
