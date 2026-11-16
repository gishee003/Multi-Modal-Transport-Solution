package com.mmts.payment.repository;

import com.mmts.payment.domain.Payout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayoutRepository extends JpaRepository<Payout, Long> {

    List<Payout> findTop100ByOrderByIdDesc();
}
