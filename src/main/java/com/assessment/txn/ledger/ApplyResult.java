package com.assessment.txn.ledger;

public enum ApplyResult {
    APPLIED,
    /** The ledger already holds an entry for this transaction; nothing was changed. */
    ALREADY_APPLIED
}
