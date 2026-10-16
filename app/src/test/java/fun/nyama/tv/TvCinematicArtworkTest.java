package fun.nyama.tv;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Synthetic artwork only: verifies bounded requests and actual image-layer transitions without HTTP. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TvCinematicArtworkTest {
    private static final String FIRST = "https://static.tvmaze.com/uploads/images/original_untouched/1/100.jpg";
    private static final String SECOND = "https://static.tvmaze.com/uploads/images/original_untouched/1/101.jpg";
    private final List<TvArtworkLoader> loaders = new ArrayList<>();
    private final List<TvCinematicBackground> backgrounds = new ArrayList<>();

    @Before public void before() throws Exception { clearCaches(); }
    @After public void after() throws Exception {
        for (TvCinematicBackground background : backgrounds) background.release();
        for (TvArtworkLoader loader : loaders) loader.release();
        clearCaches();
    }

    private static void clearCaches() throws Exception {
        for (String name : new String[]{"MEMORY", "FAILURES"}) {
            Field field = TvArtworkLoader.class.getDeclaredField(name);
            field.setAccessible(true);
            ((LruCache<?, ?>) field.get(null)).evictAll();
        }
    }

    private TvArtworkLoader loader(TvArtworkLoader.Fetch fetch) {
        TvArtworkLoader loader = new TvArtworkLoader(RuntimeEnvironment.getApplication(), fetch);
        loaders.add(loader);
        return loader;
    }

    private static void awaitWorker(TvArtworkLoader loader) throws Exception {
        Field field = TvArtworkLoader.class.getDeclaredField("current");
        field.setAccessible(true);
        Future<?> current = (Future<?>) field.get(loader);
        if (current != null) current.get(2, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private TvCinematicBackground background(TvArtworkLoader main, TvArtworkLoader fallback) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TvCinematicBackground background = new TvCinematicBackground(activity, main, fallback);
        backgrounds.add(background);
        activity.setContentView(background);
        background.layout(0, 0, 1920, 1080);
        return background;
    }

    private static ImageView[] images(TvCinematicBackground background) throws Exception {
        Field field = TvCinematicBackground.class.getDeclaredField("images");
        field.setAccessible(true);
        return (ImageView[]) field.get(background);
    }

    @Test public void fullResolutionAndPortraitRequestsUseTheirOwnBoundedCache() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        TvArtworkLoader loader = loader((url, portrait, width, height) -> {
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper());
            assertTrue(width <= 1920 && height <= 1080);
            fetches.incrementAndGet();
            return Bitmap.createBitmap(64, 36, Bitmap.Config.RGB_565);
        });
        loader.load(FIRST, false, bitmap -> callbacks.incrementAndGet()); awaitWorker(loader);
        loader.load(FIRST, false, bitmap -> callbacks.incrementAndGet()); awaitWorker(loader);
        loader.load(FIRST, true, bitmap -> callbacks.incrementAndGet()); awaitWorker(loader);
        assertEquals(2, fetches.get());
        assertEquals(3, callbacks.get());
        Field field = TvArtworkLoader.class.getDeclaredField("MEMORY"); field.setAccessible(true);
        assertTrue(((LruCache<?, ?>) field.get(null)).maxSize() <= TvArtworkLoader.NORMAL_CACHE_BYTES);
    }

    @Test public void fastFocusChangeIgnoresStaleResultEvenWhenNetworkIgnoresCancellation() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<String> delivered = new AtomicReference<>();
        TvArtworkLoader loader = loader((url, portrait, width, height) -> {
            if (url.equals(FIRST)) {
                started.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { /* Uncooperative mock network. */ }
            }
            return Bitmap.createBitmap(32, 18, Bitmap.Config.RGB_565);
        });
        loader.load(FIRST, false, bitmap -> delivered.set(FIRST));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        loader.load(SECOND, false, bitmap -> delivered.set(SECOND));
        release.countDown(); awaitWorker(loader);
        assertEquals(SECOND, delivered.get());
    }

    @Test public void secureArtworkRejectsLocalAndCredentialAddresses() {
        for (String unsafe : new String[]{"http://static.tvmaze.com/a.jpg", "file:///tmp/a.jpg", "content://images/a",
                "https://user:secret@example.com/a", "https://localhost/a", "https://localhost./a", "https://127.0.0.1/a",
                "https://10.0.0.1/a", "https://[::1]/a", "https://host.local/a", "https://example.com:444/a"}) {
            assertEquals(unsafe, "", TvArtworkLoader.safeUrl(unsafe));
        }
        assertEquals(FIRST, TvArtworkLoader.safeUrl(FIRST));
        assertEquals("https://pukanki.fun/posters/tt0944947.jpg", TvArtworkLoader.safeUrl("https://pukanki.fun/posters/tt0944947.jpg"));
    }

    @Test public void redirectsKeepHttpsValidationAndRejectUnsafeTargets() {
        assertEquals("https://static.tvmaze.com/new.jpg", TvArtworkLoader.redirectUrl(FIRST, "/new.jpg"));
        assertEquals("", TvArtworkLoader.redirectUrl(FIRST, "http://static.tvmaze.com/a.jpg"));
        assertEquals("", TvArtworkLoader.redirectUrl(FIRST, "https://127.0.0.1/private.jpg"));
        assertEquals("", TvArtworkLoader.redirectUrl(FIRST, "https://user:secret@example.com/a.jpg"));
        assertEquals("", TvArtworkLoader.redirectUrl(FIRST, null));
    }

    @Test public void displaySizingBounds4kAndOlderBoxesWithoutDistortingAspect() {
        assertArrayEquals(new int[]{1920, 1080}, TvArtworkLoader.targetSize(3840, 2160, false));
        assertArrayEquals(new int[]{1280, 720}, TvArtworkLoader.targetSize(3840, 2160, true));
        assertArrayEquals(new int[]{1280, 720}, TvArtworkLoader.targetSize(1280, 720, false));
        assertArrayEquals(new int[]{1920, 1080}, TvArtworkLoader.targetSize(0, 0, false));
        int sample = TvArtworkLoader.sampleSize(16000, 8000, 1920, 1080);
        assertTrue((long) ((16000 + sample - 1) / sample) * ((8000 + sample - 1) / sample) <= 2L * 1920 * 1080);
    }

    @Test public void lowResolutionThumbnailIsNotEnlargedIntoFullscreenArtwork() {
        Bitmap thumbnail = Bitmap.createBitmap(320, 180, Bitmap.Config.RGB_565);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(thumbnail.compress(Bitmap.CompressFormat.PNG, 100, bytes));
        assertNull(TvArtworkLoader.decode(bytes.toByteArray(), false, 1920, 1080));
        // A portrait poster has its own intentional blur treatment and does not claim HD still detail.
        assertNotNull(TvArtworkLoader.decode(bytes.toByteArray(), true, 1920, 1080));
        thumbnail.recycle();
    }

    @Test public void failedOptionalArtworkIsNotDownloadedOnEveryFocusMovement() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        TvArtworkLoader loader = loader((url, portrait, width, height) -> { fetches.incrementAndGet(); return null; });
        loader.load(FIRST, false, bitmap -> assertNull(bitmap)); awaitWorker(loader);
        loader.load(FIRST, false, bitmap -> assertNull(bitmap)); awaitWorker(loader);
        assertEquals(1, fetches.get());
    }

    @Test public void backdropIsNonFocusableCenterCropAndUses350msImageCrossfade() throws Exception {
        TvArtworkLoader main = loader((url, portrait, width, height) -> Bitmap.createBitmap(64, 36, Bitmap.Config.RGB_565));
        TvCinematicBackground background = background(main, loader((url, portrait, width, height) -> null));
        assertFalse(background.isFocusable());
        assertFalse(background.isClickable());
        background.show(FIRST, false); awaitWorker(main);
        ImageView[] images = images(background);
        assertEquals(ImageView.ScaleType.CENTER_CROP, images[0].getScaleType());
        assertEquals(ImageView.ScaleType.CENTER_CROP, images[1].getScaleType());
        assertEquals(350, images[1].animate().getDuration());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        // A cached result delivers synchronously, so inspect both layers before advancing frames.
        main.load(SECOND, false, bitmap -> assertNotNull(bitmap)); awaitWorker(main);
        background.show(SECOND, false);
        assertNotNull(images[0].getDrawable());
        assertNotNull(images[1].getDrawable()); // Old stays intact while the new layer fades over it.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(SECOND, background.currentRequestedUrl());
        assertNull(images[1].getDrawable());
        assertNotNull(images[0].getDrawable());
    }

    @Test public void missingEpisodeUsesSeriesPosterAndReleaseDropsAllViewReferences() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        TvArtworkLoader main = loader((url, portrait, width, height) -> {
            fetches.incrementAndGet(); return Bitmap.createBitmap(64, 36, Bitmap.Config.RGB_565);
        });
        TvArtworkLoader fallback = loader((url, portrait, width, height) -> {
            fetches.incrementAndGet(); return Bitmap.createBitmap(36, 64, Bitmap.Config.RGB_565);
        });
        TvCinematicBackground background = background(main, fallback);
        String poster = "https://pukanki.fun/posters/tt0944947.jpg";
        background.setFallback(poster, true); background.showFallback(); awaitWorker(fallback);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        background.show(FIRST, false); awaitWorker(main);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        background.show("", false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(poster, background.currentRequestedUrl());
        assertEquals(2, fetches.get()); // The fallback was preloaded once; no duplicate poster request.
        background.release();
        for (ImageView image : images(background)) assertNull(image.getDrawable());
    }

    @Test public void releasePreventsLateNetworkResultsFromUpdatingScreen() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        TvArtworkLoader loader = loader((url, portrait, width, height) -> {
            started.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException ignored) { /* Cancelled. */ }
            return Bitmap.createBitmap(32, 18, Bitmap.Config.RGB_565);
        });
        loader.load(FIRST, false, bitmap -> callbacks.incrementAndGet());
        assertTrue(started.await(2, TimeUnit.SECONDS));
        loader.release();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, callbacks.get());
    }
}
