package fun.nyama.tv;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Optional same-origin service. Credentials never enter the player URL or WebView. */
final class SubtitleApi implements AutoCloseable {
    interface Credentials { String[] read() throws Exception; }
    interface Connections { HttpsURLConnection open(URL url) throws Exception; }
    static final String SUBSOURCE_BASE = "https://nyama.fun/api/subtitles";
    final String base;
    final boolean subsource;
    private final Credentials credentials;
    private final Connections connections;
    private volatile boolean closed;
    private HttpsURLConnection active;
    static final class Failure extends IOException {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    static final class Release {
        final long fileId;
        final String label;
        final Double fps, rating;
        final Long downloads;
        final String format, uploader;
        Release(JSONObject j) throws Exception { this(j, false); }
        Release(JSONObject j, boolean subsource) throws Exception {
            String idField = subsource ? "subtitle_id" : "file_id";
            fileId = j.getLong(idField);
            if (!(j.opt(idField) instanceof Number) || j.getDouble(idField) != fileId) throw new Failure("invalid_response");
            if (fileId <= 0 || fileId > 9007199254740991L || !"bg".equals(j.optString("language"))) throw new Failure("invalid_response");
            label = label(j.getString("release"));
            if (label.isEmpty()) throw new Failure("invalid_response");
            fps = number(j, "fps"); rating = number(j, "rating");
            Double d = number(j, "downloads"); downloads = d == null ? null : d.longValue();
            String f = j.optString("format", "").toLowerCase(Locale.ROOT);
            format = f.matches("srt|vtt") ? f : "";
            uploader = label(j.optString("uploader", ""));
        }
        String display() {
            return label + " · #" + fileId + (uploader.isEmpty() ? "" : " · @" + uploader) + (fps == null ? "" : " · " + fps + " FPS")
                    + (downloads == null ? "" : " · ↓" + downloads)
                    + (rating == null ? "" : " · ★" + rating) + (format.isEmpty() ? "" : " · " + format.toUpperCase(Locale.ROOT));
        }
    }
    static final class Selection {
        final String url, label;
        final long expires, fileId;
        final double offset;
        Selection(JSONObject j, String base, long fileId, double offset, long now) throws Exception { this(j, base, fileId, offset, now, false); }
        Selection(JSONObject j, String base, long fileId, double offset, long now, boolean subsource) throws Exception {
            this.fileId = fileId; this.offset = offset;
            if (subsource && !"subsource".equals(j.optString("provider"))) throw new Failure("invalid_response");
            if (j.getLong(subsource ? "subtitle_id" : "file_id") != fileId || j.getDouble("offset_seconds") != offset) throw new Failure("invalid_response");
            label = label(j.getString("release"));
            url = j.getString("url"); expires = j.getLong("expires");
            Uri target = Uri.parse(url), root = Uri.parse(base);
            if (expires <= now + 30 || expires > now + 3600 || url.length() > 4096 || !"https".equals(target.getScheme())
                    || target.getUserInfo() != null || target.getPort() != -1 || target.getFragment() != null
                    || !root.getHost().equals(target.getHost())
                    || !target.getPath().matches(java.util.regex.Pattern.quote(root.getPath()) + "/file/[a-f0-9]{64}" + (subsource ? "\\.vtt" : ""))
                    || !Long.toString(expires).equals(target.getQueryParameter("expires"))
                    || target.getQueryParameters("expires").size() != 1 || target.getQueryParameters("sig").size() != 1
                    || target.getQueryParameter("sig") == null || !target.getQueryParameter("sig").matches("[a-f0-9]{64}")) throw new Failure("invalid_response");
        }
    }
    SubtitleApi(Context context) {
        this(!new Config(context).isPhoneMode() && BuildConfig.SUBTITLES_BACKEND.isEmpty() ? SUBSOURCE_BASE : BuildConfig.SUBTITLES_BACKEND, () -> {
            if (!ActivationAccess.playbackAllowed(context) || !ActivationStore.managed(context)) throw new Failure("unauthorized");
            String[] existing = new ActivationStore(context).optionalCredentials();
            if (existing == null) throw new Failure("unauthorized");
            return existing;
        }, url -> (HttpsURLConnection) url.openConnection(), !new Config(context).isPhoneMode());
    }
    SubtitleApi(String base, Credentials credentials, Connections connections) { this(base, credentials, connections, false); }
    SubtitleApi(String base, Credentials credentials, Connections connections, boolean subsource) {
        this.subsource = subsource;
        this.base = validBase(base) && (!subsource || SUBSOURCE_BASE.equals(base)) ? base : ""; this.credentials = credentials; this.connections = connections;
    }
    static boolean validBase(String base) {
        try {
            Uri u = Uri.parse(base);
            // Nyama activation credentials may only be forwarded to their original host.
            return "https".equals(u.getScheme()) && "nyama.fun".equals(u.getHost()) && u.getPort() == -1
                    && u.getUserInfo() == null && u.getQuery() == null && u.getFragment() == null
                    && u.getPath().matches("/[a-zA-Z0-9_/-]+/subtitles") && !u.getPath().contains("..");
        } catch (Exception invalid) { return false; }
    }
    static String label(String raw) {
        if (raw == null) return "";
        String clean = raw.replaceAll("[\\p{Cntrl}\\p{Cf}<>]", " ").replaceAll("\\s+", " ").trim();
        return clean.substring(0, Math.min(180, clean.length()));
    }
    private static Double number(JSONObject j, String key) {
        Object raw = j.opt(key);
        if (!(raw instanceof Number)) return null;
        double n = ((Number) raw).doubleValue();
        return Double.isFinite(n) && n >= 0 && n <= 1e12 ? n : null;
    }
    static final class Page {
        final List<Release> releases; final int next;
        Page(List<Release> releases, int next) { this.releases = releases; this.next = next; }
    }
    List<Release> search(SubtitleMedia media) throws Exception { return searchPage(media, 1).releases; }
    Page searchPage(SubtitleMedia media, int page) throws Exception {
        if (page < 1 || page > 20) throw new Failure("invalid_input");
        Uri.Builder query = Uri.parse(base + "/search").buildUpon();
        JSONObject identity = media.json();
        for (Iterator<String> it = identity.keys(); it.hasNext();) {
            String k = it.next(); query.appendQueryParameter(k, identity.get(k).toString());
        }
        if (subsource) query.appendQueryParameter("page", Integer.toString(page));
        JSONObject response = request(query.build().toString(), null);
        if (subsource && (!"subsource".equals(response.optString("provider")) || response.optInt("page", -1) != page)) throw new Failure("invalid_response");
        List<Release> found = results(response, media, subsource);
        int next = subsource ? response.optInt("next_page", 0) : 0;
        if (next != 0 && (next != page + 1 || next > 20)) throw new Failure("invalid_response");
        return new Page(found, next);
    }
    static List<Release> results(JSONObject response, SubtitleMedia media) throws Exception { return results(response, media, false); }
    static List<Release> results(JSONObject response, SubtitleMedia media, boolean subsource) throws Exception {
        if ((subsource && !"subsource".equals(response.optString("provider"))) || !response.optBoolean("ok") || !media.matches(response.optJSONObject("media"))) throw new Failure("invalid_response");
        JSONArray rows = response.getJSONArray("results");
        if (rows.length() > 100) throw new Failure("invalid_response");
        List<Release> results = new ArrayList<>(); Set<Long> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            Release r = new Release(rows.getJSONObject(i), subsource);
            if (seen.add(r.fileId)) results.add(r);
        }
        return results;
    }
    Selection select(SubtitleMedia media, long fileId, double offset) throws Exception {
        if (!Double.isFinite(offset) || offset < -10 || offset > 10 || offset * 2 != Math.rint(offset * 2)) throw new Failure("invalid_input");
        JSONObject r = request(base + "/select", media.json().put(subsource ? "subtitle_id" : "file_id", fileId).put("offset_seconds", offset));
        if (!media.matches(r.optJSONObject("media"))) throw new Failure("invalid_response");
        return new Selection(r, base, fileId, offset, System.currentTimeMillis() / 1000, subsource);
    }
    private JSONObject request(String url, JSONObject body) throws Exception {
        if (base.isEmpty()) throw new Failure("unconfigured");
        if (closed) throw new InterruptedIOException();
        String[] auth = credentials.read();
        HttpsURLConnection c = connections.open(new URL(url));
        synchronized (this) { if (closed) { c.disconnect(); throw new InterruptedIOException(); } active = c; }
        try {
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(15000); c.setUseCaches(false);
            c.setRequestProperty("Authorization", "Bearer " + auth[1]); c.setRequestProperty("X-Nyama-Activation-ID", auth[0]);
            c.setRequestProperty("Accept", "application/json");
            if (subsource) c.setRequestProperty("X-Nyama-Subtitle-Provider", "subsource");
            if (body != null) {
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int status = c.getResponseCode();
            if (status != 200) throw new Failure(status == 401 || status == 403 ? "unauthorized" : status == 429 ? "rate_limited" : "unavailable");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (closed) throw new InterruptedIOException();
                    if (out.size() + n > 131072) throw new Failure("invalid_response"); out.write(buffer, 0, n);
                }
                JSONObject r;
                try { r = new JSONObject(out.toString("UTF-8")); } catch (Exception invalid) { throw new Failure("invalid_response"); }
                if (!r.optBoolean("ok")) throw new Failure("unavailable");
                return r;
            }
        } finally { synchronized (this) { if (active == c) active = null; } c.disconnect(); }
    }
    @Override public synchronized void close() { closed = true; if (active != null) active.disconnect(); }
}
