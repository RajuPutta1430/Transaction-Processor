package com.assessment.txn;

import static com.assessment.txn.TestData.credit;
import static org.testng.Assert.*;

import com.assessment.txn.ledger.InMemoryLedger;
import com.assessment.txn.model.RawTransaction;
import com.assessment.txn.model.TransactionStatus;
import com.assessment.txn.ordering.SequenceScope;
import com.assessment.txn.processing.ProcessorConfig;
import com.assessment.txn.processing.TransactionProcessor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;

public class ConcurrencyTest {

    @Test
    public void theSameTransactionSubmittedByManyThreadsIsAppliedExactlyOnce() throws Exception {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = new TransactionProcessor(new ProcessorConfig(3, 0, SequenceScope.GLOBAL, 1), ledger);
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransactionStatus>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            RawTransaction raw = TestData.raw("TXN-1", "REQ-" + i, 1, "ACC-1", "CREDIT", "100");
            results.add(pool.submit(() -> {
                start.await();
                return p.submit(raw);
            }));
        }
        start.countDown();

        int processed = 0;
        int duplicates = 0;
        for (Future<TransactionStatus> f : results) {
            TransactionStatus s = f.get(10, TimeUnit.SECONDS);
            if (s == TransactionStatus.PROCESSED) {
                processed++;
            } else if (s == TransactionStatus.DUPLICATE) {
                duplicates++;
            }
        }
        pool.shutdownNow();

        assertEquals(processed, 1);
        assertEquals(duplicates, threads - 1);
        assertEquals(ledger.balanceOf("ACC-1"), new BigDecimal("100.00"));
        assertEquals(ledger.entryCount(), 1);
    }

    @Test
    public void shuffledSequencesSubmittedConcurrentlyAllEndUpProcessedInOrder() throws Exception {
        InMemoryLedger ledger = new InMemoryLedger();
        TransactionProcessor p = new TransactionProcessor(new ProcessorConfig(3, 0, SequenceScope.GLOBAL, 1), ledger);
        int total = 200;
        List<Integer> order = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new java.util.Random(42));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int seq : order) {
            futures.add(pool.submit(() -> p.submit(credit("TXN-" + seq, seq, "ACC-1", "1"))));
        }
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(ledger.balanceOf("ACC-1"), new BigDecimal("200.00"));
        assertEquals(p.report().count(TransactionStatus.PROCESSED), total);
        assertTrue(p.report().sequenceGaps().isEmpty());
        List<String> applied = ledger.appliedOrder();
        for (int i = 0; i < total; i++) {
            assertEquals(applied.get(i), "TXN-" + (i + 1));
        }
    }
}
