# Design Note

## 1. Goal and scope

Process a feed of transactions so that the observable effect (account balances) is correct even
when the feed contains duplicates, arrives out of order, hits transient failures or contains bad
records. In scope: validation, idempotency, ordering, retries, reporting, tests.
Out of scope (listed in section 9): persistence, real queues, multi-instance deployment.

## 2. Architecture

```
TransactionSource (CSV / in-memory)
        |  RawTransaction (untrusted strings)
        v
TransactionValidator ---- invalid ----> RejectedRecord (dead-letter list) + release sequence slot
        |  TransactionRequest (typed)
        v
Idempotency store (transactionId -> TransactionRecord)
        |  new            \_ replay -> DUPLICATE (or IDEMPOTENCY_CONFLICT if payload differs)
        v
SequenceGate (per lane buffer) ---- early -> PENDING
        |  released in order
        v
Retry loop --> AccountLedger.apply (atomic + idempotent) --> PROCESSED / FAILED
        |
        v
ProcessingReport + structured log + event trail
```

Business logic (`TransactionProcessor`) knows nothing about files: input comes through the
`TransactionSource` interface and the side effect goes through the `AccountLedger` interface.

## 3. Idempotency

- Key: `transactionId`. The state store is checked before anything else happens.
- A replay with the same payload is reported as DUPLICATE and changes nothing. The `requestId`
  is ignored in the comparison, because a retrying client legitimately sends a new one.
- A replay with the same `transactionId` but a different payload is rejected as
  IDEMPOTENCY_CONFLICT. It is never applied and never overwrites the original.
- Duplicates are also detected while the original is still PENDING, so one transaction can never
  sit twice in the buffer.
- Defence in depth: the ledger keeps its own entry per `transactionId`. If the processor ever
  lost track of a transaction (for example a crash after the ledger write), re-applying it
  returns ALREADY_APPLIED and only the status is reconciled.
- Status moves to PROCESSED only after the ledger write succeeded.

## 4. Out-of-order handling (Option A, sequence-based)

- Each lane has a "next expected" sequence. A record ahead of it is buffered as PENDING.
- When the missing sequence arrives, it and every consecutive buffered successor are released
  in order.
- A rejected record (for example a negative amount) still carries a readable sequence number, so
  its slot is marked resolved. Otherwise one bad record would block the stream forever.
- A permanently FAILED transaction also resolves its slot, so later records keep flowing.
- Records still waiting at the end are reported with the exact missing sequence numbers.
- Late arrivals for an already-consumed slot, or two different transactions claiming one slot,
  are rejected as SEQUENCE_CONFLICT.

**Scope decision.** The sample data uses one global sequence (accounts 201, 202 and 203 carry
sequences 1 to 5). So the default scope is GLOBAL. `PER_ACCOUNT` is supported and tested, and is
what I would use in production because accounts can then progress independently.

**Trade-offs.** Strict ordering delays everything behind a gap. A production system would add a
gap timeout and an alert, and a plausibility window to reject absurd sequence numbers. Because a
failed record releases its slot, a late recovery of that record is applied out of order; the
ledger still enforces "no negative balance", so the risk is a later rejection, not corruption.

## 5. Retry and failure handling

| Failure | Handling |
|---|---|
| Transient (timeout, blip) | Retry up to 3 attempts with exponential backoff (100 ms base, doubled each time). Status goes PROCESSING, RETRY_PENDING, PROCESSING. |
| Retries exhausted | FAILED with reason RETRIES_EXHAUSTED, marked recoverable. `retryFailedTransient()` replays these safely. |
| Business rule (insufficient funds, currency mismatch) | FAILED immediately, never retried, because retrying cannot help. |
| Validation | Rejected to the dead-letter list; the batch continues. |
| Unexpected exception | FAILED as UNEXPECTED_ERROR; only the exception type is recorded, never its message. |

Retries are safe because the ledger apply is idempotent. This is proven by a test that injects a
failure after the ledger write succeeded: the retry finds the entry and the balance is applied once.

