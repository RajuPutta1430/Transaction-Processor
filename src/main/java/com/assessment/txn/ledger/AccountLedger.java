package com.assessment.txn.ledger;

import com.assessment.txn.model.TransactionRequest;
import java.math.BigDecimal;
import java.util.Map;

/**
 * The downstream side effect we must never duplicate. Implementations must make {@link #apply}
 * atomic (balance and ledger entry change together) and idempotent per transactionId. In
 * production this would be a database transaction with a unique constraint on transactionId.
 */
public interface AccountLedger {

    ApplyResult apply(TransactionRequest request);

    BigDecimal balanceOf(String accountId);

    Map<String, BigDecimal> balances();
}
