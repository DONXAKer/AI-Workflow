package com.workflow.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByAccountId(Long accountId);

    /**
     * Pessimistic {@code SELECT ... FOR UPDATE} — serializes concurrent debits when
     * parallel pipeline blocks (virtual-thread fan-out) charge the same wallet at once.
     * Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.accountId = :accountId")
    Optional<Wallet> findByAccountIdForUpdate(@Param("accountId") Long accountId);
}
