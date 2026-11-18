package com.assessment.txn.model;

/**
 * Lifecycle of a transaction, plus DUPLICATE which describes the outcome of a replayed request
 * (a duplicate request never becomes a state of the original transaction).
 */
public enum TransactionStatus {
    RECEIVED,
    PENDING,        // buffered: waiting for an earlier sequence number
    PROCESSING,
    RETRY_PENDING,  // transient failure, waiting for the next attempt
    PROCESSED,
    DUPLICATE,
    FAILED
}
