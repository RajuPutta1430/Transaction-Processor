package com.assessment.txn;

import static org.testng.Assert.*;

import com.assessment.txn.ledger.ApplyResult;
import com.assessment.txn.ledger.BusinessRuleException;
import com.assessment.txn.ledger.InMemoryLedger;
import com.assessment.txn.model.FailureReason;
import com.assessment.txn.model.TransactionRequest;
import com.assessment.txn.validation.TransactionValidator;
import java.math.BigDecimal;
import org.testng.annotations.Test;

public class InMemoryLedgerTest {

    private final TransactionValidator validator = new TransactionValidator();

    private TransactionRequest req(com.assessment.txn.model.RawTransaction raw) {
        return validator.validate(raw).request();
    }

    @Test
    public void applyingTheSameTransactionTwiceChangesTheBalanceOnce() {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionRequest credit = req(TestData.credit("TXN-1", 1, "ACC-1", "100"));
        assertEquals(ledger.apply(credit), ApplyResult.APPLIED);
        assertEquals(ledger.apply(credit), ApplyResult.ALREADY_APPLIED);
        assertEquals(ledger.balanceOf("ACC-1"), new BigDecimal("100.00"));
        assertEquals(ledger.entryCount(), 1);
    }

    @Test
    public void rejectedDebitLeavesNoPartialState() {
        InMemoryLedger ledger = new InMemoryLedger();
        BusinessRuleException e = expectThrows(BusinessRuleException.class,
                () -> ledger.apply(req(TestData.debit("TXN-1", 1, "ACC-1", "50"))));
        assertEquals(e.reason(), FailureReason.INSUFFICIENT_FUNDS);
        assertEquals(ledger.balanceOf("ACC-1"), new BigDecimal("0.00"));
        assertEquals(ledger.entryCount(), 0);
    }
}
