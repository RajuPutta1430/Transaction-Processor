package com.assessment.txn.source;

import com.assessment.txn.model.RawTransaction;
import java.util.List;

/** Handy for tests and demos. */
public final class InMemoryTransactionSource implements TransactionSource {

    private final List<RawTransaction> records;

    public InMemoryTransactionSource(List<RawTransaction> records) {
        this.records = List.copyOf(records);
    }

    @Override
    public List<RawTransaction> readAll() {
        return records;
    }
}
