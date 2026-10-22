package fun.nyama.tv;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Separate, bounded TV backdrop pipeline. It never borrows the small episode-thumbnail cache. */
final class TvArtworkLoader {
    interface Callback { void loaded(Bitmap bitmap); }
    interface Fetch { Bitmap load(String url, boolean portrait, int width, int height) throws Exception; }

    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    static final int NORMAL_CACHE_BYTES = 24 * 1024 * 1024;
    static final int LOW_MEMORY_CACHE_BYTES = 10 * 1024 * 1024;
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(NORMAL_CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private static final LruCache<String, Long> FAILURES = new LruCache<>(64);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Fetch fetch;
    private final int width;
    private final int height;
    private long generation;
    private Future<?> current;
    private boolean closed;

    TvArtworkLoader(Context context) { this(context, TvArtworkLoader::fetchBitmap); }

    TvArtworkLoader(Context context, Fetch fetch) { this(context, fetch, false); }

    static TvArtworkLoader mobilePoster(Context context) {
        return mobilePoster(context, TvArtworkLoader::fetchBitmap);
    }

    static TvArtworkLoader mobilePoster(Context context, Fetch fetch) {
        return new TvArtworkLoader(context, fetch, true);
    }

    private TvArtworkLoader(Context context, Fetch fetch, boolean mobilePoster) {
        this.fetch = fetch;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        boolean lowMemory = manager != null && (manager.isLowRamDevice() || manager.getMemoryClass() <= 128);
        DisplayMetrics display = context.getResources().getDisplayMetrics();
        // Phone movie backdrops use the same bounded blurred poster across rotations.
        // TV keeps its existing display-dependent target sizes and cache keys.
        int[] size = mobilePoster ? new int[]{360, 640} : targetSize(display.widthPixels, display.heightPixels, lowMemory);
        width = size[0]; height = size[1];
        if (lowMemory) MEMORY.resize(LOW_MEMORY_CACHE_BYTES);
        worker.allowCoreThreadTimeOut(true);
    }

    /** Replaces this screen's pending request. Failed images retain the currently displayed artwork. */
    synchronized void load(String address, boolean portrait, Callback callback) {
        cancelPending();
        if (closed) return;
        String url = safeUrl(address);
        if (url.isEmpty()) { callback.loaded(null); return; }
        long request = generation;
        String key = cacheKey(url, portrait, width, height);
        Bitmap cached = MEMORY.get(key);
        if (cached != null && !cached.isRecycled()) { callback.loaded(cached); return; }
        Long failed = FAILURES.get(key);
        if (failed != null && SystemClock.elapsedRealtime() - failed < 60_000L) { callback.loaded(null); return; }
        current = worker.submit(() -> {
            Bitmap bitmap = null;
            try { bitmap = fetch.load(url, portrait, width, height); } catch (Exception ignored) { /* Optional artwork. */ }
            if (Thread.currentThread().isInterrupted()) return;
            Bitmap result = bitmap;
            main.post(() -> deliver(request, key, result, callback));
        });
    }

    private synchronized void deliver(long request, String key, Bitmap bitmap, Callback callback) {
        if (closed || request != generation) return;
        if (bitmap != null && !bitmap.isRecycled()) MEMORY.put(key, bitmap);
        else FAILURES.put(key, SystemClock.elapsedRealtime());
        current = null;
        callback.loaded(bitmap);
    }

    synchronized void cancelPending() {
        generation++;
        if (current != null) current.cancel(true);
        current = null;
        worker.getQueue().clear();
        worker.purge();
    }

    synchronized void release() {
        closed = true;
        cancelPending();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    static String cacheKey(String url, boolean portrait, int width, int height) {
        return (portrait ? "poster|" : "still|") + width + "x" + height + "|" + url;
    }

    static int[] targetSize(int displayWidth, int displayHeight, boolean lowMemory) {
        int maxWidth = lowMemory ? 1280 : 1920;
        int maxHeight = lowMemory ? 720 : 1080;
        int w = displayWidth > 0 ? displayWidth : maxWidth;
        int h = displayHeight > 0 ? displayHeight : maxHeight;
        double scale = Math.min(1d, Math.min((double) maxWidth / w, (double) maxHeight / h));
        return new int[]{Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale))};
    }

