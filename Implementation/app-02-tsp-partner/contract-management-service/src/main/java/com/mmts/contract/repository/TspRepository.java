package com.mmts.contract.repository;

import com.mmts.contract.domain.Tsp;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TspRepository extends JpaRepository<Tsp, String> {
}
