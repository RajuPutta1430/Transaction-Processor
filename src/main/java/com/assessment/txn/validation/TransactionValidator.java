package com.assessment.txn.validation;

import com.assessment.txn.model.RawTransaction;
import com.assessment.txn.model.TransactionRequest;
import com.assessment.txn.model.TransactionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates mandatory fields and formats. Error messages mention field names only, never the
 * offending values, so rejected data cannot leak into logs.
 */
public final class TransactionValidator {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");

    public ValidationResult validate(RawTransaction raw) {
        List<String> errors = new ArrayList<>();

        String transactionId = id("transactionId", raw.transactionId(), errors);
        String requestId = id("requestId", raw.requestId(), errors);
        String accountId = id("accountId", raw.accountId(), errors);
        Long sequence = sequence(raw.sequenceNumber(), errors);
        TransactionType type = type(raw.transactionType(), errors);
        BigDecimal amount = amount(raw.amount(), errors);
        String currency = currency(raw.currency(), errors);

        if (!errors.isEmpty()) {
            return new ValidationResult(null, List.copyOf(errors), raw.sourceRef(),
                    transactionId, requestId, accountId, sequence);
        }
        TransactionRequest request = new TransactionRequest(
                raw.sourceRef(), transactionId, requestId, sequence, accountId, type, amount, currency);
        return new ValidationResult(request, List.of(), raw.sourceRef(),
                transactionId, requestId, accountId, sequence);
    }

    private static String id(String field, String value, List<String> errors) {
        if (isBlank(value)) {
            errors.add(field + " is required");
            return null;
        }
        String trimmed = value.trim();
        if (!ID.matcher(trimmed).matches()) {
            errors.add(field + " has an invalid format");
            return null;
        }
        return trimmed;
    }

    private static Long sequence(String value, List<String> errors) {
        if (isBlank(value)) {
            errors.add("sequenceNumber is required");
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 1) {
                errors.add("sequenceNumber must be at least 1");
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            errors.add("sequenceNumber must be a whole number");
            return null;
        }
    }

    private static TransactionType type(String value, List<String> errors) {
        if (isBlank(value)) {
            errors.add("transactionType is required");
            return null;
        }
        try {
            return TransactionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("transactionType must be one of CREDIT, DEBIT");
            return null;
        }
    }

    private static BigDecimal amount(String value, List<String> errors) {
        if (isBlank(value)) {
            errors.add("amount is required");
            return null;
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            errors.add("amount must be numeric");
            return null;
        }
        if (parsed.signum() <= 0) {
            errors.add("amount must be greater than zero");
            return null;
        }
        if (parsed.stripTrailingZeros().scale() > 2) {
            errors.add("amount supports at most 2 decimal places");
            return null;
        }
        if (parsed.compareTo(MAX_AMOUNT) > 0) {
            errors.add("amount exceeds the maximum allowed");
            return null;
        }
        return parsed.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String currency(String value, List<String> errors) {
        if (isBlank(value)) {
            errors.add("currency is required");
            return null;
        }
        try {
            return Currency.getInstance(value.trim().toUpperCase(Locale.ROOT)).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            errors.add("currency must be a valid ISO 4217 code");
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
