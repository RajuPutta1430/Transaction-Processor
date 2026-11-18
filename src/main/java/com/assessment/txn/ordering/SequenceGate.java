package com.assessment.txn.ordering;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Option A (sequence-based) out-of-order handling.
 *
 * Items are released strictly in sequence order per lane. An item that arrives early is buffered
 * until every earlier sequence has either been released or explicitly skipped (a rejected record
 * "resolves" its slot so it cannot block the stream forever).
 *
 * This class is a plain data structure with no I/O and no locking; the caller serialises access.
 */
public final class SequenceGate<T> {

    private static final int MAX_REPORTED_MISSING = 50;

    public enum OfferStatus {
        /** The offered item was released (possibly together with earlier buffered items). */
        READY,
        /** The offered item is waiting for an earlier sequence. */
        BUFFERED,
        /** The slot was already consumed or skipped, so the item arrived too late. */
        STALE,
        /** A different item is already buffered under the same sequence number. */
        CONFLICT
    }

    public record OfferResult<T>(OfferStatus status, List<T> ready) {
    }

    public record Gap(String lane, long waitingFor, List<Long> missing, List<Long> buffered) {
    }

    private static final class Lane<T> {
        long next;
        final TreeMap<Long, T> buffered = new TreeMap<>();
        final TreeSet<Long> skipped = new TreeSet<>();

        Lane(long first) {
            this.next = first;
        }
    }

    private final long firstSequence;
    private final Map<String, Lane<T>> lanes = new HashMap<>();

    public SequenceGate(long firstSequence) {
        this.firstSequence = firstSequence;
    }

    public OfferResult<T> offer(String lane, long sequence, T item) {
        Lane<T> l = lane(lane);
        if (sequence < l.next || l.skipped.contains(sequence)) {
            return new OfferResult<>(OfferStatus.STALE, List.of());
        }
        if (l.buffered.containsKey(sequence)) {
            return new OfferResult<>(OfferStatus.CONFLICT, List.of());
        }
        l.buffered.put(sequence, item);
        List<T> ready = drain(l);
        OfferStatus status = l.buffered.containsKey(sequence) ? OfferStatus.BUFFERED : OfferStatus.READY;
        return new OfferResult<>(status, ready);
    }

    /** Marks a sequence as resolved without an item (for example a rejected record). */
    public List<T> skip(String lane, long sequence) {
        Lane<T> l = lane(lane);
        if (sequence < l.next || l.buffered.containsKey(sequence)) {
            return List.of();
        }
        l.skipped.add(sequence);
        return drain(l);
    }

    /** Lanes that still hold buffered items, with the sequence numbers they are waiting for. */
    public List<Gap> gaps() {
        List<Gap> out = new ArrayList<>();
        new TreeMap<>(lanes).forEach((name, l) -> {
            if (l.buffered.isEmpty()) {
                return;
            }
            long highest = l.buffered.lastKey();
            List<Long> missing = new ArrayList<>();
            for (long s = l.next; s < highest && missing.size() < MAX_REPORTED_MISSING; s++) {
                if (!l.buffered.containsKey(s) && !l.skipped.contains(s)) {
                    missing.add(s);
                }
            }
            out.add(new Gap(name, l.next, missing, new ArrayList<>(l.buffered.keySet())));
        });
        return out;
    }

    private Lane<T> lane(String name) {
        return lanes.computeIfAbsent(name, k -> new Lane<>(firstSequence));
    }

    private List<T> drain(Lane<T> l) {
        List<T> ready = new ArrayList<>();
        while (true) {
            if (l.skipped.remove(l.next)) {
                l.next++;
                continue;
            }
            T item = l.buffered.remove(l.next);
            if (item == null) {
                return ready;
            }
            ready.add(item);
            l.next++;
        }
    }
}
