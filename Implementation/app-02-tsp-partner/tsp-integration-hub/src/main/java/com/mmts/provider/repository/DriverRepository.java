package com.mmts.provider.repository;

import com.mmts.provider.domain.Driver;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Stores drivers (TSP staff). */
public interface DriverRepository extends JpaRepository<Driver, String> {

    List<Driver> findByProviderId(String providerId);
}
