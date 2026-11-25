package com.mmts.identity.repository;

import com.mmts.identity.domain.OtpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;

/** Database access for one-time login codes. The id is the mobile number. */
public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, String> {
}
