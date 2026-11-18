package com.assessment.txn.validation;

import com.assessment.txn.model.TransactionRequest;
import java.util.List;

/**
 * Outcome of validating one raw record. Even for an invalid record we keep whichever identifiers
 * were individually well-formed, because the sequence number is needed to release the ordering
 * slot of a rejected record (otherwise later records would wait for it forever).
 */
public record ValidationResult(
        TransactionRequest request,
        List<String> errors,
        String sourceRef,
        String transactionId,
        String requestId,
        String accountId,
        Long sequence) {

    public boolean isValid() {
        return request != null;
    }
}
