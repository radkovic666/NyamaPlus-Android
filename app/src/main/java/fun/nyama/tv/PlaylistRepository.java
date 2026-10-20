package fun.nyama.tv;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;

public final class PlaylistRepository {
    private PlaylistRepository() {}
    interface Downloader { void download(Context context, String url, File target) throws Exception; }
    static Downloader downloader = HttpUtil::downloadToFile;
    private static java.util.concurrent.FutureTask<List<Channel>> refreshInFlight;
    private static boolean deactivationPending;

    static void awaitDeactivationQuiescence() throws InterruptedException {
        java.util.concurrent.FutureTask<List<Channel>> pending;
        synchronized (PlaylistRepository.class) { deactivationPending = true; pending = refreshInFlight; }
        if (pending != null) try { pending.get(); } catch (java.util.concurrent.ExecutionException ignored) { }
    }
    static synchronized void finishDeactivationRequest() { deactivationPending = false; }

    public static File cacheFile(Context context) { return new File(context.getFilesDir(), "playlist.m3u"); }

    public static List<Channel> refresh(Context context) throws Exception {
        java.util.concurrent.FutureTask<List<Channel>> task;
        synchronized (PlaylistRepository.class) {
            if (deactivationPending) throw new java.io.IOException("deactivation_in_progress");
            if (refreshInFlight == null) {
                Context app = context.getApplicationContext();
                refreshInFlight = new java.util.concurrent.FutureTask<>(() -> refreshOnce(app));
            }
            task = refreshInFlight;
        }
        try {
            task.run(); // FutureTask runs once even when several foreground/background callers join.
            return task.get();
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("Playlist refresh failed");
        } finally {
            synchronized (PlaylistRepository.class) {
                if (task.isDone() && refreshInFlight == task) refreshInFlight = null;
            }
        }
    }

    private static List<Channel> refreshOnce(Context context) throws Exception {
        Config config = new Config(context);
        String url = config.playlistUrl();
        if (url.isEmpty()) return Collections.emptyList();

        File cache = cacheFile(context);
        File candidate = new File(context.getFilesDir(), "playlist.candidate.m3u");
        if (candidate.exists()) candidate.delete();
        try {
            downloader.download(context, url, candidate);
        } catch (Exception e) {
            NetworkDiagnostics.logFailure(context, "playlist download", e);
            throw e;
        }

        List<Channel> channels = M3uParser.parse(candidate);
        if (channels.isEmpty()) {
            candidate.delete();
            throw new IllegalStateException("Playlist downloaded but contains no playable channels");
        }

        synchronized (PlaylistRepository.class) {
            if (!url.equals(config.playlistUrl())) {
                candidate.delete(); throw new java.io.IOException("playlist_configuration_changed");
            }
            replace(candidate, cache);
            config.setPlaylistLastRefresh(System.currentTimeMillis());
        }
        DeviceLinking.playlistRefreshed();
        return channels;
    }

    public static synchronized List<Channel> loadCached(Context context) throws Exception {
        try {
            File cache = cacheFile(context);
            if (cache.exists() || new File(cache.getPath() + ".bak").exists())
                try (java.io.InputStream recovered = new android.util.AtomicFile(cache).openRead()) { }
            return M3uParser.parse(cache);
        } catch (Exception e) {
            NetworkDiagnostics.logFailure(context, "cached playlist parse", e);
            throw e;
        }
    }

    private static synchronized void replace(File source, File target) throws Exception {
        android.util.AtomicFile atomic = new android.util.AtomicFile(target);
        FileOutputStream out = null;
        try (FileInputStream in = new FileInputStream(source)) {
            out = atomic.startWrite();
            byte[] buffer = new byte[64 * 1024]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            atomic.finishWrite(out); out = null;
            source.delete();
        } catch (Exception failure) {
            if (out != null) atomic.failWrite(out);
            throw failure;
        }
    }
}
