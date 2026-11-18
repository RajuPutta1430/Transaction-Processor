package com.assessment.txn.processing;

import com.assessment.txn.model.FailureReason;
import com.assessment.txn.model.TransactionStatus;
import java.util.List;

/** Immutable view of a tracked transaction, safe to hand to callers and tests. */
public record TransactionSnapshot(
        String transactionId,
        TransactionStatus status,
        int attempts,
        int duplicateCount,
        FailureReason failureReason,
        List<TransactionStatus> history) {

    public boolean retryable() {
        return failureReason != null && failureReason.isRetryable();
    }
}
