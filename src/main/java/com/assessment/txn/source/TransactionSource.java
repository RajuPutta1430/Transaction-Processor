package com.assessment.txn.source;

import com.assessment.txn.model.RawTransaction;
import java.io.IOException;
import java.util.List;

/**
 * Where transactions come from. The processor never knows about files, queues or HTTP, so this
 * can be swapped for a Kafka/SQS consumer or a DB poller without touching business logic.
 */
public interface TransactionSource {
    List<RawTransaction> readAll() throws IOException;
}
