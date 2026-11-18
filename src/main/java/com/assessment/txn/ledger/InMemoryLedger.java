package com.assessment.txn.ledger;

import com.assessment.txn.model.FailureReason;
import com.assessment.txn.model.TransactionRequest;
import com.assessment.txn.model.TransactionType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory ledger. The lock is per account, and the balance update, the currency binding and the
 * ledger entry all change inside one critical section, so no caller can observe a half-applied
 * transaction. The entry map keyed by transactionId is a second line of defence: even if the
 * processor lost track of a transaction (for example it crashed after applying), applying it
 * again is a no-op.
 */
public final class InMemoryLedger implements AccountLedger {

    public record LedgerEntry(String transactionId, String accountId, TransactionType type,
                              BigDecimal amount, BigDecimal balanceAfter) {
    }

    private static final class Account {
        BigDecimal balance = BigDecimal.ZERO.setScale(2);
        String currency;
    }

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, LedgerEntry> entries = new ConcurrentHashMap<>();
    private final List<String> appliedOrder = Collections.synchronizedList(new ArrayList<>());

    @Override
    public ApplyResult apply(TransactionRequest request) {
        Account account = accounts.computeIfAbsent(request.accountId(), k -> new Account());
        synchronized (account) {
            if (entries.containsKey(request.transactionId())) {
                return ApplyResult.ALREADY_APPLIED;
            }
            if (account.currency != null && !account.currency.equals(request.currency())) {
                throw new BusinessRuleException(FailureReason.CURRENCY_MISMATCH,
                        "currency does not match the account currency");
            }
            BigDecimal next = request.type() == TransactionType.CREDIT
                    ? account.balance.add(request.amount())
                    : account.balance.subtract(request.amount());
            if (next.signum() < 0) {
                throw new BusinessRuleException(FailureReason.INSUFFICIENT_FUNDS, "insufficient funds");
            }
            account.balance = next;
            account.currency = request.currency();
            entries.put(request.transactionId(), new LedgerEntry(request.transactionId(),
                    request.accountId(), request.type(), request.amount(), next));
            appliedOrder.add(request.transactionId());
            return ApplyResult.APPLIED;
        }
    }

    @Override
    public BigDecimal balanceOf(String accountId) {
        Account account = accounts.get(accountId);
        if (account == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        synchronized (account) {
            return account.balance;
        }
    }

    @Override
    public Map<String, BigDecimal> balances() {
        Map<String, BigDecimal> snapshot = new TreeMap<>();
        accounts.forEach((id, account) -> {
            synchronized (account) {
                snapshot.put(id, account.balance);
            }
        });
        return snapshot;
    }

    /** Number of ledger entries; used by tests to prove nothing was applied twice. */
    public int entryCount() {
        return entries.size();
    }

    /** Transaction ids in the order they were applied; used by tests to prove ordering. */
    public List<String> appliedOrder() {
        synchronized (appliedOrder) {
            return new ArrayList<>(appliedOrder);
        }
    }
}
