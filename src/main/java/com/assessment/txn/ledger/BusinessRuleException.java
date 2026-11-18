package com.assessment.txn.ledger;

import com.assessment.txn.model.FailureReason;

/** A permanent business rejection. Retrying cannot help, so it is never retried. */
public class BusinessRuleException extends RuntimeException {

    private final FailureReason reason;

    public BusinessRuleException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public FailureReason reason() {
        return reason;
    }
}
