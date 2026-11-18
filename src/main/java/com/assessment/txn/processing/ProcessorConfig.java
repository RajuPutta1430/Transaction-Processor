package com.assessment.txn.processing;

import com.assessment.txn.ordering.SequenceScope;

/**
 * @param maxAttempts       attempts per processing round (first try included)
 * @param baseBackoffMillis delay before the first retry; doubles on each further retry (0 disables)
 * @param scope             whether sequence numbers are global or per account
 * @param firstSequence     the first sequence number expected in each lane
 */
public record ProcessorConfig(int maxAttempts, long baseBackoffMillis, SequenceScope scope, long firstSequence) {

    public ProcessorConfig {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (baseBackoffMillis < 0) {
            throw new IllegalArgumentException("baseBackoffMillis must not be negative");
        }
    }

    public static ProcessorConfig defaults() {
        return new ProcessorConfig(3, 100, SequenceScope.GLOBAL, 1);
    }
}
