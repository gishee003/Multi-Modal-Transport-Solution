package com.mmts.payment.repository;

import com.mmts.payment.domain.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    List<WalletTransaction> findTop50ByUserIdOrderByIdDesc(String userId);

    Optional<WalletTransaction> findByIdempotencyKey(String idempotencyKey);
}
