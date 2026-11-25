package com.mmts.contract.repository;

import com.mmts.contract.domain.SlaMeasurement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SlaMeasurementRepository extends JpaRepository<SlaMeasurement, Long> {

    Optional<SlaMeasurement> findByContractIdAndPeriod(String contractId, String period);

    List<SlaMeasurement> findByContractIdOrderByMeasuredAtDesc(String contractId);
}
