package fun.nyama.tv;

import android.app.Application;
import android.graphics.Bitmap;
import android.util.LruCache;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Optional still-image loading uses synthetic bitmaps and never performs HTTP requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TvEpisodeStillLoaderTest {
    private final List<TvEpisodeStillLoader> loaders = new ArrayList<>();

    @Before public void before() throws Exception { clearCaches(); }
    @After public void after() throws Exception {
        for (TvEpisodeStillLoader loader : loaders) loader.close();
        clearCaches();
    }

    private static void clearCaches() throws Exception {
        for (String name : new String[] { "MEMORY", "FAILURES" }) {
            Field field = TvEpisodeStillLoader.class.getDeclaredField(name);
            field.setAccessible(true);
            ((LruCache<?, ?>) field.get(null)).evictAll();
        }
    }

    private TvEpisodeStillLoader loader(TvEpisodeStillLoader.Fetch fetch) {
        TvEpisodeStillLoader loader = new TvEpisodeStillLoader(fetch);
        loaders.add(loader);
        return loader;
    }

    private static void awaitCallbacks(AtomicInteger callbacks, int count) throws Exception {
        for (int i = 0; i < 100 && callbacks.get() < count; i++) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (callbacks.get() < count) Thread.sleep(10);
        }
        assertEquals(count, callbacks.get());
    }

    @Test public void duplicateRowsAndRebindingReuseOneSuccessfulDownload() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Bitmap image = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
        TvEpisodeStillLoader loader = loader(url -> {
            fetches.incrementAndGet(); started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return image;
        });
        String url = "https://static.tvmaze.com/uploads/images/medium_landscape/1/100.jpg";
        loader.load(url, bitmap -> { assertSame(image, bitmap); callbacks.incrementAndGet(); });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        loader.load(url, bitmap -> callbacks.incrementAndGet());
        release.countDown();
        awaitCallbacks(callbacks, 2);
        loader.cancelAll();
        loader.load(url, bitmap -> callbacks.incrementAndGet());
        awaitCallbacks(callbacks, 3);
        assertEquals(1, fetches.get());
    }

    @Test public void seasonChangeCancelsActiveAndQueuedRowsWithoutStaleCallbacks() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch cancelled = new CountDownLatch(2);
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        TvEpisodeStillLoader loader = loader(url -> {
            fetches.incrementAndGet(); started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stopped) { cancelled.countDown(); Thread.currentThread().interrupt(); }
            return null;
        });
        for (int i = 1; i <= 3; i++) loader.load("https://static.tvmaze.com/episode" + i + ".jpg", bitmap -> callbacks.incrementAndGet());
        assertTrue(started.await(2, TimeUnit.SECONDS));
        loader.cancelAll();
        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(2, fetches.get());
        assertEquals(0, callbacks.get());
    }

    @Test public void optionalArtworkRejectsNonHttpsAndCredentialBearingAddresses() {
        assertEquals("", TvEpisodeStillLoader.safeUrl("http://static.tvmaze.com/image.jpg"));
        assertEquals("", TvEpisodeStillLoader.safeUrl("file:///tmp/image.jpg"));
        assertEquals("", TvEpisodeStillLoader.safeUrl("https://user:password@example.invalid/image.jpg"));
        assertEquals("https://static.tvmaze.com/image.jpg", TvEpisodeStillLoader.safeUrl("https://static.tvmaze.com/image.jpg"));
    }
}
