package com.assessment.txn.processing;

import com.assessment.txn.model.FailureReason;
import com.assessment.txn.model.TransactionRequest;
import com.assessment.txn.model.TransactionStatus;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.assessment.txn.model.TransactionStatus.FAILED;
import static com.assessment.txn.model.TransactionStatus.PENDING;
import static com.assessment.txn.model.TransactionStatus.PROCESSED;
import static com.assessment.txn.model.TransactionStatus.PROCESSING;
import static com.assessment.txn.model.TransactionStatus.RECEIVED;
import static com.assessment.txn.model.TransactionStatus.RETRY_PENDING;

/**
 * Mutable state of one tracked transaction. Every status change goes through
 * {@link #transitionTo}, which enforces the lifecycle so an illegal jump (for example
 * PROCESSED back to PROCESSING, which could cause a double apply) fails loudly.
 *
 * Not thread-safe by itself; {@link TransactionProcessor} serialises access.
 */
final class TransactionRecord {

    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED = new EnumMap<>(TransactionStatus.class);

    static {
        ALLOWED.put(RECEIVED, EnumSet.of(PENDING, PROCESSING, FAILED));
        ALLOWED.put(PENDING, EnumSet.of(PROCESSING));
        ALLOWED.put(PROCESSING, EnumSet.of(PROCESSED, RETRY_PENDING, FAILED));
        ALLOWED.put(RETRY_PENDING, EnumSet.of(PROCESSING, FAILED));
        ALLOWED.put(FAILED, EnumSet.of(PROCESSING));   // only via explicit recovery of retryable failures
        ALLOWED.put(PROCESSED, EnumSet.noneOf(TransactionStatus.class));   // terminal
        ALLOWED.put(TransactionStatus.DUPLICATE, EnumSet.noneOf(TransactionStatus.class));
    }

    private final TransactionRequest request;
    private final List<TransactionStatus> history = new ArrayList<>();
    private TransactionStatus status = RECEIVED;
    private FailureReason failureReason;
    private int attempts;
    private int duplicateCount;

    TransactionRecord(TransactionRequest request) {
        this.request = request;
        history.add(RECEIVED);
    }

    void transitionTo(TransactionStatus next) {
        if (!ALLOWED.get(status).contains(next)) {
            throw new IllegalStateException("Illegal transition " + status + " -> " + next
                    + " for " + request.transactionId());
        }
        if (next == PROCESSING) {
            failureReason = null;
        }
        status = next;
        history.add(next);
    }

    void markFailed(FailureReason reason) {
        transitionTo(FAILED);
        failureReason = reason;
    }

    void incrementAttempts() {
        attempts++;
    }

    void incrementDuplicates() {
        duplicateCount++;
    }

    TransactionRequest request() {
        return request;
    }

    TransactionStatus status() {
        return status;
    }

    FailureReason failureReason() {
        return failureReason;
    }

    int attempts() {
        return attempts;
    }

    boolean isRetryableFailure() {
        return status == FAILED && failureReason != null && failureReason.isRetryable();
    }

    TransactionSnapshot snapshot() {
        return new TransactionSnapshot(request.transactionId(), status, attempts, duplicateCount,
                failureReason, List.copyOf(history));
    }
}
