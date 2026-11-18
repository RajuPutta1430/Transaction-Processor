package com.assessment.txn.report;

import com.assessment.txn.model.TransactionStatus;
import com.assessment.txn.ordering.SequenceGate;
import com.assessment.txn.processing.ProcessingEvent;
import com.assessment.txn.processing.RejectedRecord;
import com.assessment.txn.processing.TransactionSnapshot;
import com.assessment.txn.util.Masking;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Point-in-time operational summary of a processing run. */
public record ProcessingReport(
        int inputsReceived,
        int uniqueTransactions,
        int retryAttempts,
        Map<TransactionStatus, Integer> statusCounts,
        List<TransactionSnapshot> transactions,
        List<RejectedRecord> rejected,
        List<SequenceGate.Gap> sequenceGaps,
        List<ProcessingEvent> events,
        Map<String, BigDecimal> balances) {

    public int count(TransactionStatus status) {
        return statusCounts.getOrDefault(status, 0);
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Transaction Processing Report ===\n");
        sb.append(String.format("Inputs received      : %d%n", inputsReceived));
        sb.append(String.format("Unique transactions  : %d%n", uniqueTransactions));
        sb.append(String.format("Retry attempts       : %d%n", retryAttempts));

        sb.append("\n-- Summary by status --\n");
        statusCounts.forEach((status, n) -> sb.append(String.format("%-14s: %d%n", status, n)));

        sb.append("\n-- Transactions --\n");
        for (TransactionSnapshot t : transactions) {
            sb.append(String.format("%-10s %-10s attempts=%d duplicates=%d%s%n",
                    t.transactionId(), t.status(), t.attempts(), t.duplicateCount(),
                    t.failureReason() == null ? ""
                            : " reason=" + t.failureReason() + (t.retryable() ? " (recoverable)" : "")));
        }

        sb.append("\n-- Rejected at ingestion (dead-letter) --\n");
        if (rejected.isEmpty()) {
            sb.append("none\n");
        }
        for (RejectedRecord r : rejected) {
            sb.append(String.format("%s txn=%s reason=%s %s%n", r.sourceRef(),
                    r.transactionId() == null ? "n/a" : r.transactionId(), r.reason(), r.details()));
        }

        sb.append("\n-- Unresolved sequence gaps --\n");
        if (sequenceGaps.isEmpty()) {
            sb.append("none\n");
        }
        for (SequenceGate.Gap g : sequenceGaps) {
            sb.append(String.format("lane=%s waitingFor=%d missing=%s buffered=%s%n",
                    g.lane(), g.waitingFor(), g.missing(), g.buffered()));
        }

        sb.append("\n-- Balances --\n");
        balances.forEach((account, balance) ->
                sb.append(String.format("%-10s: %s%n", Masking.maskAccount(account), balance.toPlainString())));
        return sb.toString();
    }
}
