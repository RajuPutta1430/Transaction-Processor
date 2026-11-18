package com.assessment.txn.model;

/**
 * A record exactly as read from the input source: every field is an untrusted, nullable string.
 * Keeping this separate from {@link TransactionRequest} means malformed input can be isolated
 * by the validator instead of crashing the reader.
 */
public record RawTransaction(
        String sourceRef,
        String transactionId,
        String requestId,
        String sequenceNumber,
        String accountId,
        String transactionType,
        String amount,
        String currency) {
}
