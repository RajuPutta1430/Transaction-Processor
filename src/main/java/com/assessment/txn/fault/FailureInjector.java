package com.assessment.txn.fault;

/**
 * Test seam for simulating transient infrastructure failures. The two hooks surround the ledger
 * write so we can prove retries are safe both when the failure happens before the write and when
 * it happens after the write succeeded (the dangerous case: the work is done but we think it failed).
 */
public interface FailureInjector {

    FailureInjector NONE = new FailureInjector() { };

    default void beforeApply(String transactionId) {
    }

    default void afterApply(String transactionId) {
    }
}
