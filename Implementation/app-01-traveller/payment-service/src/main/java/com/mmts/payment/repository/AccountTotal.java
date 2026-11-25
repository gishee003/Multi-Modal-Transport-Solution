package com.mmts.payment.repository;

/** Total debits and credits of one ledger account, as added up by the database. */
public record AccountTotal(String account, Long debitsPaise, Long creditsPaise) {

    /** Credits minus debits: for wallet and payable accounts, the money the platform still holds or owes. */
    public long creditBalancePaise() {
        return creditsPaise - debitsPaise;
    }
}
