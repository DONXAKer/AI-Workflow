package com.workflow.api;

import com.workflow.account.AccountRepository;
import com.workflow.account.AccountStatus;
import com.workflow.billing.BillingService;
import com.workflow.core.KillSwitch;
import com.workflow.core.KillSwitchService;
import com.workflow.security.audit.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    @Autowired
    private KillSwitchService killSwitchService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BillingService billingService;

    @GetMapping("/kill-switch")
    public ResponseEntity<Map<String, Object>> getState() {
        return ResponseEntity.ok(toDto(killSwitchService.current()));
    }

    @PostMapping("/kill-switch")
    public ResponseEntity<Map<String, Object>> toggle(@RequestBody Map<String, Object> request) {
        boolean active = Boolean.TRUE.equals(request.get("active"));
        String reason = (String) request.getOrDefault("reason", "");
        boolean cancelActive = Boolean.TRUE.equals(request.get("cancelActive"));
        String actor = currentActor();

        KillSwitch ks;
        if (active) {
            ks = killSwitchService.activate(reason, actor, cancelActive);
            auditService.record("KILL_SWITCH_ACTIVATE", "system", "kill-switch", Map.of(
                "reason", reason,
                "cancelActive", cancelActive));
        } else {
            ks = killSwitchService.deactivate(actor);
            auditService.record("KILL_SWITCH_DEACTIVATE", "system", "kill-switch", Map.of());
        }
        return ResponseEntity.ok(toDto(ks));
    }

    // ── Platform-staff account console ────────────────────────────────────────────
    // Method-level @PreAuthorize overrides the class-level hasRole('ADMIN'): account
    // management is platform-staff only — a tenant admin must not see other tenants.

    /** All customer accounts with their current wallet balance. */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/accounts")
    public List<Map<String, Object>> listAccounts() {
        return accountRepository.findAll().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("slug", a.getSlug());
            m.put("name", a.getName());
            m.put("status", a.getStatus().name());
            m.put("tier", a.getTier());
            m.put("balanceUsd", billingService.balance(a.getId()));
            m.put("onboardedAt", a.getOnboardedAt());
            m.put("createdAt", a.getCreatedAt());
            return m;
        }).toList();
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PostMapping("/accounts/{id}/suspend")
    public ResponseEntity<?> suspendAccount(@PathVariable Long id) {
        return setAccountStatus(id, AccountStatus.SUSPENDED);
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PostMapping("/accounts/{id}/activate")
    public ResponseEntity<?> activateAccount(@PathVariable Long id) {
        return setAccountStatus(id, AccountStatus.ACTIVE);
    }

    private ResponseEntity<?> setAccountStatus(Long id, AccountStatus status) {
        return accountRepository.findById(id)
            .<ResponseEntity<?>>map(account -> {
                account.setStatus(status);
                accountRepository.save(account);
                auditService.record("ACCOUNT_" + status.name(), "account",
                    String.valueOf(id), Map.of("by", currentActor()));
                return ResponseEntity.ok(Map.of("id", id, "status", status.name()));
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> toDto(KillSwitch ks) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("active", ks.isActive());
        dto.put("reason", ks.getReason());
        dto.put("activatedBy", ks.getActivatedBy());
        dto.put("activatedAt", ks.getActivatedAt());
        return dto;
    }

    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
