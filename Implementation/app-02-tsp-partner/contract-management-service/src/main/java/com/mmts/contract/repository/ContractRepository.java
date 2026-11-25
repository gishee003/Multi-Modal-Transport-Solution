package com.mmts.contract.repository;

import com.mmts.contract.domain.Contract;
import com.mmts.contract.domain.ContractStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContractRepository extends JpaRepository<Contract, String> {

    List<Contract> findByTspIdOrderByRevisionDesc(String tspId);

    Optional<Contract> findFirstByTspIdAndStatusOrderByRevisionDesc(String tspId, ContractStatus status);
}
