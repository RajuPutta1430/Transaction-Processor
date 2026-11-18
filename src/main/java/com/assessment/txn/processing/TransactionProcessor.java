package com.assessment.txn.processing;

import com.assessment.txn.fault.FailureInjector;
import com.assessment.txn.fault.TransientFailureException;
import com.assessment.txn.ledger.AccountLedger;
import com.assessment.txn.ledger.ApplyResult;
import com.assessment.txn.ledger.BusinessRuleException;
import com.assessment.txn.model.FailureReason;
import com.assessment.txn.model.RawTransaction;
import com.assessment.txn.model.TransactionRequest;
import com.assessment.txn.model.TransactionStatus;
import com.assessment.txn.ordering.SequenceGate;
import com.assessment.txn.ordering.SequenceScope;
import com.assessment.txn.report.ProcessingReport;
import com.assessment.txn.source.TransactionSource;
import com.assessment.txn.util.Masking;
import com.assessment.txn.validation.TransactionValidator;
import com.assessment.txn.validation.ValidationResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The core of the service: validate, de-duplicate, order, apply with retries, and report.
 *
 * Concurrency model: every public method is synchronized, so the state machine is
 * single-writer. That is simple and provably correct for this exercise; DESIGN.md explains how
 * production would get the same guarantee from partitioning by key plus a DB unique constraint.
 */
public final class TransactionProcessor {

    private static final Logger LOG = Logger.getLogger(TransactionProcessor.class.getName());
    private static final long MAX_BACKOFF_MILLIS = 5_000;

    private final ProcessorConfig config;
    private final AccountLedger ledger;
    private final FailureInjector injector;
    private final TransactionValidator validator = new TransactionValidator();
    private final SequenceGate<TransactionRequest> gate;

    /** Idempotency / state store, keyed by transactionId. */
    private final Map<String, TransactionRecord> store = new LinkedHashMap<>();
    private final List<RejectedRecord> rejected = new ArrayList<>();
    private final List<ProcessingEvent> events = new ArrayList<>();
    private int inputsReceived;
    private int duplicateRequests;
    private int retryAttempts;

    public TransactionProcessor(ProcessorConfig config, AccountLedger ledger, FailureInjector injector) {
        this.config = config;
        this.ledger = ledger;
        this.injector = injector;
        this.gate = new SequenceGate<>(config.firstSequence());
    }

    public TransactionProcessor(ProcessorConfig config, AccountLedger ledger) {
        this(config, ledger, FailureInjector.NONE);
    }

    // ---------------------------------------------------------------- ingestion

    public synchronized void processAll(TransactionSource source) throws IOException {
        processAll(source.readAll());
    }

