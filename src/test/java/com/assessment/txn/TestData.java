package com.assessment.txn;

import com.assessment.txn.model.RawTransaction;

/** Small builders so the tests read like the scenarios they describe. */
public final class TestData {

    private TestData() {
    }

    public static RawTransaction raw(String txnId, String reqId, long seq, String account, String type, String amount) {
        return new RawTransaction("test", txnId, reqId, String.valueOf(seq), account, type, amount, "USD");
    }

    public static RawTransaction credit(String txnId, long seq, String account, String amount) {
        return raw(txnId, "REQ-" + txnId, seq, account, "CREDIT", amount);
    }

    public static RawTransaction debit(String txnId, long seq, String account, String amount) {
        return raw(txnId, "REQ-" + txnId, seq, account, "DEBIT", amount);
    }
}