Status changes go through one method that enforces the lifecycle
(`RECEIVED -> PENDING/PROCESSING/FAILED`, `PROCESSING -> PROCESSED/RETRY_PENDING/FAILED`,
`PROCESSED` is terminal), so an illegal jump such as PROCESSED back to PROCESSING fails loudly.

## 6. Atomicity and concurrency

- `TransactionProcessor` methods are synchronized: the state machine is single-writer. This is
  simple to reason about and is verified by two concurrency tests (32 threads submitting the same
  transaction; 200 shuffled sequences from 8 threads).
- Inside the ledger, balance update, currency binding and ledger entry change in one critical
  section per account, so nobody can observe a half-applied transaction.
- Known cost: retry backoff sleeps while holding the lock. Acceptable for this exercise;
  production would schedule retries through a delayed queue instead.

## 7. Observability and security

- Structured one-line logs: `event=PROCESSED txnId=... requestId=... detail=...`, plus a
  per-record event trail and a summary report (counts by status, retries, rejected records,
  unresolved gaps, balances).
- Account ids are masked (`***201`) in logs, reports and `TransactionRequest.toString()`.
- Validation errors name fields, never values. IDs are restricted to `[A-Za-z0-9._-]{1,64}`,
  which also prevents log injection through crafted ids.
- Amounts are `BigDecimal`, positive, at most 2 decimals, with an upper bound.
- No secrets, no network access, no hardcoded credentials.

## 8. Testing strategy

32 TestNG tests: unit tests for the validator, sequence gate and ledger; scenario tests for
duplicates, ordering, retries and recovery; concurrency tests; and an end-to-end test using the
assessment's own dataset. The expected final balance of ACC-201 (85.00) is asserted.
To test this service further: contract tests on the `AccountLedger` interface, property-based
tests that shuffle and duplicate random feeds and assert the final balance equals the in-order
result, load tests, and failure-injection tests against a real database.

## 9. Production hardening (not built)

- Persistent store with a unique constraint on `transactionId`; apply and status update in one DB transaction.
- Real queue (Kafka/SQS) partitioned by account so one consumer owns each account's order; this replaces the global lock.
- Dead-letter queue and a replay tool for rejected and recoverable records.
- Recovery job for records stuck in PROCESSING after a crash.
- Delayed retries instead of sleeping, jittered backoff, and a gap timeout with alerting.
- Metrics (processed, duplicates, retries, failures, gap age), tracing, and persisted audit trail.
- REVERSAL support that references the original transaction.

## 10. How AI was used and how the output was checked

**Where I used AI.** I used Claude (Anthropic) as a pair-programming assistant. I used it to
analyse the assessment document, propose the architecture, generate the first version of the
code and the test suite, and draft this document. The decisions below are ones I reviewed and
can explain:  GLOBAL sequence scope by default,releasing the sequence slot of rejected records, 
idempotent ledger as a second line of defence

**What I chose myself.** I chose Java with Maven and TestNG because that is my core automation
stack. I asked the recruiter to confirm that this backend assessment was the intended one for
the SDET role before starting. 

**How I validated the generated code.**
1. I ran `mvn clean test` on my own machine. The first run failed because Maven defaulted to an
   old compiler plugin that targeted Java 1.5. I fixed it by pinning `maven-compiler-plugin`
   3.13.0 with `release 17`. After that, all 32 tests pass.
2. I ran the application on the assessment's sample data and compared the output with the
   expected table: ACC-201 ends at 85.00, TXN-2002 is reported as a duplicate, TXN-2004 is
   rejected, TXN-2005 is retried once.
3. I read each class and traced the main flow by hand: validate, idempotency check, sequence
   gate, retry loop, ledger apply.


**Limits of AI-generated code I kept in mind.** Generated tests can pass while missing
scenarios, so I added my own cases. The design is simple on purpose (in-memory, single writer),
and section 9 lists what production would need.
