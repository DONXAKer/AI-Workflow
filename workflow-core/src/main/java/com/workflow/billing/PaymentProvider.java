package com.workflow.billing;

import java.math.BigDecimal;

/**
 * Abstraction over a payment provider (Stripe today; YooKassa et al. drop in behind the
 * same interface). The active provider is selected by {@code workflow.billing.provider}.
 *
 * <p>Two operations: create a hosted checkout session (customer is redirected to pay), and
 * verify + parse an incoming webhook (the provider calls us back when payment completes).
 * Crediting the wallet is {@code BillingService}'s job — the provider only reports facts.
 */
public interface PaymentProvider {

    /** Provider id, e.g. {@code "stripe"} — matches {@code workflow.billing.provider}. */
    String id();

    /**
     * Creates a hosted checkout session for a wallet top-up.
     *
     * @param accountId  account being topped up (echoed back on the webhook)
     * @param amountUsd  top-up amount in USD
     * @param successUrl where the provider returns the customer after a successful payment
     * @param cancelUrl  where the provider returns the customer if they cancel
     * @return the created session — redirect the customer to {@link CheckoutSession#url()}
     * @throws PaymentException if the provider is not configured or the call fails
     */
    CheckoutSession createCheckout(Long accountId, BigDecimal amountUsd,
                                   String successUrl, String cancelUrl);

    /**
     * Verifies a webhook's signature against the raw payload and extracts the payment.
     *
     * @param payload   raw request body — the signature is computed over the raw bytes
     * @param signature the provider's signature header value
     * @return the completed top-up, or {@code null} for webhook events that are not a
     *         completed payment (those are acknowledged and ignored)
     * @throws PaymentException on an invalid signature or malformed payload
     */
    PaymentEvent parseWebhook(String payload, String signature);

    /** A created checkout session — the customer is redirected to {@link #url()}. */
    record CheckoutSession(String url, String reference) {}

    /** A completed top-up payment extracted from a verified webhook. */
    record PaymentEvent(String paymentRef, Long accountId, BigDecimal amountUsd) {}
}
