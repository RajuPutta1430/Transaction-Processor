package com.assessment.txn.util;

/** Helpers so sensitive identifiers never appear in full in logs or reports. */
public final class Masking {

    private Masking() {
    }

    /** "ACC-201" becomes "***201". Only the tail is kept. */
    public static String maskAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return "n/a";
        }
        int keep = Math.min(3, accountId.length() / 2);
        return "***" + accountId.substring(accountId.length() - keep);
    }
}
