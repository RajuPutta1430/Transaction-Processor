package com.assessment.txn.processing;

import com.assessment.txn.model.FailureReason;
import java.util.List;

/**
 * A record that never became a tracked transaction (malformed, or an idempotency conflict).
 * This is the in-memory stand-in for a dead-letter queue. Details hold field names only.
 */
public record RejectedRecord(String sourceRef, String transactionId, FailureReason reason, List<String> details) {
}
