package com.workflow.api;

import com.workflow.account.AccountRepository;
import com.workflow.account.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Current-account endpoints. The account is always the caller's own — resolved from
 * {@link TenantContext}, never from a path parameter — so there is no cross-account risk.
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountRepository accountRepository;

    public AccountController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /** Summary of the caller's account, incl. {@code onboardedAt} (null until onboarded). */
    @GetMapping
    public ResponseEntity<?> current() {
        Long accountId = TenantContext.get();
        if (accountId == null) {
            return ResponseEntity.status(404).body(Map.of("error", "No account scope"));
        }
        return accountRepository.findById(accountId)
            .<ResponseEntity<?>>map(a -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", a.getId());
                m.put("slug", a.getSlug());
                m.put("name", a.getName());
                m.put("status", a.getStatus().name());
                m.put("tier", a.getTier());
                m.put("onboardedAt", a.getOnboardedAt());
                return ResponseEntity.ok(m);
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Marks onboarding complete — called by the OnboardingWizard on finish. Idempotent. */
    @PostMapping("/onboarded")
    public ResponseEntity<?> markOnboarded() {
        Long accountId = TenantContext.get();
        if (accountId == null) {
            return ResponseEntity.status(404).body(Map.of("error", "No account scope"));
        }
        return accountRepository.findById(accountId)
            .<ResponseEntity<?>>map(a -> {
                if (a.getOnboardedAt() == null) {
                    a.setOnboardedAt(Instant.now());
                    accountRepository.save(a);
                }
                return ResponseEntity.ok(Map.of("onboardedAt", a.getOnboardedAt()));
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
