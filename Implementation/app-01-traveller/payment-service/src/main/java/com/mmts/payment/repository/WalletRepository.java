package com.mmts.payment.repository;

import com.mmts.payment.domain.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WalletRepository extends JpaRepository<Wallet, String> {

    /** Sum of all wallet balances; must equal what the ledger says the platform holds for travellers. */
    @Query("select coalesce(sum(w.balancePaise), 0) from Wallet w")
    long totalBalancePaise();
}
