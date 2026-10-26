package fun.nyama.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Comparator;

/** Local TV caption preferences, scoped to provider + exact title/episode; no URLs or credentials. */
final class TvSubtitleStore {
    private final SharedPreferences preferences;
    private final String key;
    private final File directory;
    TvSubtitleStore(Context context, String identity) {
        preferences = context.getSharedPreferences("nyama_tv_subtitles", Context.MODE_PRIVATE);
        key = TvSubtitleFile.hash(identity);
        directory = new File(context.getCacheDir(), "tv-subtitles");
    }
    JSONObject read() {
        try { return new JSONObject(preferences.getString(key, "{}")); }
        catch (JSONException invalid) { return new JSONObject(); }
    }
    void save(String mode, String source, String fingerprint, String file, double offset) {
        try {
            JSONObject value = new JSONObject().put("mode", mode).put("source", source).put("fingerprint", fingerprint)
                    .put("file", file).put("offset", offset).put("at", System.currentTimeMillis());
            SharedPreferences.Editor edit = preferences.edit().putString(key, value.toString());
            // Keep this feature independent of all activation/settings/history preference stores.
            if (preferences.getAll().size() >= 128) {
                String oldest = null; long time = Long.MAX_VALUE;
                for (String candidate : preferences.getAll().keySet()) {
                    if (candidate.equals(key)) continue;
                    long at;
                    try { at = new JSONObject(preferences.getString(candidate, "{}")).optLong("at", 0); }
                    catch (JSONException malformed) { at = 0; }
                    if (at < time) { time = at; oldest = candidate; }
                }
                if (oldest != null) edit.remove(oldest);
            }
            edit.apply();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    void cache(TvSubtitleFile file) throws java.io.IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("subtitle_cache");
        File target = new File(directory, file.id + ".json");
        android.util.AtomicFile atomic = new android.util.AtomicFile(target);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite(); output.write(file.json().toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(output); output = null;
        } finally { if (output != null) atomic.failWrite(output); }
        File[] cached = directory.listFiles((dir, name) -> name.matches("[0-9a-f]{64}\\.json"));
        if (cached == null) return;
        Arrays.sort(cached, Comparator.comparingLong(File::lastModified).reversed());
        int retained = 0;
        for (File entry : cached) {
            if (entry.equals(target)) continue;
            if (++retained >= 12) entry.delete();
        }
    }
    TvSubtitleFile load(String id) throws java.io.IOException {
        if (!id.matches("[0-9a-f]{64}")) throw new java.io.IOException("subtitle_cache");
        File file = new File(directory, id + ".json");
        if (file.length() > 4L * TvSubtitleFile.MAX_BYTES) throw new java.io.IOException("subtitle_size");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream input = new android.util.AtomicFile(file).openRead()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 4 * TvSubtitleFile.MAX_BYTES) throw new java.io.IOException("subtitle_size");
                bytes.write(buffer, 0, count);
            }
        }
        TvSubtitleFile loaded = TvSubtitleFile.cached(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        if (!id.equals(loaded.id)) throw new java.io.IOException("subtitle_cache");
        return loaded;
    }
}
