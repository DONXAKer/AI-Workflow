package com.workflow.api;

import com.workflow.billing.BillingService;
import com.workflow.billing.LedgerEntryType;
import com.workflow.billing.PaymentException;
import com.workflow.billing.PaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Receives payment-provider webhooks. {@code /api/webhooks/**} is already permit-all and
 * CSRF-exempt in {@code SecurityConfig} — the provider authenticates via a signed payload,
 * which {@link PaymentProvider#parseWebhook} verifies.
 *
 * <p>Idempotency: {@code BillingService.credit} dedupes on {@code paymentRef}, so a
 * replayed / duplicated webhook credits the wallet at most once. The body is taken as a
 * raw {@code String} because the signature is computed over the raw bytes.
 */
@RestController
@RequestMapping("/api/webhooks")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final BillingService billingService;
    private final PaymentProvider paymentProvider;

    public PaymentWebhookController(BillingService billingService,
                                    ObjectProvider<PaymentProvider> paymentProvider) {
        this.billingService = billingService;
        this.paymentProvider = paymentProvider.getIfAvailable();
    }

    @PostMapping("/payments")
    public ResponseEntity<Map<String, Object>> payments(
            @RequestBody(required = false) byte[] payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (paymentProvider == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Payments are not configured"));
        }
        // Take the body as raw bytes and decode UTF-8 ourselves — the provider's signature
        // is computed over the exact bytes, so charset guessing must not get in the way.
        String body = payload != null ? new String(payload, StandardCharsets.UTF_8) : "";
        try {
            PaymentProvider.PaymentEvent event = paymentProvider.parseWebhook(body, signature);
            if (event != null && event.accountId() != null) {
                billingService.credit(event.accountId(), event.amountUsd(),
                    LedgerEntryType.TOPUP, event.paymentRef(),
                    "Wallet top-up via " + paymentProvider.id());
                log.info("Webhook credited ${} to account {} (ref {})",
                    event.amountUsd(), event.accountId(), event.paymentRef());
            }
            return ResponseEntity.ok(Map.of("received", true));
        } catch (PaymentException e) {
            // 400 — a bad signature won't self-heal, but the provider's retry window is
            // bounded; a transient malformed body may succeed on retry.
            log.warn("Payment webhook rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
