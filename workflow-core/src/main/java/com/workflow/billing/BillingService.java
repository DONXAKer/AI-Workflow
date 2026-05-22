package com.workflow.billing;

import com.workflow.account.Account;
import com.workflow.account.AccountRepository;
import com.workflow.llm.LlmCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Wallet metering: turns raw LLM cost into wallet debits and payment top-ups into credits.
 *
 * <p>Atomicity — {@link #debitForLlmCall} updates the {@link Wallet} and inserts the
 * {@link LedgerEntry} in one transaction, so a balance change never commits without its
 * audit row. Idempotency — a {@code (llm_call_id, type)} unique constraint plus an
 * existence pre-check make a retried debit a no-op; top-ups dedupe on {@code paymentRef}.
 * Concurrency — the wallet is read with {@code SELECT ... FOR UPDATE} so parallel pipeline
 * blocks debiting the same wallet serialize.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private static final int SCALE = 4;

    private final WalletRepository walletRepo;
    private final LedgerEntryRepository ledgerRepo;
    private final BillingProperties props;
    private final AccountRepository accountRepo;
    private final TierProperties tiers;

    public BillingService(WalletRepository walletRepo, LedgerEntryRepository ledgerRepo,
                          BillingProperties props, AccountRepository accountRepo,
                          TierProperties tiers) {
        this.walletRepo = walletRepo;
        this.ledgerRepo = ledgerRepo;
        this.props = props;
        this.accountRepo = accountRepo;
        this.tiers = tiers;
    }

    /** Creates a zero-balance wallet for the account if one does not exist yet. */
    @Transactional
    public Wallet getOrCreateWallet(Long accountId) {
        return walletRepo.findByAccountId(accountId).orElseGet(() -> {
            try {
                return walletRepo.save(new Wallet(accountId));
            } catch (DataIntegrityViolationException race) {
                // Concurrent create lost the unique-constraint race — re-read the winner.
                return walletRepo.findByAccountId(accountId)
                    .orElseThrow(() -> race);
            }
        });
    }

    @Transactional(readOnly = true)
    public BigDecimal balance(Long accountId) {
        if (accountId == null) return BigDecimal.ZERO;
        return walletRepo.findByAccountId(accountId)
            .map(Wallet::getBalanceUsd)
            .orElse(BigDecimal.ZERO);
    }

    /** True when the account cannot afford to start or continue a run. */
    @Transactional(readOnly = true)
    public boolean isWalletDepleted(Long accountId) {
        if (accountId == null) return false; // contextless / legacy — unbilled
        return balance(accountId).doubleValue() <= props.getMinRunReserveUsd();
    }

    /**
     * Raw-LLM budget the account can still afford — wallet balance divided by markup.
     * Used to clamp a tool-use loop's {@code budgetUsdCap} (which is denominated in raw
     * LLM cost). Returns {@link Double#MAX_VALUE} for unbilled (contextless) callers.
     */
    @Transactional(readOnly = true)
    public double remainingRawBudgetUsd(Long accountId) {
        if (accountId == null) return Double.MAX_VALUE;
        return Math.max(0.0, balance(accountId).doubleValue() / markupForAccount(accountId));
    }

    /** Charge for a raw LLM cost at the global markup: {@code cost × markup}, rounded. */
    public BigDecimal computeCharge(double rawCostUsd) {
        return BigDecimal.valueOf(rawCostUsd)
            .multiply(BigDecimal.valueOf(props.getMarkup()))
            .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Effective markup for an account: its plan tier's markup, or the global
     * {@code workflow.billing.markup} when the tier is unconfigured / has no override.
     */
    public double markupForAccount(Long accountId) {
        if (accountId == null) return props.getMarkup();
        String tier = accountRepo.findById(accountId).map(Account::getTier).orElse(null);
        double tierMarkup = tiers.forName(tier).getMarkup();
        return tierMarkup > 0 ? tierMarkup : props.getMarkup();
    }

    /** Charge for a raw LLM cost using the account's tier markup, rounded. */
    public BigDecimal computeCharge(double rawCostUsd, Long accountId) {
        return BigDecimal.valueOf(rawCostUsd)
            .multiply(BigDecimal.valueOf(markupForAccount(accountId)))
            .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Debits the wallet for one LLM call. Idempotent — calling it twice for the same
     * {@code LlmCall} debits once. {@code REQUIRES_NEW} so the charge commits independently
     * of any caller transaction: the LLM cost was really incurred regardless.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void debitForLlmCall(LlmCall call) {
        Long accountId = call.getAccountId();
        if (accountId == null) return;                 // unbilled (legacy / contextless)
        double cost = call.getCostUsd();
        if (cost <= 0) return;
        Long callId = call.getId();
        if (callId != null && ledgerRepo.existsByLlmCallIdAndType(callId, LedgerEntryType.DEBIT)) {
            return;                                    // already debited
        }
        BigDecimal charge = computeCharge(cost, accountId);
        if (charge.signum() <= 0) return;

        Wallet wallet = walletRepo.findByAccountIdForUpdate(accountId)
            .orElseGet(() -> walletRepo.save(new Wallet(accountId)));
        BigDecimal newBalance = wallet.getBalanceUsd().subtract(charge);
        wallet.setBalanceUsd(newBalance);
        walletRepo.save(wallet);

        LedgerEntry entry = new LedgerEntry();
        entry.setAccountId(accountId);
        entry.setType(LedgerEntryType.DEBIT);
        entry.setAmountUsd(charge.negate());
        entry.setBalanceAfterUsd(newBalance);
        entry.setRunId(call.getRunId());
        entry.setLlmCallId(callId);
        entry.setDescription("LLM usage: " + (call.getModel() != null ? call.getModel() : "unknown"));
        ledgerRepo.save(entry);
    }

    /**
     * Credits the wallet (top-up / refund / adjustment). Idempotent on {@code paymentRef}
     * when one is supplied — a replayed payment webhook returns the original entry.
     */
    @Transactional
    public LedgerEntry credit(Long accountId, BigDecimal amountUsd, LedgerEntryType type,
                              String paymentRef, String description) {
        if (accountId == null) throw new IllegalArgumentException("accountId is required");
        if (amountUsd == null || amountUsd.signum() <= 0) {
            throw new IllegalArgumentException("credit amount must be positive");
        }
        if (paymentRef != null) {
            Optional<LedgerEntry> existing = ledgerRepo.findByPaymentRef(paymentRef);
            if (existing.isPresent()) {
                log.info("Skipping duplicate credit for paymentRef={} (account {})", paymentRef, accountId);
                return existing.get();
            }
        }
        BigDecimal amount = amountUsd.setScale(SCALE, RoundingMode.HALF_UP);
        Wallet wallet = walletRepo.findByAccountIdForUpdate(accountId)
            .orElseGet(() -> walletRepo.save(new Wallet(accountId)));
        BigDecimal newBalance = wallet.getBalanceUsd().add(amount);
        wallet.setBalanceUsd(newBalance);
        walletRepo.save(wallet);

        LedgerEntry entry = new LedgerEntry();
        entry.setAccountId(accountId);
        entry.setType(type != null ? type : LedgerEntryType.TOPUP);
        entry.setAmountUsd(amount);
        entry.setBalanceAfterUsd(newBalance);
        entry.setPaymentRef(paymentRef);
        entry.setDescription(description);
        return ledgerRepo.save(entry);
    }
}
