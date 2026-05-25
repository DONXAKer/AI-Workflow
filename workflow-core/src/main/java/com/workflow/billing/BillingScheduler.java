package com.workflow.billing;

import com.workflow.account.Account;
import com.workflow.account.AccountRepository;
import com.workflow.account.AccountStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Grants each ACTIVE account its plan tier's monthly credit.
 *
 * <p>Idempotent: the credit carries {@code paymentRef = monthly-{accountId}-{YYYY-MM}} and
 * {@link BillingService#credit} dedupes on {@code paymentRef} — re-running the job within
 * the same month is a no-op. Tiers with {@code monthly-credit-usd = 0} (the default) are
 * skipped, so an unconfigured deployment grants nothing.
 */
@Component
public class BillingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingScheduler.class);

    private final AccountRepository accountRepository;
    private final BillingService billingService;
    private final TierProperties tiers;

    public BillingScheduler(AccountRepository accountRepository, BillingService billingService,
                            TierProperties tiers) {
        this.accountRepository = accountRepository;
        this.billingService = billingService;
        this.tiers = tiers;
    }

    /** 04:00 UTC on the 1st of each month. */
    @Scheduled(cron = "0 0 4 1 * *", zone = "UTC")
    public void grantMonthlyCredits() {
        String yearMonth = YearMonth.now(ZoneOffset.UTC).toString();   // e.g. "2026-05"
        int granted = 0;
        for (Account a : accountRepository.findAll()) {
            if (a.getStatus() != AccountStatus.ACTIVE) continue;
            double credit = tiers.forName(a.getTier()).getMonthlyCreditUsd();
            if (credit <= 0) continue;
            try {
                billingService.credit(a.getId(), BigDecimal.valueOf(credit),
                    LedgerEntryType.ADJUSTMENT,
                    "monthly-" + a.getId() + "-" + yearMonth,
                    "Monthly tier credit (" + a.getTier() + ")");
                granted++;
            } catch (Exception e) {
                log.warn("Monthly credit failed for account {}: {}", a.getId(), e.getMessage());
            }
        }
        if (granted > 0) {
            log.info("Granted monthly tier credit to {} account(s) for {}", granted, yearMonth);
        }
    }
}
