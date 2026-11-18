package com.assessment.txn.ordering;

/**
 * What a sequence number is unique within.
 * GLOBAL: one stream for the whole feed (matches the assessment's sample data).
 * PER_ACCOUNT: each account has its own 1..n stream (what most real systems do, and it lets
 * accounts progress independently).
 */
public enum SequenceScope {
    GLOBAL,
    PER_ACCOUNT
}
