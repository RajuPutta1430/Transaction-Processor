package com.assessment.txn.source;

import com.assessment.txn.model.RawTransaction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a CSV with a header row. Columns are looked up by header name, so column order is
 * irrelevant. Short or odd rows never throw here: missing cells become null and the validator
 * rejects the record, which keeps one bad line from stopping the whole batch.
 */
public final class CsvTransactionSource implements TransactionSource {

    private final Path file;

    public CsvTransactionSource(Path file) {
        this.file = file;
    }

    @Override
    public List<RawTransaction> readAll() throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Map<String, Integer> columns = null;
        List<RawTransaction> records = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cells = line.split(",", -1);
            if (columns == null) {
                columns = headerIndex(cells);
                continue;
            }
            String ref = "line " + (i + 1);
            records.add(new RawTransaction(
                    ref,
                    cell(cells, columns, "transactionId"),
                    cell(cells, columns, "requestId"),
                    cell(cells, columns, "sequenceNumber"),
                    cell(cells, columns, "accountId"),
                    cell(cells, columns, "transactionType"),
                    cell(cells, columns, "amount"),
                    cell(cells, columns, "currency")));
        }
        if (columns == null) {
            throw new IOException("Input file has no header row");
        }
        return records;
    }

    private static Map<String, Integer> headerIndex(String[] header) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            index.put(header[i].trim(), i);
        }
        return index;
    }

    private static String cell(String[] cells, Map<String, Integer> columns, String name) {
        Integer position = columns.get(name);
        if (position == null || position >= cells.length) {
            return null;
        }
        return cells[position].trim();
    }
}
