package com.assessment.txn.fault;

/** A failure that is expected to go away on its own (timeout, lock timeout, downstream blip). */
public class TransientFailureException extends RuntimeException {
    public TransientFailureException(String message) {
        super(message);
    }
}
