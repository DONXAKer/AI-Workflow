package com.workflow.billing;

import com.workflow.llm.LlmCall;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the wallet-metering core: markup arithmetic, idempotent debiting and the
 * depletion boundary. Repositories are mocked — no Spring context, no DB.
 */
class BillingServiceTest {

    private final WalletRepository walletRepo = mock(WalletRepository.class);
    private final LedgerEntryRepository ledgerRepo = mock(LedgerEntryRepository.class);
    private final BillingProperties props = new BillingProperties();
    // No tier configured -> markupForAccount falls back to the global props markup,
    // so these tests keep asserting against props.setMarkup(...).
    private final com.workflow.account.AccountRepository accountRepo =
        mock(com.workflow.account.AccountRepository.class);
    private final TierProperties tiers = new TierProperties();

    private BillingService service() {
        return new BillingService(walletRepo, ledgerRepo, props, accountRepo, tiers);
    }

    @Test
    void computeCharge_appliesMarkupAndRoundsTo4dp() {
        props.setMarkup(1.7);
        assertThat(service().computeCharge(1.0)).isEqualByComparingTo("1.7000");
        assertThat(service().computeCharge(0.10)).isEqualByComparingTo("0.1700");
    }

    @Test
    void debitForLlmCall_isIdempotent_whenAlreadyDebited() {
        LlmCall call = llmCall(42L, 100L, 1.0);
        when(ledgerRepo.existsByLlmCallIdAndType(42L, LedgerEntryType.DEBIT)).thenReturn(true);

        service().debitForLlmCall(call);

        verify(walletRepo, never()).findByAccountIdForUpdate(anyLong());
        verify(ledgerRepo, never()).save(any());
    }

    @Test
    void debitForLlmCall_debitsWalletAndWritesLedgerRow() {
        props.setMarkup(2.0);
        LlmCall call = llmCall(42L, 100L, 0.50);
        Wallet wallet = new Wallet(100L);
        wallet.setBalanceUsd(new BigDecimal("5.0000"));
        when(ledgerRepo.existsByLlmCallIdAndType(42L, LedgerEntryType.DEBIT)).thenReturn(false);
        when(walletRepo.findByAccountIdForUpdate(100L)).thenReturn(Optional.of(wallet));

        service().debitForLlmCall(call);

        // 0.50 raw cost x 2.0 markup = 1.00 charge -> 5.00 - 1.00 = 4.00
        assertThat(wallet.getBalanceUsd()).isEqualByComparingTo("4.0000");
        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerRepo).save(captor.capture());
        LedgerEntry entry = captor.getValue();
        assertThat(entry.getType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(entry.getAmountUsd()).isEqualByComparingTo("-1.0000");
        assertThat(entry.getBalanceAfterUsd()).isEqualByComparingTo("4.0000");
        assertThat(entry.getLlmCallId()).isEqualTo(42L);
        assertThat(entry.getAccountId()).isEqualTo(100L);
    }

    @Test
    void debitForLlmCall_skipsWhenNoAccount() {
        LlmCall call = llmCall(42L, null, 1.0);
        service().debitForLlmCall(call);
        verifyNoInteractions(walletRepo, ledgerRepo);
    }

    @Test
    void debitForLlmCall_skipsWhenZeroCost() {
        LlmCall call = llmCall(42L, 100L, 0.0);
        service().debitForLlmCall(call);
        verifyNoInteractions(walletRepo);
        verify(ledgerRepo, never()).save(any());
    }

    @Test
    void isWalletDepleted_atOrBelowReserveIsDepleted() {
        props.setMinRunReserveUsd(0.10);
        Wallet wallet = new Wallet(100L);
        when(walletRepo.findByAccountId(100L)).thenReturn(Optional.of(wallet));

        wallet.setBalanceUsd(new BigDecimal("0.10"));
        assertThat(service().isWalletDepleted(100L)).isTrue();

        wallet.setBalanceUsd(new BigDecimal("0.11"));
        assertThat(service().isWalletDepleted(100L)).isFalse();
    }

    @Test
    void isWalletDepleted_falseForUnbilledNullAccount() {
        assertThat(service().isWalletDepleted(null)).isFalse();
    }

    @Test
    void remainingRawBudget_isBalanceDividedByMarkup() {
        props.setMarkup(2.0);
        Wallet wallet = new Wallet(100L);
        wallet.setBalanceUsd(new BigDecimal("10.00"));
        when(walletRepo.findByAccountId(100L)).thenReturn(Optional.of(wallet));
        assertThat(service().remainingRawBudgetUsd(100L)).isEqualTo(5.0);
    }

    @Test
    void credit_isIdempotentOnPaymentRef() {
        LedgerEntry existing = new LedgerEntry();
        existing.setPaymentRef("pay-1");
        when(ledgerRepo.findByPaymentRef("pay-1")).thenReturn(Optional.of(existing));

        LedgerEntry result = service().credit(100L, new BigDecimal("5.00"),
            LedgerEntryType.TOPUP, "pay-1", "replayed webhook");

        assertThat(result).isSameAs(existing);
        verify(walletRepo, never()).findByAccountIdForUpdate(anyLong());
    }

    private static LlmCall llmCall(Long id, Long accountId, double cost) {
        LlmCall call = mock(LlmCall.class);
        when(call.getId()).thenReturn(id);
        when(call.getAccountId()).thenReturn(accountId);
        when(call.getCostUsd()).thenReturn(cost);
        when(call.getModel()).thenReturn("z-ai/glm-4.6");
        when(call.getRunId()).thenReturn(UUID.randomUUID());
        return call;
    }
}
