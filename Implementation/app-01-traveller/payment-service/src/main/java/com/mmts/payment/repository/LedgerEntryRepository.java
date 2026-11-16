package com.mmts.payment.repository;

import com.mmts.payment.domain.LedgerEntry;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * The ledger is append-only, so this repository deliberately extends the bare {@link Repository}
 * and offers only "save" and read methods. There is no delete or update method to call.
 */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    <S extends LedgerEntry> List<S> saveAll(Iterable<S> entries);

    List<LedgerEntry> findByReferenceOrderByIdAsc(String reference);

    List<LedgerEntry> findTop100ByOrderByIdDesc();

    long count();

    /** Total debits and credits per account, added up by the database. */
    @Query("select new com.mmts.payment.repository.AccountTotal(e.account, "
            + "sum(case when e.direction = com.mmts.payment.domain.EntryDirection.DEBIT then e.amountPaise else 0L end), "
            + "sum(case when e.direction = com.mmts.payment.domain.EntryDirection.CREDIT then e.amountPaise else 0L end)) "
            + "from LedgerEntry e group by e.account order by e.account")
    List<AccountTotal> totalsByAccount();
}
