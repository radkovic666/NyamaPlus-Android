package fun.nyama.tv;

import java.util.ArrayDeque;

/** One TV seek operation at a time, including the asynchronous provider-focus fallback. */
final class TvSeekQueue {
    enum Result { DIRECT, INACCESSIBLE, UNAVAILABLE }
    interface Completion { void complete(Result result); }
    interface Transport {
        /** Completes only after an accessible HTML5 seek has settled or was definitely unavailable. */
        void direct(int seconds, Completion completion);
        /** Exactly one provider key down/up pair for one intentional ten-second step. */
        void nativeStep(int seconds, Runnable completion);
        void post(Runnable task);
        void unavailable();
        default void idle() { }
    }

    private enum Path { UNKNOWN, DIRECT, NATIVE }
    private final Transport transport;
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private Path path = Path.UNKNOWN;
    private boolean inFlight;
    private boolean closed;
    private long operation;

    TvSeekQueue(Transport transport) { this.transport = transport; }

    void request(int seconds) {
        if (closed || (seconds != -10 && seconds != 10)) return;
        pending.addLast(seconds);
        drain();
    }

    private void drain() {
        if (closed || inFlight) return;
        if (pending.isEmpty()) { transport.idle(); return; }
        inFlight = true;
        long token = ++operation;
        int seconds = pending.removeFirst();
        // Keep direction changes in order, including clamping at either end of the video.
        // A cross-origin shortcut still needs one real key pair per intentional press.
        if (path == Path.DIRECT) {
            while (!pending.isEmpty() && Integer.signum(pending.peekFirst()) == Integer.signum(seconds))
                seconds += pending.removeFirst();
        }
        final int amount = seconds;
        if (path == Path.NATIVE) {
            nativeStep(amount, token);
        } else {
            final boolean[] answered = { false };
            transport.direct(amount, result -> {
                if (!active(token) || answered[0]) return;
                answered[0] = true;
                if (result == Result.INACCESSIBLE) {
                    path = Path.NATIVE;
                    int step = Integer.signum(amount) * 10;
                    for (int remaining = Math.abs(amount) - 10; remaining > 0; remaining -= 10)
                        pending.addFirst(step);
                    nativeStep(step, token);
                } else if (result == Result.DIRECT) {
                    path = Path.DIRECT;
                    complete(token);
                } else {
                    // An ambiguous/failed direct assignment must never also send a native seek.
                    pending.clear();
                    inFlight = false;
                    transport.unavailable();
                }
            });
        }
    }

    private void nativeStep(int seconds, long token) {
        transport.nativeStep(seconds, () -> complete(token));
    }

    private boolean active(long token) { return !closed && inFlight && operation == token; }

    private void complete(long token) {
        if (!active(token)) return;
        inFlight = false;
        // Posting avoids recursive callbacks and lets normal remote/UI work run between commands.
        transport.post(this::drain);
    }

    void close() {
        closed = true;
        operation++;
        inFlight = false;
        pending.clear();
    }
}
