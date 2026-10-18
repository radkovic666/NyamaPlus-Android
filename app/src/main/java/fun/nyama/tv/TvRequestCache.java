package fun.nyama.tv;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

/** Bounded TTL cache plus single-flight requests, executed on the caller's background worker. */
final class TvRequestCache<T> {
    private static final class Entry<T> {
        final T value; final long at;
        Entry(T value, long at) { this.value = value; this.at = at; }
    }
    private final int capacity;
    private final long ttl;
    private final LongSupplier clock;
    private final Map<String, Entry<T>> values = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, FutureTask<T>> pending = new HashMap<>();
    TvRequestCache(int capacity, long ttl, LongSupplier clock) {
        this.capacity = capacity; this.ttl = ttl; this.clock = clock;
    }
    T get(String key, Callable<T> fetch) throws Exception {
        FutureTask<T> task;
        boolean owner;
        synchronized (this) {
            Entry<T> entry = values.get(key);
            if (entry != null && clock.getAsLong() - entry.at < ttl) return entry.value;
            values.remove(key);
            task = pending.get(key);
            owner = task == null;
            if (owner) {
                if (pending.size() >= capacity) throw new IllegalStateException("Catalog requests busy; retry");
                task = new FutureTask<>(fetch); pending.put(key, task);
            }
        }
        try {
            if (owner) task.run();
            T result = task.get();
            if (owner) synchronized (this) {
                values.put(key, new Entry<>(result, clock.getAsLong()));
                while (values.size() > capacity) values.remove(values.keySet().iterator().next());
            }
            return result;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("Catalog request failed");
        } finally {
            if (owner) synchronized (this) { pending.remove(key); }
        }
    }
}
