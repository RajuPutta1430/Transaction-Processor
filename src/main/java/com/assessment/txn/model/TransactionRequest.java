package com.assessment.txn.model;

import com.assessment.txn.util.Masking;
import java.math.BigDecimal;

/** A validated, typed transaction request. */
public record TransactionRequest(
        String sourceRef,
        String transactionId,
        String requestId,
        long sequenceNumber,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency) {

    /**
     * True when two requests describe the same business transaction. The requestId is ignored on
     * purpose: a retrying client legitimately sends a new requestId for the same transactionId.
     */
    public boolean samePayloadAs(TransactionRequest other) {
        return transactionId.equals(other.transactionId)
                && accountId.equals(other.accountId)
                && type == other.type
                && amount.compareTo(other.amount) == 0
                && currency.equals(other.currency)
                && sequenceNumber == other.sequenceNumber;
    }

    /** Masked on purpose: records must not leak account details if someone logs them. */
    @Override
    public String toString() {
        return "TransactionRequest[transactionId=" + transactionId
                + ", requestId=" + requestId
                + ", seq=" + sequenceNumber
                + ", account=" + Masking.maskAccount(accountId)
                + ", type=" + type + "]";
    }
}
