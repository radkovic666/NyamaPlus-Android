package fun.nyama.tv;
import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
public class TvRequestCacheTest {
    @Test public void repeatsReuseCacheAndExpiredOrEvictedPagesRefetch() throws Exception {
        long[] now = {0}; AtomicInteger calls = new AtomicInteger();
        TvRequestCache<Integer> cache = new TvRequestCache<>(2, 100, () -> now[0]);
        assertEquals(Integer.valueOf(1), cache.get("movies|q=a|page=1", calls::incrementAndGet));
        assertEquals(Integer.valueOf(1), cache.get("movies|q=a|page=1", calls::incrementAndGet));
        cache.get("series|q=a|page=1", calls::incrementAndGet); cache.get("movies|q=b|page=2", calls::incrementAndGet);
        assertEquals(Integer.valueOf(4), cache.get("movies|q=a|page=1", calls::incrementAndGet));
        now[0] = 100;
        assertEquals(Integer.valueOf(5), cache.get("movies|q=a|page=1", calls::incrementAndGet));
    }
    @Test public void identicalConcurrentRequestsUseOneFetch() throws Exception {
        TvRequestCache<String> cache = new TvRequestCache<>(4, 1000, () -> 0L);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), second = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(() -> cache.get("same", () -> { calls.incrementAndGet(); entered.countDown(); release.await(5, TimeUnit.SECONDS); return "value"; }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<String> b = pool.submit(() -> { second.countDown(); return cache.get("same", () -> { calls.incrementAndGet(); return "duplicate"; }); });
            assertTrue(second.await(5, TimeUnit.SECONDS)); release.countDown();
            assertEquals("value", a.get(5, TimeUnit.SECONDS)); assertEquals("value", b.get(5, TimeUnit.SECONDS)); assertEquals(1, calls.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test public void failureDoesNotPinAnEmptyOrFailedResult() throws Exception {
        TvRequestCache<String> cache = new TvRequestCache<>(1, 1000, () -> 0L);
        try { cache.get("q", () -> { throw new IOException(); }); fail(); } catch (IOException expected) { }
        assertEquals("recovered", cache.get("q", () -> "recovered"));
    }
}
