package com.assessment.txn;

import static org.testng.Assert.*;

import com.assessment.txn.fault.ScriptedFailureInjector;
import com.assessment.txn.ledger.InMemoryLedger;
import com.assessment.txn.model.TransactionStatus;
import com.assessment.txn.ordering.SequenceScope;
import com.assessment.txn.processing.ProcessorConfig;
import com.assessment.txn.processing.TransactionProcessor;
import com.assessment.txn.report.ProcessingReport;
import com.assessment.txn.source.CsvTransactionSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The exact dataset from the assessment's "L2 Suggested Test Data" table, run from a CSV file. */
public class SampleDatasetTest {

    private Path tmp;

    @BeforeMethod
    public void createTempDir() throws Exception {
        tmp = Files.createTempDirectory("txn-test");
    }

    @AfterMethod(alwaysRun = true)
    public void deleteTempDir() throws Exception {
        try (var files = Files.walk(tmp)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
    }

    @Test
    public void assessmentDatasetProducesTheExpectedOutcomes() throws Exception {
        Path file = tmp.resolve("input.csv");
        Files.writeString(file, String.join("\n",
                "transactionId,requestId,sequenceNumber,accountId,transactionType,amount,currency",
                "TXN-2002,REQ-2,2,ACC-201,DEBIT,40,USD",
                "TXN-2001,REQ-1,1,ACC-201,CREDIT,100,USD",
                "TXN-2002,REQ-2-DUP,2,ACC-201,DEBIT,40,USD",
                "TXN-2003,REQ-3,3,ACC-201,CREDIT,25,USD",
                "TXN-2004,REQ-4,4,ACC-202,DEBIT,-10,USD",
                "TXN-2005,REQ-5,5,ACC-203,CREDIT,500,USD"));

        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = new TransactionProcessor(
                new ProcessorConfig(3, 0, SequenceScope.GLOBAL, 1), ledger,
                new ScriptedFailureInjector().failBefore("TXN-2005", 1));

        p.processAll(new CsvTransactionSource(file));

        ProcessingReport r = p.report();
        assertEquals(r.inputsReceived(), 6);
        assertEquals(r.count(TransactionStatus.PROCESSED), 4);
        assertEquals(r.count(TransactionStatus.DUPLICATE), 1);
        assertEquals(r.count(TransactionStatus.FAILED), 1);
        assertEquals(r.retryAttempts(), 1);
        assertEquals(ledger.balanceOf("ACC-201"), new BigDecimal("85.00"));
        assertEquals(ledger.balanceOf("ACC-203"), new BigDecimal("500.00"));
        assertEquals(ledger.balanceOf("ACC-202"), new BigDecimal("0.00"));
        assertEquals(p.find("TXN-2005").orElseThrow().attempts(), 2);
        assertTrue(r.sequenceGaps().isEmpty());
    }

    @Test
    public void csvSourceTurnsShortRowsIntoRejectedRecordsInsteadOfCrashing() throws Exception {
        Path file = tmp.resolve("bad.csv");
        Files.writeString(file, String.join("\n",
                "transactionId,requestId,sequenceNumber,accountId,transactionType,amount,currency",
                "TXN-1,REQ-1,1,ACC-1,CREDIT,10,USD",
                "TXN-2,REQ-2",
                "",
                "# a comment line",
                "TXN-3,REQ-3,3,ACC-1,CREDIT,5,USD"));

        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = new TransactionProcessor(new ProcessorConfig(3, 0, SequenceScope.GLOBAL, 1), ledger);
        p.processAll(new CsvTransactionSource(file));

        assertEquals(p.report().rejected().size(), 1);
        assertEquals(p.report().rejected().get(0).sourceRef(), "line 3");
        // TXN-3 waits because slot 2 was unreadable (no sequence could be recovered); it is reported, not lost.
        assertEquals(p.report().count(TransactionStatus.PENDING), 1);
        assertEquals(ledger.balanceOf("ACC-1"), new BigDecimal("10.00"));
    }
}
