package fun.nyama.tv;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Delayed transport acknowledgements model seeks which outlive several remote presses. */
public class TvSeekQueueTest {
    private static final class Transport implements TvSeekQueue.Transport {
        final List<Integer> direct = new ArrayList<>(), nativeKeys = new ArrayList<>();
        final ArrayDeque<Runnable> posted = new ArrayDeque<>();
        TvSeekQueue.Completion directPending;
        Runnable nativePending;
        int failures;
        @Override public void direct(int seconds, TvSeekQueue.Completion completion) {
            assertNull("Only one asynchronous seek may run", directPending);
            assertNull("Native fallback must not overlap a direct seek", nativePending);
            direct.add(seconds); directPending = completion;
        }
        @Override public void nativeStep(int seconds, Runnable completion) {
            assertNull(directPending); assertNull(nativePending);
            nativeKeys.add(seconds); nativePending = completion;
        }
        @Override public void post(Runnable task) { posted.add(task); }
        @Override public void unavailable() { failures++; }
        void answer(TvSeekQueue.Result result) {
            TvSeekQueue.Completion callback = directPending;
            assertNotNull(callback); directPending = null; callback.complete(result);
        }
        void nativeDone() {
            Runnable callback = nativePending;
            assertNotNull(callback); nativePending = null; callback.run();
        }
        void drain() { while (!posted.isEmpty()) posted.removeFirst().run(); }
    }

    @Test public void tenRapidPressesCoalesceAfterTheFirstSettledDirectSeekWithoutLosingDistance() {
        Transport transport = new Transport(); TvSeekQueue queue = new TvSeekQueue(transport);
        for (int i = 0; i < 10; i++) queue.request(10);
        assertEquals(Arrays.asList(10), transport.direct);
        transport.answer(TvSeekQueue.Result.DIRECT); transport.drain();
        assertEquals(Arrays.asList(10, 90), transport.direct);
        transport.answer(TvSeekQueue.Result.DIRECT); transport.drain();
        assertNull(transport.directPending); assertTrue(transport.nativeKeys.isEmpty());
        assertEquals(100, transport.direct.stream().mapToInt(Integer::intValue).sum());
    }

    @Test public void crossOriginPathKeepsTenOrderedNativeTenSecondStepsWithOneProbe() {
        Transport transport = new Transport(); TvSeekQueue queue = new TvSeekQueue(transport);
        for (int i = 0; i < 10; i++) queue.request(10);
        transport.answer(TvSeekQueue.Result.INACCESSIBLE);
        assertEquals(1, transport.nativeKeys.size());
        for (int i = 0; i < 10; i++) { transport.nativeDone(); transport.drain(); }
        assertEquals(10, transport.nativeKeys.size());
        assertEquals(100, transport.nativeKeys.stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, transport.direct.size()); assertEquals(0, transport.failures);
    }

    @Test public void oppositeDirectionsStayOrderedAtTheMediaBoundary() {
        Transport transport = new Transport(); TvSeekQueue queue = new TvSeekQueue(transport);
        for (int delta : new int[]{10, 10, 10, -10, -10}) queue.request(delta);
        double position = 95;
        for (int expected : new int[]{10, 20, -20}) {
            assertEquals(expected, (int) transport.direct.get(transport.direct.size() - 1));
            position = Math.max(0, Math.min(100, position + expected));
            transport.answer(TvSeekQueue.Result.DIRECT); transport.drain();
        }
        assertEquals(80, position, 0);
        assertEquals(Arrays.asList(10, 20, -20), transport.direct);
    }

    @Test public void ambiguousAssignmentNeverAlsoSendsNativeSeekOrAutomaticallyRetries() {
        Transport transport = new Transport(); TvSeekQueue queue = new TvSeekQueue(transport);
        queue.request(10); queue.request(10); queue.request(10);
        transport.answer(TvSeekQueue.Result.UNAVAILABLE); transport.drain();
        assertTrue(transport.nativeKeys.isEmpty()); assertEquals(1, transport.direct.size());
        assertEquals(1, transport.failures);
        queue.request(-10);
        assertEquals(Arrays.asList(10, -10), transport.direct);
    }

    @Test public void duplicateProbeCallbackCannotDispatchTheNativeFallbackTwice() {
        Transport transport = new Transport(); TvSeekQueue queue = new TvSeekQueue(transport);
        queue.request(10);
        TvSeekQueue.Completion callback = transport.directPending;
        transport.answer(TvSeekQueue.Result.INACCESSIBLE);
        callback.complete(TvSeekQueue.Result.INACCESSIBLE);
        assertEquals(Arrays.asList(10), transport.nativeKeys);
        transport.nativeDone(); transport.drain();
    }

    @Test public void closingDuringDirectSeekDropsLateCallbacksAndQueuedActions() {
        Transport transport = new Transport(); TvSeekQueue queue = new TvSeekQueue(transport);
        queue.request(10); queue.request(10); queue.close();
        transport.answer(TvSeekQueue.Result.INACCESSIBLE); transport.drain();
        queue.request(10);
        assertEquals(1, transport.direct.size()); assertTrue(transport.nativeKeys.isEmpty());
    }

    @Test public void closingDuringNativeDispatchCannotDrainIntoRetiredSurface() {
        Transport transport = new Transport(); TvSeekQueue queue = new TvSeekQueue(transport);
        queue.request(10); queue.request(-10); transport.answer(TvSeekQueue.Result.INACCESSIBLE);
        queue.close(); transport.nativeDone(); transport.drain();
        assertEquals(Arrays.asList(10), transport.nativeKeys);
    }

    @Test public void changedAccessibilityPreservesAllPreviouslyCoalescedSteps() {
        Transport transport = new Transport(); TvSeekQueue queue = new TvSeekQueue(transport);
        for (int i = 0; i < 5; i++) queue.request(10);
        transport.answer(TvSeekQueue.Result.DIRECT); transport.drain();
        assertEquals(Arrays.asList(10, 40), transport.direct);
        transport.answer(TvSeekQueue.Result.INACCESSIBLE);
        for (int i = 0; i < 4; i++) { transport.nativeDone(); transport.drain(); }
        assertEquals(Arrays.asList(10, 10, 10, 10), transport.nativeKeys);
    }

    @Test public void unrelatedDeltasAreRejectedWithoutTouchingPlayback() {
        Transport transport = new Transport(); TvSeekQueue queue = new TvSeekQueue(transport);
        for (int delta : new int[]{0, 5, -20, 100}) queue.request(delta);
        assertTrue(transport.direct.isEmpty()); assertTrue(transport.nativeKeys.isEmpty());
    }
}
