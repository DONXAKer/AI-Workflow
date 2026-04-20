package com.workflow.security.audit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Auto-records login / login-failure into the audit log via Spring Security's event bus.
 * Logout is recorded directly by {@code AuthController} since we use a custom REST logout
 * that doesn't go through Spring's filter-chain logout handler.
 */
@Component
public class AuthEventAuditor {

    @Autowired
    private AuditService auditService;

    @EventListener
    public void onLoginSuccess(AuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        auditService.record("LOGIN", "user", auth.getName(),
            Map.of("authorities", auth.getAuthorities().toString()));
    }

    @EventListener
    public void onLoginFailure(AbstractAuthenticationFailureEvent event) {
        Authentication auth = event.getAuthentication();
        String username = auth != null ? String.valueOf(auth.getName()) : "unknown";
        auditService.recordFailure("LOGIN", "user", username,
            Map.of("reason", event.getException().getClass().getSimpleName()));
    }
}
