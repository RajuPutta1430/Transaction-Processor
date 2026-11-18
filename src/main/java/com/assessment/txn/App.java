package com.assessment.txn;

import com.assessment.txn.fault.FailureInjector;
import com.assessment.txn.fault.ScriptedFailureInjector;
import com.assessment.txn.ledger.InMemoryLedger;
import com.assessment.txn.ordering.SequenceScope;
import com.assessment.txn.processing.ProcessorConfig;
import com.assessment.txn.processing.TransactionProcessor;
import com.assessment.txn.source.CsvTransactionSource;
import java.nio.file.Path;

/**
 * Usage: App [inputFile] [--scope=GLOBAL|PER_ACCOUNT] [--no-faults]
 *
 * By default a transient failure is simulated for TXN-2005 (it fails once, then succeeds) so the
 * retry path is visible in the log and report.
 */
public final class App {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tH:%1$tM:%1$tS.%1$tL %4$-7s %5$s%n");

        Path input = Path.of("data/sample-input.csv");
        SequenceScope scope = SequenceScope.GLOBAL;
        boolean faults = true;
        for (String arg : args) {
            if (arg.startsWith("--scope=")) {
                scope = SequenceScope.valueOf(arg.substring("--scope=".length()).toUpperCase());
            } else if (arg.equals("--no-faults")) {
                faults = false;
            } else {
                input = Path.of(arg);
            }
        }

        FailureInjector injector = faults
                ? new ScriptedFailureInjector().failBefore("TXN-2005", 1)
                : FailureInjector.NONE;
        ProcessorConfig defaults = ProcessorConfig.defaults();
        ProcessorConfig config = new ProcessorConfig(
                defaults.maxAttempts(), defaults.baseBackoffMillis(), scope, defaults.firstSequence());

        TransactionProcessor processor = new TransactionProcessor(config, new InMemoryLedger(), injector);
        processor.processAll(new CsvTransactionSource(input));

        System.out.println();
        System.out.println(processor.report().toText());
    }

    private App() {
    }
}
