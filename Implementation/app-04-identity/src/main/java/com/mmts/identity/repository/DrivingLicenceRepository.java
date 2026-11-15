package com.mmts.identity.repository;

import com.mmts.identity.domain.DrivingLicence;
import com.mmts.identity.domain.LicenceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Database access for driving licences. */
public interface DrivingLicenceRepository extends JpaRepository<DrivingLicence, Long> {

    Optional<DrivingLicence> findByUserId(String userId);

    /** Used by the expiry job: e.g. all VERIFIED licences whose expiry date is before today. */
    List<DrivingLicence> findByStatusAndExpiresOnBefore(LicenceStatus status, LocalDate date);

    void deleteByUserId(String userId);
}
