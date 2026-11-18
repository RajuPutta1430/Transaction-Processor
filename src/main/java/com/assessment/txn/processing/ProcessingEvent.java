package com.assessment.txn.processing;

import com.assessment.txn.model.TransactionStatus;

/** One entry in the processing audit trail. transactionId may be null for unparseable input. */
public record ProcessingEvent(String requestId, String transactionId, TransactionStatus status, String detail) {
}
