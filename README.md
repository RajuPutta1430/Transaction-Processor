# Idempotent Background Transaction Processor

A small Java service that processes financial transactions safely when input is messy:
duplicates, out-of-order delivery, transient failures and invalid records.
Built for the FDE L2 assessment. See [DESIGN.md](DESIGN.md) for the reasoning.

## Requirements

- Java 17 or newer
- Maven 3.8+

## Build, test, run

```bash
mvn test                                   # run all 32 tests
mvn package                                # build target/idempotent-txn-processor-1.0.0.jar
java -jar target/idempotent-txn-processor-1.0.0.jar                      # runs data/sample-input.csv
java -jar target/idempotent-txn-processor-1.0.0.jar my.csv --scope=PER_ACCOUNT --no-faults
```

Options: `[inputFile]`, `--scope=GLOBAL|PER_ACCOUNT` (default GLOBAL), `--no-faults`.
By default the demo simulates one transient failure for `TXN-2005` so the retry path is visible.

Input CSV columns (any order, header required):
`transactionId,requestId,sequenceNumber,accountId,transactionType,amount,currency`

Expected output for the sample file is in [docs/sample-output.txt](docs/sample-output.txt).

## Sample result

| Input | Outcome |
|---|---|
| TXN-2002 (seq 2) arrives first | PENDING (waiting for seq 1) |
| TXN-2001 (seq 1) | PROCESSED, then TXN-2002 is released and PROCESSED |
| TXN-2002 again (REQ-2-DUP) | DUPLICATE, balance not touched |
| TXN-2003 (seq 3) | PROCESSED |
| TXN-2004 (amount -10) | FAILED (validation), batch continues |
| TXN-2005 | transient failure, retried, PROCESSED on attempt 2 |

Final balance for ACC-201 is 85.00 (100 - 40 + 25).

## Project layout

```
src/main/java/com/assessment/txn
  model/       RawTransaction, TransactionRequest, status and failure enums
  validation/  TransactionValidator, ValidationResult
  source/      TransactionSource + CSV and in-memory implementations
  ordering/    SequenceGate (out-of-order buffering), SequenceScope
  ledger/      AccountLedger + InMemoryLedger (atomic, idempotent side effect)
  fault/       FailureInjector for simulating transient failures
  processing/  TransactionProcessor (core), config, state record, events
  report/      ProcessingReport
  util/        Masking
  App.java     command-line entry point
src/test/java  TestNG tests
data/          sample input
docs/          sample output
```

## Test matrix

| Scenario | Test |
|---|---|
| Duplicate of processed transaction not re-applied | `duplicateOfAProcessedTransactionIsDetectedAndNotReapplied` |
| Duplicate while original still pending | `duplicateArrivingWhileTheOriginalIsStillPendingIsNotBufferedTwice` |
| Same id, different payload | `sameTransactionIdWithDifferentPayloadIsRejected...` |
| Out-of-order processed in order | `outOfOrderRequestsAreProcessedInSequenceOrder` |
| Missing sequences reported | `missingSequencesAreReported` |
| Rejected record frees its sequence slot | `aRejectedRecordReleasesItsSequenceSlot` |
| Sequence slot reused by another transaction | `aSequenceSlotUsedByAnotherTransactionIsRejected` |
| Invalid records isolated, batch continues | `invalidRecordsAreIsolatedAndTheBatchContinues` |
| Transient failure retried, applied once | `transientFailureIsRetriedAndAppliedOnce` |
| Failure after ledger write, no double apply | `failureAfterTheLedgerWriteDoesNotDoubleApplyOnRetry` |
| Retries exhausted, then recovered | `exhaustedRetriesEndInFailedAndCanBeRecovered...` |
| Business failure not retried | `permanentBusinessFailureIsNotRetried...` |
| Counts by status, masked accounts | `reportShowsCountsByStatus` |
| 32 threads, same transaction | `theSameTransactionSubmittedByManyThreadsIsAppliedExactlyOnce` |
| Shuffled sequences, 8 threads | `shuffledSequencesSubmittedConcurrentlyAllEndUpProcessedInOrder` |
| Assessment dataset end to end | `SampleDatasetTest` |
