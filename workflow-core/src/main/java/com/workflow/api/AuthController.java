package com.workflow.api;

import com.workflow.security.User;
import com.workflow.security.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.workflow.security.audit.AuditService auditService;

    @Autowired
    private RememberMeServices rememberMeServices;

    @Autowired
    private com.workflow.account.RegistrationService registrationService;

    @Autowired
    private com.workflow.account.AccountRepository accountRepository;

    @Autowired
    private com.workflow.account.RegistrationRateLimiter registrationRateLimiter;

    @Value("${workflow.app-base-url:http://localhost:5173}")
    private String appBaseUrl;

    /**
     * Session repo must be populated explicitly when authentication is set outside the
     * filter chain (e.g. inside a controller), otherwise the user appears logged in for
     * one request and the cookie is never persisted.
     */
    private final SecurityContextRepository securityContextRepository =
        new HttpSessionSecurityContextRepository();

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        Object usernameRaw = body.get("username");
        Object passwordRaw = body.get("password");
        String username = usernameRaw instanceof String s ? s : null;
        String password = passwordRaw instanceof String s ? s : null;
        boolean rememberMe = Boolean.TRUE.equals(body.get("rememberMe"));
        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "username and password are required"));
        }
        try {
            Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            if (rememberMe) {
                // Sets the signed "remember-me" cookie with the configured TTL.
                rememberMeServices.loginSuccess(request, response, auth);
            }

            return ResponseEntity.ok(currentUserPayload(username));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        } catch (DisabledException e) {
            return ResponseEntity.status(403).body(Map.of(
                "error", "Account is disabled or email is not verified"));
        } catch (Exception e) {
            log.error("Login failure for {}: {}", username, e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", "Authentication failed"));
        }
    }

    /**
     * Confirms an email address from the link in the verification message. Public + GET
     * (clicked from the user's inbox) — permit-all in {@code SecurityConfig}. Redirects
     * back to the SPA login with a {@code verified} flag.
     */
    @GetMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@RequestParam(value = "token", required = false) String token) {
        boolean ok = false;
        if (token != null && !token.isBlank()) {
            var userOpt = userRepository.findByVerificationToken(token);
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                user.setEmailVerified(true);
                user.setVerificationToken(null);
                userRepository.save(user);
                auditService.record("EMAIL_VERIFIED", "user", user.getUsername(), Map.of());
                ok = true;
            }
        }
        URI redirect = URI.create(appBaseUrl + "/login?verified=" + (ok ? "1" : "0"));
        return ResponseEntity.status(302).location(redirect).build();
    }

    /**
     * Self-serve sign-up: provisions a new account + owner user + wallet, then logs the
     * user straight in (same session-cookie flow as {@link #login}). CSRF-exempt and
     * permit-all in {@code SecurityConfig}, alongside {@code /api/auth/login}.
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body,
                                                         HttpServletRequest request,
                                                         HttpServletResponse response) {
        if (!registrationRateLimiter.tryRegister(clientIp(request))) {
            return ResponseEntity.status(429).body(Map.of(
                "error", "Too many registration attempts — please try again later"));
        }
        String email = body.get("email") instanceof String s ? s : null;
        String password = body.get("password") instanceof String s ? s : null;
        String displayName = body.get("displayName") instanceof String s ? s : null;
        if (email == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "email and password are required"));
        }
        try {
            registrationService.register(email, password, displayName);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Registration failed for {}: {}", email, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Registration failed"));
        }

        String username = email.trim().toLowerCase();
        try {
            Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            auditService.record("REGISTER", "user", username, Map.of());
            return ResponseEntity.ok(currentUserPayload(username));
        } catch (Exception e) {
            // Account is created — surface success and let the user log in manually.
            log.warn("Auto-login after registration failed for {}: {}", username, e.getMessage());
            return ResponseEntity.ok(Map.of("registered", true));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = auth != null ? auth.getName() : null;
        // Record BEFORE clearing the context so AuditService can pick up the real actor.
        if (actor != null && !"anonymousUser".equals(actor)) {
            auditService.record("LOGOUT", "user", actor, Map.of());
        }
        // Clear the remember-me cookie so the next request isn't silently re-authenticated.
        // RememberMeServices interface has no logout(); the standard impl
        // (AbstractRememberMeServices) implements LogoutHandler — cast to invoke.
        if (rememberMeServices instanceof LogoutHandler lh) {
            lh.logout(request, response, auth);
        }
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        return ResponseEntity.ok(currentUserPayload(authentication.getName()));
    }

    /** Best-effort client IP — first X-Forwarded-For hop when behind a proxy. */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private Map<String, Object> currentUserPayload(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalStateException("Authenticated user not found in DB: " + username));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", user.getId());
        payload.put("username", user.getUsername());
        payload.put("displayName", user.getDisplayName());
        payload.put("email", user.getEmail());
        payload.put("role", user.getRole().name());
        payload.put("accountId", user.getAccountId());
        payload.put("emailVerified", user.isEmailVerified());
        // Drives the post-login onboarding-wizard redirect.
        boolean onboarded = user.getAccountId() != null
            && accountRepository.findById(user.getAccountId())
                .map(a -> a.getOnboardedAt() != null)
                .orElse(false);
        payload.put("accountOnboarded", onboarded);
        return payload;
    }
}
