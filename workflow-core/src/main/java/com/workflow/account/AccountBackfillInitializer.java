package com.workflow.account;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time multi-tenancy migration. Runs as an {@link ApplicationRunner} — i.e. after the
 * context is fully started, so every {@code @PostConstruct} initializer
 * ({@code BootstrapAdminInitializer}, {@code DefaultProjectInitializer}) has already
 * created its rows.
 *
 * <p>{@code ddl-auto: update} adds the nullable {@code account_id} column to each
 * tenant-owned table but never backfills data. This class creates the
 * {@link Account#LEGACY_SLUG} account and bulk-assigns every row with a null
 * {@code account_id} to it. Idempotent: on later boots it finds no orphan rows.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccountBackfillInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountBackfillInitializer.class);

    /** Entity names (JPQL) of every tenant-owned table carrying an {@code accountId} field. */
    private static final String[] TENANT_ENTITIES = {
        "User", "Project", "PipelineRun", "IntegrationConfig", "LlmCall", "McpServer", "AuditLog"
    };

    private final AccountRepository accountRepository;

    @PersistenceContext
    private EntityManager em;

    public AccountBackfillInitializer(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Account legacy = accountRepository.findBySlug(Account.LEGACY_SLUG).orElseGet(() -> {
            Account a = new Account();
            a.setSlug(Account.LEGACY_SLUG);
            a.setName("Legacy");
            a.setStatus(AccountStatus.ACTIVE);
            Account saved = accountRepository.save(a);
            log.info("Created legacy account (id={}) for pre-multi-tenancy data", saved.getId());
            return saved;
        });
        Long legacyId = legacy.getId();

        // The legacy account predates the onboarding wizard — mark it onboarded so
        // existing admins are not redirected into the wizard after login.
        if (legacy.getOnboardedAt() == null) {
            legacy.setOnboardedAt(java.time.Instant.now());
            accountRepository.save(legacy);
        }

        int total = 0;
        for (String entity : TENANT_ENTITIES) {
            total += em.createQuery(
                    "update " + entity + " e set e.accountId = :a where e.accountId is null")
                .setParameter("a", legacyId)
                .executeUpdate();
        }

        if (total > 0) {
            log.info("Multi-tenancy backfill: assigned {} orphan rows to legacy account {}", total, legacyId);
        } else {
            log.debug("Multi-tenancy backfill: no orphan rows");
        }
    }
}
