package com.assessment.txn.fault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Fails the first N calls for a given transactionId, then lets calls through. */
public final class ScriptedFailureInjector implements FailureInjector {

    private final Map<String, AtomicInteger> failuresBefore = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failuresAfter = new ConcurrentHashMap<>();

    public ScriptedFailureInjector failBefore(String transactionId, int times) {
        failuresBefore.put(transactionId, new AtomicInteger(times));
        return this;
    }

    public ScriptedFailureInjector failAfter(String transactionId, int times) {
        failuresAfter.put(transactionId, new AtomicInteger(times));
        return this;
    }

    @Override
    public void beforeApply(String transactionId) {
        maybeFail(failuresBefore, transactionId, "simulated transient failure before apply");
    }

    @Override
    public void afterApply(String transactionId) {
        maybeFail(failuresAfter, transactionId, "simulated transient failure after apply");
    }

    private static void maybeFail(Map<String, AtomicInteger> budget, String id, String message) {
        AtomicInteger remaining = budget.get(id);
        if (remaining != null && remaining.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
            throw new TransientFailureException(message);
        }
    }
}
