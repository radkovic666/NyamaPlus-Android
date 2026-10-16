package fun.nyama.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded optional TV episode thumbnails. Cancellation never updates a recycled episode row. */
final class TvEpisodeStillLoader {
    interface Callback { void loaded(Bitmap bitmap); }
    interface Fetch { Bitmap load(String url) throws Exception; }

    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final long FAILURE_TTL_MS = 5L * 60L * 1000L;
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private static final LruCache<String, Long> FAILURES = new LruCache<>(128);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32));
    private final Map<String, Job> jobs = new HashMap<>();
    private final Fetch fetch;

    TvEpisodeStillLoader() { this(TvEpisodeStillLoader::fetchBitmap); }
    TvEpisodeStillLoader(Fetch fetch) {
        this.fetch = fetch;
        workers.allowCoreThreadTimeOut(true);
    }

    synchronized void load(String address, Callback callback) {
        String url = safeUrl(address);
        if (url.isEmpty() || workers.isShutdown()) return;
        Bitmap cached = MEMORY.get(url);
        if (cached != null) { callback.loaded(cached); return; }
        Long failed = FAILURES.get(url);
        if (failed != null && SystemClock.elapsedRealtime() - failed < FAILURE_TTL_MS) return;
        Job current = jobs.get(url);
        if (current != null) { current.callbacks.add(callback); return; }
        Job job = new Job();
        job.callbacks.add(callback);
        jobs.put(url, job);
        try {
            job.future = workers.submit(() -> {
                Bitmap bitmap = null;
                try { bitmap = fetch.load(url); } catch (Exception ignored) { /* Optional artwork. */ }
                if (job.cancelled || Thread.currentThread().isInterrupted()) return;
                if (bitmap != null) MEMORY.put(url, bitmap);
                else FAILURES.put(url, SystemClock.elapsedRealtime());
                Bitmap result = bitmap;
                main.post(() -> complete(url, job, result));
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            jobs.remove(url);
        }
    }

    private synchronized void complete(String url, Job job, Bitmap bitmap) {
        if (jobs.get(url) != job) return;
        jobs.remove(url);
        if (bitmap != null) for (Callback callback : job.callbacks) callback.loaded(bitmap);
        job.callbacks.clear();
    }

    synchronized void cancelAll() {
        // Remove waiting requests before interrupting workers, so freed workers cannot start them.
        workers.getQueue().clear();
        for (Job job : jobs.values()) {
            job.cancelled = true;
            job.callbacks.clear();
            if (job.future != null) job.future.cancel(true);
        }
        jobs.clear();
        workers.purge();
    }

    synchronized void close() { cancelAll(); workers.shutdownNow(); }

    static String safeUrl(String value) {
        try {
            URL url = new URL(value);
            if ("https".equalsIgnoreCase(url.getProtocol()) && url.getUserInfo() == null
                    && !url.getHost().isEmpty() && (url.getPort() == -1 || url.getPort() == 443)) return url.toExternalForm();
        } catch (Exception ignored) { /* Invalid optional artwork. */ }
        return "";
    }

    private static Bitmap fetchBitmap(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "image/*");
        connection.setRequestProperty("User-Agent", "NyamaPlusAndroidTV/episode-still");
        try {
            if (Thread.currentThread().isInterrupted() || connection.getResponseCode() != 200
                    || connection.getContentLengthLong() > MAX_IMAGE_BYTES) return null;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] chunk = new byte[8192];
                int size;
                while ((size = input.read(chunk)) != -1) {
                    if (Thread.currentThread().isInterrupted() || body.size() + size > MAX_IMAGE_BYTES) return null;
                    body.write(chunk, 0, size);
                }
            }
            byte[] bytes = body.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 8000 || bounds.outHeight > 8000) return null;
            BitmapFactory.Options sampled = new BitmapFactory.Options();
            sampled.inSampleSize = 1;
            while (bounds.outWidth / sampled.inSampleSize > 320 || bounds.outHeight / sampled.inSampleSize > 180) {
                sampled.inSampleSize *= 2;
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, sampled);
        } finally { connection.disconnect(); }
    }

    private static final class Job {
        final List<Callback> callbacks = new ArrayList<>();
        Future<?> future;
        volatile boolean cancelled;
    }
}