    /** Processes a batch. A failure in one record never stops the others. */
    public synchronized void processAll(Collection<RawTransaction> batch) {
        for (RawTransaction raw : batch) {
            try {
                submit(raw);
            } catch (RuntimeException e) {
                rejected.add(new RejectedRecord(raw.sourceRef(), null, FailureReason.UNEXPECTED_ERROR,
                        List.of(e.getClass().getSimpleName())));
                LOG.severe("event=ERROR source=" + raw.sourceRef() + " cause=" + e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Handles one incoming record and returns the status of that record at this moment:
     * PROCESSED, PENDING (buffered), DUPLICATE or FAILED.
     */
    public synchronized TransactionStatus submit(RawTransaction raw) {
        inputsReceived++;

        ValidationResult validation = validator.validate(raw);
        if (!validation.isValid()) {
            return rejectInvalid(validation);
        }
        TransactionRequest request = validation.request();

        TransactionRecord existing = store.get(request.transactionId());
        if (existing != null) {
            return handleReplay(existing, request);
        }

        TransactionRecord record = new TransactionRecord(request);
        store.put(request.transactionId(), record);

        SequenceGate.OfferResult<TransactionRequest> offer =
                gate.offer(laneFor(request.accountId()), request.sequenceNumber(), request);
        switch (offer.status()) {
            case STALE -> fail(record, FailureReason.SEQUENCE_CONFLICT, "sequence slot already consumed");
            case CONFLICT -> fail(record, FailureReason.SEQUENCE_CONFLICT, "sequence slot already buffered");
            case BUFFERED -> {
                record.transitionTo(TransactionStatus.PENDING);
                emit(TransactionStatus.PENDING, request.requestId(), request.transactionId(),
                        "waiting for an earlier sequence, seq=" + request.sequenceNumber());
            }
            case READY -> {
                // processed below, together with any buffered successors that were released
            }
        }
        processReleased(offer.ready());
        return record.status();
    }

    // ---------------------------------------------------------------- recovery

    /**
     * Re-runs transactions that failed only because transient errors outlasted the retry budget.
     * Safe to call repeatedly: the ledger is idempotent, so a recovered transaction is applied once.
     *
     * @return how many transactions ended up PROCESSED
     */
    public synchronized int retryFailedTransient() {
        int recovered = 0;
        for (TransactionRecord record : new ArrayList<>(store.values())) {
            if (record.isRetryableFailure()) {
                processOne(record);
                if (record.status() == TransactionStatus.PROCESSED) {
                    recovered++;
                }
            }
        }
        return recovered;
    }

    // ---------------------------------------------------------------- queries

    public synchronized Optional<TransactionSnapshot> find(String transactionId) {
        return Optional.ofNullable(store.get(transactionId)).map(TransactionRecord::snapshot);
    }

    public synchronized ProcessingReport report() {
        Map<TransactionStatus, Integer> counts = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus s : TransactionStatus.values()) {
            counts.put(s, 0);
        }
        List<TransactionSnapshot> snapshots = new ArrayList<>();
        for (TransactionRecord record : store.values()) {
            snapshots.add(record.snapshot());
            counts.merge(record.status(), 1, Integer::sum);
        }
        counts.merge(TransactionStatus.FAILED, rejected.size(), Integer::sum);
        counts.put(TransactionStatus.DUPLICATE, duplicateRequests);

        List<SequenceGate.Gap> gaps = new ArrayList<>();
        for (SequenceGate.Gap g : gate.gaps()) {
            String lane = config.scope() == SequenceScope.GLOBAL ? "global" : Masking.maskAccount(g.lane());
            gaps.add(new SequenceGate.Gap(lane, g.waitingFor(), g.missing(), g.buffered()));
        }
        return new ProcessingReport(inputsReceived, store.size(), retryAttempts, counts, snapshots,
                List.copyOf(rejected), gaps, List.copyOf(events), ledger.balances());
    }

    // ---------------------------------------------------------------- internals

    private TransactionStatus rejectInvalid(ValidationResult v) {
        rejected.add(new RejectedRecord(v.sourceRef(), v.transactionId(), FailureReason.VALIDATION_FAILED, v.errors()));
        emit(TransactionStatus.FAILED, v.requestId(), v.transactionId(), "VALIDATION_FAILED " + v.errors());

        // Release the ordering slot so this rejected record cannot block later records forever.
        boolean laneKnown = config.scope() == SequenceScope.GLOBAL || v.accountId() != null;
        if (v.sequence() != null && laneKnown) {
            processReleased(gate.skip(laneFor(v.accountId()), v.sequence()));
        }
        return TransactionStatus.FAILED;
    }

    private TransactionStatus handleReplay(TransactionRecord existing, TransactionRequest incoming) {
        if (!existing.request().samePayloadAs(incoming)) {
            // Same business key but different content: never apply it, never overwrite the original.
            rejected.add(new RejectedRecord(incoming.sourceRef(), incoming.transactionId(),
                    FailureReason.IDEMPOTENCY_CONFLICT, List.of("payload differs from the original request")));
            emit(TransactionStatus.FAILED, incoming.requestId(), incoming.transactionId(), "IDEMPOTENCY_CONFLICT");
            return TransactionStatus.FAILED;
        }
        existing.incrementDuplicates();
        duplicateRequests++;
        emit(TransactionStatus.DUPLICATE, incoming.requestId(), incoming.transactionId(),
                "original is " + existing.status());
        return TransactionStatus.DUPLICATE;
    }

    private void processReleased(List<TransactionRequest> released) {
        for (TransactionRequest request : released) {
            processOne(store.get(request.transactionId()));
        }
    }

    /**
     * Applies one transaction with bounded retries. Retrying is safe because the ledger apply is
     * idempotent: if a previous attempt actually wrote the entry before "failing", the next attempt
     * reports ALREADY_APPLIED and we only reconcile the status.
     */
    private void processOne(TransactionRecord record) {
        TransactionRequest request = record.request();
        String txnId = request.transactionId();
        record.transitionTo(TransactionStatus.PROCESSING);

        int attemptsThisRound = 0;
        while (true) {
            attemptsThisRound++;
            record.incrementAttempts();
            try {
                injector.beforeApply(txnId);
                ApplyResult result = ledger.apply(request);
                injector.afterApply(txnId);

                record.transitionTo(TransactionStatus.PROCESSED);
                emit(TransactionStatus.PROCESSED, request.requestId(), txnId,
                        (result == ApplyResult.APPLIED ? "applied" : "ledger entry already existed, status reconciled")
                                + " attempts=" + record.attempts());
                return;
            } catch (TransientFailureException e) {
                if (attemptsThisRound >= config.maxAttempts()) {
                    fail(record, FailureReason.RETRIES_EXHAUSTED, "attempts=" + attemptsThisRound);
                    return;
                }
                retryAttempts++;
                record.transitionTo(TransactionStatus.RETRY_PENDING);
                emit(TransactionStatus.RETRY_PENDING, request.requestId(), txnId,
                        "transient failure on attempt " + attemptsThisRound);
                backoff(attemptsThisRound);
                record.transitionTo(TransactionStatus.PROCESSING);
            } catch (BusinessRuleException e) {
                fail(record, e.reason(), "business rule rejected the transaction");
                return;
            } catch (RuntimeException e) {
                // Safe error handling: record the exception type only, never its message or payload.
                fail(record, FailureReason.UNEXPECTED_ERROR, "unexpected " + e.getClass().getSimpleName());
                return;
            }
        }
    }

    private void fail(TransactionRecord record, FailureReason reason, String detail) {
        record.markFailed(reason);
        emit(TransactionStatus.FAILED, record.request().requestId(), record.request().transactionId(),
                reason + " " + detail);
    }

    private String laneFor(String accountId) {
        return config.scope() == SequenceScope.GLOBAL ? "GLOBAL" : accountId;
    }

    private void backoff(int attemptNumber) {
        long delay = Math.min(MAX_BACKOFF_MILLIS, config.baseBackoffMillis() * (1L << (attemptNumber - 1)));
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void emit(TransactionStatus status, String requestId, String transactionId, String detail) {
        events.add(new ProcessingEvent(requestId, transactionId, status, detail));
        Level level = status == TransactionStatus.FAILED ? Level.WARNING : Level.INFO;
        LOG.log(level, String.format("event=%s txnId=%s requestId=%s detail=%s",
                status, transactionId == null ? "n/a" : transactionId,
                requestId == null ? "n/a" : requestId, detail));
    }
}
