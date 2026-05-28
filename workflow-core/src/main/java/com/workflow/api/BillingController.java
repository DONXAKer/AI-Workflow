package com.workflow.api;

import com.workflow.account.TenantContext;
import com.workflow.billing.BillingService;
import com.workflow.billing.LedgerEntry;
import com.workflow.billing.LedgerEntryRepository;
import com.workflow.billing.LedgerEntryType;
import com.workflow.billing.PaymentException;
import com.workflow.billing.PaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Customer wallet endpoints. Balance and ledger are scoped to the caller's account via
 * {@link TenantContext}. Phase 1 has no payment provider — top-ups go through the
 * platform-staff-only {@code /admin/credit} endpoint; Phase 2 adds Stripe checkout.
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    /** Largest single top-up — a guard against fat-finger / abusive amounts. */
    private static final BigDecimal MAX_TOPUP_USD = BigDecimal.valueOf(1000);

    private final BillingService billingService;
    private final LedgerEntryRepository ledgerRepository;
    private final PaymentProvider paymentProvider;

    @Value("${workflow.app-base-url:http://localhost:5173}")
    private String appBaseUrl;

    public BillingController(BillingService billingService, LedgerEntryRepository ledgerRepository,
                             org.springframework.beans.factory.ObjectProvider<PaymentProvider> paymentProvider) {
        this.billingService = billingService;
        this.ledgerRepository = ledgerRepository;
        // Optional — absent if no PaymentProvider bean is on the classpath.
        this.paymentProvider = paymentProvider.getIfAvailable();
    }

    /**
     * Starts a self-serve wallet top-up: creates a hosted checkout session and returns its
     * URL for the browser to redirect to. The wallet is credited later, by the provider's
     * webhook ({@code PaymentWebhookController}).
     */
    @PostMapping("/checkout")
    public ResponseEntity<Map<String, Object>> checkout(@RequestBody Map<String, Object> body) {
        Long accountId = TenantContext.get();
        if (accountId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not in an account scope"));
        }
        if (paymentProvider == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Payments are not configured"));
        }
        if (!(body.get("amountUsd") instanceof Number amountNum)) {
            return ResponseEntity.badRequest().body(Map.of("error", "amountUsd is required"));
        }
        BigDecimal amount = BigDecimal.valueOf(amountNum.doubleValue());
        if (amount.signum() <= 0 || amount.compareTo(MAX_TOPUP_USD) > 0) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "amountUsd must be between 0 and " + MAX_TOPUP_USD));
        }
        try {
            PaymentProvider.CheckoutSession session = paymentProvider.createCheckout(
                accountId, amount,
                appBaseUrl + "/billing?topup=success",
                appBaseUrl + "/billing?topup=cancelled");
            return ResponseEntity.ok(Map.of("checkoutUrl", session.url()));
        } catch (PaymentException e) {
            log.warn("Checkout failed for account {}: {}", accountId, e.getMessage());
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    /** Current account's wallet balance. */
    @GetMapping("/wallet")
    public ResponseEntity<Map<String, Object>> wallet() {
        Long accountId = TenantContext.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", accountId);
        body.put("balanceUsd", accountId != null ? billingService.balance(accountId) : BigDecimal.ZERO);
        return ResponseEntity.ok(body);
    }

    /** Recent ledger entries for the current account (most recent first). */
    @GetMapping("/ledger")
    public ResponseEntity<List<Map<String, Object>>> ledger(
            @RequestParam(defaultValue = "50") int limit) {
        Long accountId = TenantContext.get();
        if (accountId == null) return ResponseEntity.ok(List.of());
        List<LedgerEntry> entries = ledgerRepository.findByAccountIdOrderByCreatedAtDesc(
            accountId, PageRequest.of(0, Math.min(Math.max(limit, 1), 200)));
        return ResponseEntity.ok(entries.stream().map(BillingController::toDto).toList());
    }

    /**
     * Platform-staff manual top-up. Phase 1 stand-in for the payment provider — lets the
     * operator credit a closed-beta customer's wallet directly.
     */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PostMapping("/admin/credit")
    public ResponseEntity<Map<String, Object>> adminCredit(@RequestBody Map<String, Object> body) {
        if (!(body.get("accountId") instanceof Number accountIdNum)
                || !(body.get("amountUsd") instanceof Number amountNum)) {
            return ResponseEntity.badRequest().body(Map.of("error", "accountId and amountUsd are required"));
        }
        long accountId = accountIdNum.longValue();
        BigDecimal amount = BigDecimal.valueOf(amountNum.doubleValue());
        if (amount.signum() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "amountUsd must be positive"));
        }
        try {
            LedgerEntry entry = billingService.credit(accountId, amount, LedgerEntryType.TOPUP,
                null, "Manual top-up by platform staff");
            log.info("Platform-admin credited ${} to account {}", amount, accountId);
            return ResponseEntity.ok(Map.of(
                "accountId", accountId,
                "balanceUsd", entry.getBalanceAfterUsd()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, Object> toDto(LedgerEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("type", e.getType().name());
        m.put("amountUsd", e.getAmountUsd());
        m.put("balanceAfterUsd", e.getBalanceAfterUsd());
        m.put("runId", e.getRunId() != null ? e.getRunId().toString() : null);
        m.put("description", e.getDescription());
        m.put("createdAt", e.getCreatedAt());
        return m;
    }
}