    /** Metadata may reference public HTTPS images, never local files, credentials or private hosts. */
    static String safeUrl(String address) {
        try {
            URI uri = new URI(address == null ? "" : address.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return "";
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (!host.contains(".") || host.endsWith(".local") || host.endsWith(".localhost")
                    || host.equals("localhost") || host.matches("[0-9.]+") || host.contains(":")) return "";
            return uri.toASCIIString();
        } catch (Exception invalid) { return ""; }
    }

    static String redirectUrl(String address, String location) {
        if (location == null || safeUrl(address).isEmpty()) return "";
        try { return safeUrl(new URL(new URL(address), location).toExternalForm()); }
        catch (Exception invalid) { return ""; }
    }

    private static Bitmap fetchBitmap(String address, boolean portrait, int width, int height) throws Exception {
        String url = safeUrl(address);
        for (int redirects = 0; redirects <= 3 && !url.isEmpty(); redirects++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(4500);
            connection.setReadTimeout(4500);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "image/*");
            connection.setRequestProperty("User-Agent", "NyamaPlusAndroidTV/artwork");
            try {
                if (Thread.currentThread().isInterrupted()) return null;
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    url = redirectUrl(url, location);
                    continue;
                }
                String type = connection.getContentType();
                if (status != 200 || connection.getContentLengthLong() > MAX_IMAGE_BYTES
                        || (type != null && !type.toLowerCase(Locale.ROOT).startsWith("image/"))) return null;
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int count;
                    while ((count = input.read(chunk)) != -1) {
                        if (Thread.currentThread().isInterrupted() || body.size() + count > MAX_IMAGE_BYTES) return null;
                        body.write(chunk, 0, count);
                    }
                }
                return decode(body.toByteArray(), portrait, width, height);
            } finally { connection.disconnect(); }
        }
        return null;
    }

    static Bitmap decode(byte[] bytes, boolean portrait, int width, int height) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 16384 || bounds.outHeight > 16384
                || (long) bounds.outWidth * bounds.outHeight > 128L * 1024L * 1024L) return null;
        // Old metadata caches can contain only tiny episode thumbnails. Use the poster fallback
        // rather than enlarging those into a purported fullscreen still.
        if (!portrait && (bounds.outWidth < 640 || bounds.outHeight < 360)) return null;
        // Portrait posters are deliberately softly blurred; real episode stills retain TV-sized detail.
        int targetWidth = portrait ? Math.min(width, 360) : width;
        int targetHeight = portrait ? Math.min(height, 640) : height;
        BitmapFactory.Options sampled = new BitmapFactory.Options();
        sampled.inPreferredConfig = Bitmap.Config.RGB_565;
        sampled.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight);
        Bitmap result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, sampled);
        if (result == null || Thread.currentThread().isInterrupted()) return null;
        double scale = Math.min(1d, Math.min((double) targetWidth / result.getWidth(), (double) targetHeight / result.getHeight()));
        if (scale < 1d) {
            Bitmap smaller = Bitmap.createScaledBitmap(result, Math.max(1, (int) (result.getWidth() * scale)),
                    Math.max(1, (int) (result.getHeight() * scale)), true);
            if (smaller != result) result.recycle();
            result = smaller;
        }
        return portrait ? blur(result) : result;
    }

    static int sampleSize(int width, int height, int targetWidth, int targetHeight) {
        targetWidth = Math.max(1, targetWidth);
        targetHeight = Math.max(1, targetHeight);
        int sample = 1;
        long maxPixels = Math.max(1L, (long) targetWidth * targetHeight * 2L);
        while ((width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight)
                || (long) ((width + sample - 1) / sample) * ((height + sample - 1) / sample) > maxPixels) sample *= 2;
        return sample;
    }

    /** Two inexpensive separable box passes, computed on the artwork worker at <=360x640. */
    private static Bitmap blur(Bitmap input) {
        int width = input.getWidth(), height = input.getHeight();
        int[] source = new int[width * height];
        int[] scratch = new int[source.length];
        input.getPixels(source, 0, width, 0, 0, width, height);
        for (int pass = 0; pass < 2; pass++) {
            blurPass(source, scratch, width, height, true, 8);
            blurPass(scratch, source, width, height, false, 8);
        }
        Bitmap output = Bitmap.createBitmap(source, width, height, Bitmap.Config.RGB_565);
        input.recycle();
        return output;
    }

    private static void blurPass(int[] source, int[] dest, int width, int height, boolean horizontal, int radius) {
        int lines = horizontal ? height : width;
        int length = horizontal ? width : height;
        int window = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            if (Thread.currentThread().isInterrupted()) return;
            int red = 0, green = 0, blue = 0;
            for (int n = -radius; n <= radius; n++) {
                int position = Math.max(0, Math.min(length - 1, n));
                int pixel = source[horizontal ? line * width + position : position * width + line];
                red += (pixel >> 16) & 255; green += (pixel >> 8) & 255; blue += pixel & 255;
            }
            for (int position = 0; position < length; position++) {
                dest[horizontal ? line * width + position : position * width + line] = 0xff000000
                        | ((red / window) << 16) | ((green / window) << 8) | (blue / window);
                int oldPosition = Math.max(0, position - radius);
                int newPosition = Math.min(length - 1, position + radius + 1);
                int old = source[horizontal ? line * width + oldPosition : oldPosition * width + line];
                int next = source[horizontal ? line * width + newPosition : newPosition * width + line];
                red += ((next >> 16) & 255) - ((old >> 16) & 255);
                green += ((next >> 8) & 255) - ((old >> 8) & 255);
                blue += (next & 255) - (old & 255);
            }
        }
    }
}
