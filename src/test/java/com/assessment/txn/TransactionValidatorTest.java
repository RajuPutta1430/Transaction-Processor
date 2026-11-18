package com.assessment.txn;

import static org.testng.Assert.*;

import com.assessment.txn.model.RawTransaction;
import com.assessment.txn.validation.TransactionValidator;
import com.assessment.txn.validation.ValidationResult;
import java.math.BigDecimal;
import org.testng.annotations.Test;

public class TransactionValidatorTest {

    private final TransactionValidator validator = new TransactionValidator();

    @Test
    public void acceptsAWellFormedRecordAndNormalisesTheAmount() {
        ValidationResult r = validator.validate(TestData.credit("TXN-1", 1, "ACC-1", "100"));
        assertTrue(r.isValid());
        assertEquals(r.request().amount(), new BigDecimal("100.00"));
    }

    @Test
    public void rejectsNegativeZeroAndNonNumericAmounts() {
        assertFalse(validator.validate(TestData.debit("TXN-1", 1, "ACC-1", "-10")).isValid());
        assertFalse(validator.validate(TestData.debit("TXN-1", 1, "ACC-1", "0")).isValid());
        assertFalse(validator.validate(TestData.debit("TXN-1", 1, "ACC-1", "abc")).isValid());
        assertFalse(validator.validate(TestData.debit("TXN-1", 1, "ACC-1", "1.005")).isValid());
    }

    @Test
    public void rejectsMissingFieldsUnknownTypeAndBadCurrency() {
        RawTransaction missing = new RawTransaction("t", null, "R", "1", "A", "CREDIT", "5", "USD");
        assertFalse(validator.validate(missing).isValid());
        assertFalse(validator.validate(TestData.raw("T", "R", 1, "A", "TRANSFER", "5")).isValid());
        RawTransaction badCurrency = new RawTransaction("t", "T", "R", "1", "A", "CREDIT", "5", "ZZZ");
        assertFalse(validator.validate(badCurrency).isValid());
    }

    @Test
    public void keepsTheSequenceOfAnInvalidRecordSoItsSlotCanBeReleased() {
        ValidationResult r = validator.validate(TestData.debit("TXN-4", 4, "ACC-2", "-10"));
        assertFalse(r.isValid());
        assertEquals(r.sequence().longValue(), 4L);
    }

    @Test
    public void errorMessagesNeverContainTheOffendingValue() {
        ValidationResult r = validator.validate(TestData.debit("TXN-1", 1, "ACC-1", "SECRET-VALUE"));
        assertFalse(r.errors().toString().contains("SECRET-VALUE"));
    }

    @Test
    public void rejectsIdsWithUnsafeCharacters() {
        // newline / spaces could forge log lines
        assertFalse(validator.validate(TestData.credit("TXN\nFAKE event=PROCESSED", 1, "ACC-1", "5")).isValid());
    }
}
