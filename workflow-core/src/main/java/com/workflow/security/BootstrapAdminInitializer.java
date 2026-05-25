package com.workflow.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Ensures a platform administrator exists on startup. The bootstrap user is created as
 * {@link UserRole#PLATFORM_ADMIN} — platform staff that sees across all customer accounts.
 * Password comes from the {@code workflow.bootstrap-admin-password} property (env
 * {@code WORKFLOW_BOOTSTRAP_ADMIN_PASSWORD}); when unset, a random one is generated and
 * logged once — operator must rotate it immediately.
 *
 * <p>Runs only when the users table has no admin-tier user (ADMIN or PLATFORM_ADMIN). On
 * subsequent starts it is a no-op — pre-existing single-tenant {@code ADMIN} users are
 * left untouched (they become legacy-account admins via {@code AccountBackfillInitializer}).
 */
@Component
public class BootstrapAdminInitializer {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${workflow.bootstrap-admin-username:admin}")
    private String adminUsername;

    @Value("${workflow.bootstrap-admin-password:}")
    private String configuredPassword;

    @PostConstruct
    public void initialize() {
        if (userRepository.existsByRole(UserRole.ADMIN)
                || userRepository.existsByRole(UserRole.PLATFORM_ADMIN)) {
            log.debug("Admin already exists — skipping bootstrap");
            return;
        }

        String password = configuredPassword != null && !configuredPassword.isBlank()
            ? configuredPassword : generateRandomPassword();

        User admin = new User();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setDisplayName("Bootstrap Admin");
        admin.setRole(UserRole.PLATFORM_ADMIN);
        admin.setEnabled(true);
        admin.setEmailVerified(true);
        userRepository.save(admin);

        if (configuredPassword == null || configuredPassword.isBlank()) {
            log.warn("=============================================================");
            log.warn("Bootstrap admin created: username={}, password={}", adminUsername, password);
            log.warn("Rotate this password immediately via /api/auth or admin UI.");
            log.warn("=============================================================");
        } else {
            log.info("Bootstrap admin '{}' created from configured password", adminUsername);
        }
    }

    private String generateRandomPassword() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
