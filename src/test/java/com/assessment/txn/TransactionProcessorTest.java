package com.assessment.txn;

import static com.assessment.txn.TestData.credit;
import static com.assessment.txn.TestData.debit;
import static org.testng.Assert.*;

import com.assessment.txn.fault.FailureInjector;
import com.assessment.txn.fault.ScriptedFailureInjector;
import com.assessment.txn.ledger.InMemoryLedger;
import com.assessment.txn.model.FailureReason;
import com.assessment.txn.model.RawTransaction;
import com.assessment.txn.model.TransactionStatus;
import com.assessment.txn.ordering.SequenceScope;
import com.assessment.txn.processing.ProcessorConfig;
import com.assessment.txn.processing.TransactionProcessor;
import com.assessment.txn.processing.TransactionSnapshot;
import com.assessment.txn.report.ProcessingReport;
import java.math.BigDecimal;
import java.util.List;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class TransactionProcessorTest {

    private InMemoryLedger ledger;

    @BeforeMethod
    public void setUp() {
        ledger = new InMemoryLedger();
    }

    private TransactionProcessor processor(FailureInjector injector) {
        return new TransactionProcessor(new ProcessorConfig(3, 0, SequenceScope.GLOBAL, 1), ledger, injector);
    }

    private TransactionProcessor processor() {
        return processor(FailureInjector.NONE);
    }

    private static BigDecimal money(String v) {
        return new BigDecimal(v);
    }

    private TransactionSnapshot snapshot(TransactionProcessor p, String id) {
        return p.find(id).orElseThrow();
    }

    // ------------------------------------------------------------ duplicates

    @Test
    public void duplicateOfAProcessedTransactionIsDetectedAndNotReapplied() {
        TransactionProcessor p = processor();
        assertEquals(p.submit(credit("TXN-1", 1, "ACC-1", "100")), TransactionStatus.PROCESSED);
        assertEquals(p.submit(
                TestData.raw("TXN-1", "REQ-RETRY", 1, "ACC-1", "CREDIT", "100")), TransactionStatus.DUPLICATE);

        assertEquals(ledger.balanceOf("ACC-1"), money("100.00"));
        assertEquals(ledger.entryCount(), 1);
        assertEquals(snapshot(p, "TXN-1").duplicateCount(), 1);
    }

    @Test
    public void duplicateArrivingWhileTheOriginalIsStillPendingIsNotBufferedTwice() {
        TransactionProcessor p = processor();
        assertEquals(p.submit(debit("TXN-2", 2, "ACC-1", "40")), TransactionStatus.PENDING);
        assertEquals(p.submit(
                TestData.raw("TXN-2", "REQ-DUP", 2, "ACC-1", "DEBIT", "40")), TransactionStatus.DUPLICATE);

        p.submit(credit("TXN-1", 1, "ACC-1", "100"));

        assertEquals(ledger.balanceOf("ACC-1"), money("60.00"));
        assertEquals(ledger.entryCount(), 2);
    }

    @Test
    public void sameTransactionIdWithDifferentPayloadIsRejectedAndOriginalIsUntouched() {
        TransactionProcessor p = processor();
        p.submit(credit("TXN-1", 1, "ACC-1", "100"));

        TransactionStatus result = p.submit(
                TestData.raw("TXN-1", "REQ-OTHER", 1, "ACC-1", "CREDIT", "999"));

        assertEquals(result, TransactionStatus.FAILED);
        assertEquals(ledger.balanceOf("ACC-1"), money("100.00"));
        assertEquals(p.report().rejected().get(0).reason(), FailureReason.IDEMPOTENCY_CONFLICT);
        assertEquals(snapshot(p, "TXN-1").status(), TransactionStatus.PROCESSED);
    }

    // ------------------------------------------------------------ ordering

    @Test
    public void outOfOrderRequestsAreProcessedInSequenceOrder() {
        TransactionProcessor p = processor();
        // Arrives 3, 2, 1. Applied in arrival order the debit would hit an empty account.
        p.submit(credit("TXN-3", 3, "ACC-1", "25"));
        p.submit(debit("TXN-2", 2, "ACC-1", "40"));
        assertEquals(snapshot(p, "TXN-3").status(), TransactionStatus.PENDING);
        p.submit(credit("TXN-1", 1, "ACC-1", "100"));

        assertEquals(ledger.appliedOrder(), List.of("TXN-1", "TXN-2", "TXN-3"));
        assertEquals(ledger.balanceOf("ACC-1"), money("85.00"));
        assertEquals(p.report().count(TransactionStatus.PROCESSED), 3);
    }

    @Test
    public void missingSequencesAreReported() {
        TransactionProcessor p = processor();
        p.submit(credit("TXN-1", 1, "ACC-1", "10"));
        p.submit(credit("TXN-4", 4, "ACC-1", "10"));

        ProcessingReport report = p.report();
        assertEquals(report.count(TransactionStatus.PENDING), 1);
        assertEquals(report.sequenceGaps().get(0).missing(), List.of(2L, 3L));
    }

    @Test
    public void aRejectedRecordReleasesItsSequenceSlot() {
        TransactionProcessor p = processor();
        p.submit(credit("TXN-1", 1, "ACC-1", "10"));
        p.submit(credit("TXN-3", 3, "ACC-1", "10"));          // buffered, waiting for 2
        p.submit(credit("TXN-2", 2, "ACC-1", "-5"));          // invalid: slot 2 is resolved

        assertEquals(snapshot(p, "TXN-3").status(), TransactionStatus.PROCESSED);
        assertTrue(p.report().sequenceGaps().isEmpty());
    }

    @Test
    public void perAccountScopeLetsAccountsProgressIndependently() {
        TransactionProcessor p = new TransactionProcessor(
                new ProcessorConfig(3, 0, SequenceScope.PER_ACCOUNT, 1), ledger);
        assertEquals(p.submit(credit("A-1", 1, "ACC-A", "10")), TransactionStatus.PROCESSED);
        assertEquals(p.submit(credit("B-1", 1, "ACC-B", "20")), TransactionStatus.PROCESSED);
        assertEquals(p.submit(credit("A-3", 3, "ACC-A", "5")), TransactionStatus.PENDING);
    }

    @Test
    public void aSequenceSlotUsedByAnotherTransactionIsRejected() {
        TransactionProcessor p = processor();
        p.submit(credit("TXN-1", 1, "ACC-1", "10"));
        TransactionStatus result = p.submit(credit("TXN-OTHER", 1, "ACC-1", "10"));

        assertEquals(result, TransactionStatus.FAILED);
        assertEquals(snapshot(p, "TXN-OTHER").failureReason(), FailureReason.SEQUENCE_CONFLICT);
        assertEquals(ledger.balanceOf("ACC-1"), money("10.00"));
    }

    // ------------------------------------------------------------ invalid input

    @Test
    public void invalidRecordsAreIsolatedAndTheBatchContinues() {
        TransactionProcessor p = processor();
        List<RawTransaction> batch = List.of(
                credit("TXN-1", 1, "ACC-1", "100"),
                debit("TXN-2", 2, "ACC-1", "-10"),
                new RawTransaction("line 4", "TXN-3", "REQ-3", "3", null, "CREDIT", "5", "USD"),
                TestData.raw("TXN-4", "REQ-4", 4, "ACC-1", "CREDIT", "abc"),
                credit("TXN-5", 5, "ACC-1", "25"));

        p.processAll(batch);

        ProcessingReport report = p.report();
        assertEquals(report.rejected().size(), 3);
        assertEquals(report.count(TransactionStatus.PROCESSED), 2);
        assertEquals(ledger.balanceOf("ACC-1"), money("125.00"));
    }

    // ------------------------------------------------------------ retries

    @Test
    public void transientFailureIsRetriedAndAppliedOnce() {
        TransactionProcessor p = processor(new ScriptedFailureInjector().failBefore("TXN-1", 1));
        assertEquals(p.submit(credit("TXN-1", 1, "ACC-1", "500")), TransactionStatus.PROCESSED);

        TransactionSnapshot s = snapshot(p, "TXN-1");
        assertEquals(s.attempts(), 2);
        assertEquals(s.history(), List.of(TransactionStatus.RECEIVED, TransactionStatus.PROCESSING,
                TransactionStatus.RETRY_PENDING, TransactionStatus.PROCESSING,
                TransactionStatus.PROCESSED));
        assertEquals(ledger.balanceOf("ACC-1"), money("500.00"));
        assertEquals(p.report().retryAttempts(), 1);
    }

    @Test
    public void failureAfterTheLedgerWriteDoesNotDoubleApplyOnRetry() {
        // The dangerous case: the write succeeded but we believe it failed.
        TransactionProcessor p = processor(new ScriptedFailureInjector().failAfter("TXN-1", 1));
        assertEquals(p.submit(credit("TXN-1", 1, "ACC-1", "100")), TransactionStatus.PROCESSED);

        assertEquals(ledger.balanceOf("ACC-1"), money("100.00"));
        assertEquals(ledger.entryCount(), 1);
        assertEquals(snapshot(p, "TXN-1").attempts(), 2);
    }

    @Test
    public void exhaustedRetriesEndInFailedAndCanBeRecoveredWithoutDuplicateEffect() {
        // 5 failures: round 1 uses 3 attempts and fails; recovery round fails twice more, then succeeds.
        TransactionProcessor p = processor(new ScriptedFailureInjector().failBefore("TXN-1", 5));
        assertEquals(p.submit(credit("TXN-1", 1, "ACC-1", "100")), TransactionStatus.FAILED);

        TransactionSnapshot failed = snapshot(p, "TXN-1");
        assertEquals(failed.failureReason(), FailureReason.RETRIES_EXHAUSTED);
        assertTrue(failed.retryable());
        assertEquals(ledger.balanceOf("ACC-1"), money("0.00"));

        assertEquals(p.retryFailedTransient(), 1);

        assertEquals(snapshot(p, "TXN-1").status(), TransactionStatus.PROCESSED);
        assertNull(snapshot(p, "TXN-1").failureReason());
        assertEquals(ledger.balanceOf("ACC-1"), money("100.00"));
        assertEquals(ledger.entryCount(), 1);
        assertEquals(p.retryFailedTransient(), 0);   // calling it again changes nothing
    }

    @Test
    public void permanentBusinessFailureIsNotRetriedAndDoesNotBlockLaterRecords() {
        TransactionProcessor p = processor();
        p.submit(debit("TXN-1", 1, "ACC-1", "50"));          // insufficient funds
        p.submit(credit("TXN-2", 2, "ACC-1", "30"));

        TransactionSnapshot first = snapshot(p, "TXN-1");
        assertEquals(first.status(), TransactionStatus.FAILED);
        assertEquals(first.failureReason(), FailureReason.INSUFFICIENT_FUNDS);
        assertFalse(first.retryable());
        assertEquals(first.attempts(), 1);
        assertEquals(snapshot(p, "TXN-2").status(), TransactionStatus.PROCESSED);
        assertEquals(p.retryFailedTransient(), 0);
    }

    @Test
    public void duplicateOfAFailedTransactionIsNotReprocessedAutomatically() {
        TransactionProcessor p = processor();
        p.submit(debit("TXN-1", 1, "ACC-1", "50"));
        p.submit(credit("TXN-FUND", 2, "ACC-1", "100"));

        assertEquals(p.submit(
                TestData.raw("TXN-1", "REQ-AGAIN", 1, "ACC-1", "DEBIT", "50")), TransactionStatus.DUPLICATE);
        assertEquals(ledger.balanceOf("ACC-1"), money("100.00"));
    }

    // ------------------------------------------------------------ report

    @Test
    public void reportShowsCountsByStatus() {
        TransactionProcessor p = processor();
        p.submit(credit("TXN-1", 1, "ACC-1", "10"));
        p.submit(credit("TXN-1", 1, "ACC-1", "10"));
        p.submit(credit("TXN-2", 2, "ACC-1", "-1"));
        p.submit(credit("TXN-9", 9, "ACC-1", "10"));

        ProcessingReport report = p.report();
        assertEquals(report.inputsReceived(), 4);
        assertEquals(report.count(TransactionStatus.PROCESSED), 1);
        assertEquals(report.count(TransactionStatus.DUPLICATE), 1);
        assertEquals(report.count(TransactionStatus.FAILED), 1);
        assertEquals(report.count(TransactionStatus.PENDING), 1);
        assertFalse(report.toText().contains("ACC-1"), "account ids must be masked in the report");
    }
}
