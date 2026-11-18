package com.assessment.txn;

import static org.testng.Assert.*;

import com.assessment.txn.ordering.SequenceGate;
import com.assessment.txn.ordering.SequenceGate.OfferStatus;
import java.util.List;
import org.testng.annotations.Test;

public class SequenceGateTest {

    @Test
    public void releasesInOrderOnceTheMissingSequenceArrives() {
        SequenceGate<String> gate = new SequenceGate<>(1);
        assertEquals(gate.offer("L", 3, "c").status(), OfferStatus.BUFFERED);
        assertEquals(gate.offer("L", 2, "b").status(), OfferStatus.BUFFERED);
        SequenceGate.OfferResult<String> last = gate.offer("L", 1, "a");
        assertEquals(last.status(), OfferStatus.READY);
        assertEquals(last.ready(), List.of("a", "b", "c"));
    }

    @Test
    public void reportsMissingSequences() {
        SequenceGate<String> gate = new SequenceGate<>(1);
        gate.offer("L", 1, "a");
        gate.offer("L", 4, "d");
        SequenceGate.Gap gap = gate.gaps().get(0);
        assertEquals(gap.waitingFor(), 2L);
        assertEquals(gap.missing(), List.of(2L, 3L));
        assertEquals(gap.buffered(), List.of(4L));
    }

    @Test
    public void skippedSlotsDoNotBlockTheStream() {
        SequenceGate<String> gate = new SequenceGate<>(1);
        gate.offer("L", 3, "c");
        assertTrue(gate.skip("L", 2).isEmpty());          // still waiting for 1
        assertEquals(gate.offer("L", 1, "a").ready(), List.of("a", "c"));
    }

    @Test
    public void lateAndConflictingOffersAreDetected() {
        SequenceGate<String> gate = new SequenceGate<>(1);
        gate.offer("L", 1, "a");
        assertEquals(gate.offer("L", 1, "again").status(), OfferStatus.STALE);
        gate.offer("L", 3, "c");
        assertEquals(gate.offer("L", 3, "other").status(), OfferStatus.CONFLICT);
    }

    @Test
    public void lanesAreIndependent() {
        SequenceGate<String> gate = new SequenceGate<>(1);
        assertEquals(gate.offer("A", 1, "a1").status(), OfferStatus.READY);
        assertEquals(gate.offer("B", 1, "b1").status(), OfferStatus.READY);
    }
}
