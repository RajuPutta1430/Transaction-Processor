package com.assessment.txn.model;

public enum FailureReason {
    VALIDATION_FAILED(false),
    IDEMPOTENCY_CONFLICT(false),   // same transactionId, different payload
    SEQUENCE_CONFLICT(false),      // sequence slot already used or already resolved
    INSUFFICIENT_FUNDS(false),
    CURRENCY_MISMATCH(false),
    RETRIES_EXHAUSTED(true),       // the only reason that can be recovered by replaying
    UNEXPECTED_ERROR(false);

    private final boolean retryable;

    FailureReason(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
