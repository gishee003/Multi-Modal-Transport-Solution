package com.mmts.identity.repository;

import com.mmts.identity.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Database access for accounts (DAO pattern). Spring Data writes the SQL from the method names. */
public interface UserAccountRepository extends JpaRepository<UserAccount, String> {

    Optional<UserAccount> findByMobile(String mobile);

    boolean existsByMobile(String mobile);

    boolean existsByEmail(String email);
}
