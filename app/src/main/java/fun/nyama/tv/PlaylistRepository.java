package fun.nyama.tv;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;

public final class PlaylistRepository {
    private PlaylistRepository() {}

    public static File cacheFile(Context context) { return new File(context.getFilesDir(), "playlist.m3u"); }

    public static synchronized List<Channel> refresh(Context context) throws Exception {
        Config config = new Config(context);
        String url = config.playlistUrl();
        if (url.isEmpty()) return Collections.emptyList();

        File cache = cacheFile(context);
        File candidate = new File(context.getFilesDir(), "playlist.candidate.m3u");
        if (candidate.exists()) candidate.delete();
        try {
            HttpUtil.downloadToFile(context, url, candidate);
        } catch (Exception e) {
            NetworkDiagnostics.logFailure(context, "playlist download", e);
            throw e;
        }

        List<Channel> channels = M3uParser.parse(candidate);
        if (channels.isEmpty()) {
            candidate.delete();
            throw new IllegalStateException("Playlist downloaded but contains no playable channels");
        }

        replace(candidate, cache);
        config.setPlaylistLastRefresh(System.currentTimeMillis());
        return channels;
    }

    public static synchronized List<Channel> loadCached(Context context) throws Exception {
        try {
            return M3uParser.parse(cacheFile(context));
        } catch (Exception e) {
            NetworkDiagnostics.logFailure(context, "cached playlist parse", e);
            throw e;
        }
    }

    private static void replace(File source, File target) throws Exception {
        if (target.exists() && !target.delete()) throw new IllegalStateException("Cannot replace cached playlist");
        if (source.renameTo(target)) return;
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        source.delete();
    }
}
