package com.workflow.account;

import com.workflow.billing.BillingService;
import com.workflow.security.User;
import com.workflow.security.UserRepository;
import com.workflow.security.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Self-serve sign-up. Atomically provisions a new customer: an {@link Account}, its owner
 * {@link User} (role {@link UserRole#ADMIN}) and a zero-balance
 * {@link com.workflow.billing.Wallet}. The user logs in with their email address.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SLUG_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final BillingService billingService;
    private final EmailService emailService;

    public RegistrationService(AccountRepository accountRepository, UserRepository userRepository,
                               PasswordEncoder passwordEncoder, BillingService billingService,
                               EmailService emailService) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.billingService = billingService;
        this.emailService = emailService;
    }

    /**
     * Provisions a new account. Throws {@link IllegalArgumentException} on invalid input or
     * a duplicate email — the caller maps that to HTTP 400.
     *
     * @return the freshly created owner user (already persisted)
     */
    @Transactional
    public User register(String email, String rawPassword, String displayName) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase();
        if (normalizedEmail.isBlank() || !normalizedEmail.contains("@")) {
            throw new IllegalArgumentException("A valid email address is required");
        }
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        if (userRepository.findByUsername(normalizedEmail).isPresent()) {
            throw new IllegalArgumentException("An account with this email already exists");
        }

        String name = (displayName != null && !displayName.isBlank())
            ? displayName.trim() : normalizedEmail;

        Account account = new Account();
        account.setSlug(uniqueSlug());
        account.setName(name);
        account.setStatus(AccountStatus.ACTIVE);
        account = accountRepository.save(account);

        String verificationToken = randomToken();
        User user = new User();
        user.setUsername(normalizedEmail);
        user.setEmail(normalizedEmail);
        user.setDisplayName(name);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(UserRole.ADMIN);          // account owner
        user.setEnabled(true);
        user.setEmailVerified(false);          // confirmed via the verification link
        user.setVerificationToken(verificationToken);
        user.setAccountId(account.getId());
        user = userRepository.save(user);

        billingService.getOrCreateWallet(account.getId());
        emailService.sendVerificationEmail(user, verificationToken);

        log.info("Registered account {} (slug={}) with owner {}",
            account.getId(), account.getSlug(), normalizedEmail);
        return user;
    }

    /** URL-safe random token (~256 bits) for single-use email verification. */
    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String uniqueSlug() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String slug = "acc-" + randomSuffix();
            if (!accountRepository.existsBySlug(slug)) return slug;
        }
        throw new IllegalStateException("Could not generate a unique account slug");
    }

    private String randomSuffix() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(SLUG_CHARS.charAt(RANDOM.nextInt(SLUG_CHARS.length())));
        }
        return sb.toString();
    }
}
