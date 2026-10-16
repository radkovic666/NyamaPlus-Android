package fun.nyama.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Background-only Android TV metadata lookup. It never probes or extracts media streams.
 * TVmaze public API: https://www.tvmaze.com/api (IMDb lookup, seasons and episodes).
 * TVmaze data is attributed in the UI as CC BY-SA, with a link to the matched show.
 */
public final class TvSeriesRepository {
    static final long CACHE_TTL_MS = 60L * 60L * 1000L;
    private static final long STALE_LIMIT_MS = 7L * 24L * CACHE_TTL_MS;
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EPISODES = 10000;
    private static final String TVMAZE_SOURCE = "TVmaze (CC BY-SA)";
    private static final Object REQUEST_GATE = new Object();
    private static long nextRequestMs;
    private static long retryUntilMs;
    private static TvSeriesRepository instance;

    private final File cacheDirectory;
    private final Transport transport;
    private final Clock clock;
    private final Map<String, CacheEntry> memory = new LinkedHashMap<String, CacheEntry>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > 8;
        }
    };

    public static synchronized TvSeriesRepository get(Context context) {
        if (instance == null) instance = new TvSeriesRepository(
                new File(context.getApplicationContext().getCacheDir(), "tv_series_metadata"),
                new HttpTransport(), new SystemClock());
        return instance;
    }

    TvSeriesRepository(File directory, Transport transport, Clock clock) {
        this.cacheDirectory = directory;
        this.transport = transport;
        this.clock = clock;
    }

    /** Call on a worker; supplied Pukanki data is inspected before supplementary API calls. */
    public synchronized TvSeriesMetadata load(String imdbId, JSONObject pukanki, long nowMillis)
            throws IOException {
        String identity = canonicalImdb(imdbId);
        if (identity.isEmpty()) throw new IOException("A valid IMDb series identity is required");
        interrupted();
        TvSeriesMetadata primary = parsePukanki(identity, pukanki, nowMillis);
        if (primary != null) {
            remember(identity, primary, nowMillis);
            return primary;
        }
        CacheEntry cached = memory.get(identity);
        if (cached == null) cached = readCache(identity, nowMillis);
        if (cached != null && !cached.needsArtworkRefresh && age(nowMillis, cached.savedAt) < CACHE_TTL_MS) return cached.data;
        try {
            TvSeriesMetadata loaded = fetchTvmaze(identity, nowMillis);
            remember(identity, loaded, nowMillis);
            return loaded;
        } catch (IOException error) {
            if (Thread.currentThread().isInterrupted()) throw error;
            if (cached != null && age(nowMillis, cached.savedAt) < STALE_LIMIT_MS) return cached.data.asStale();
            throw error;
        }
    }

    static String canonicalImdb(String input) {
        String clean = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        return clean.matches("tt[0-9]{7,10}") ? clean : "";
    }

    private TvSeriesMetadata fetchTvmaze(String imdb, long now) throws IOException {
        JSONObject show = object(fetch("https://api.tvmaze.com/lookup/shows?imdb=" + imdb));
        String returned = canonicalImdb(show.optJSONObject("externals") == null ? ""
                : show.optJSONObject("externals").optString("imdb"));
        if (!imdb.equals(returned)) throw new IOException("TVmaze returned a different IMDb identity");
        int showId = positiveInt(show.opt("id"));
        if (showId < 1) throw new IOException("TVmaze show identity is missing");
        JSONArray seasonRows = array(fetch("https://api.tvmaze.com/shows/" + showId + "/seasons"));
        JSONArray episodeRows = array(fetch("https://api.tvmaze.com/shows/" + showId + "/episodes"));
        if (seasonRows.length() > 1000 || episodeRows.length() > MAX_EPISODES)
            throw new IOException("Series metadata exceeds the supported size");
        Map<Integer, Boolean> seasons = new TreeMap<>();
        for (int i = 0; i < seasonRows.length(); i++) {
            JSONObject row = seasonRows.optJSONObject(i);
            if (row != null) {
                int number = nonnegativeInt(row.opt("number"));
                if (number >= 0) seasons.put(number, Boolean.TRUE);
            }
        }
        TreeMap<Integer, TreeMap<Integer, TvSeriesMetadata.Episode>> episodes = new TreeMap<>();
        for (int i = 0; i < episodeRows.length(); i++) {
            JSONObject row = episodeRows.optJSONObject(i);
            if (row == null) continue;
            int season = nonnegativeInt(row.opt("season"));
            int number = positiveInt(row.opt("number"));
            if (!seasons.containsKey(season) || number <= 0) continue;
            JSONObject links = row.optJSONObject("_links");
            JSONObject owner = links == null ? null : links.optJSONObject("show");
            if (owner != null && !owner.optString("href").isEmpty()
                    && !owner.optString("href").equals("https://api.tvmaze.com/shows/" + showId))
                throw new IOException("Episode belongs to a different show");
            addEpisode(episodes, episode(row, season, number, now), now);
        }
        String sourceUrl = "https://www.tvmaze.com/shows/" + showId;
        List<TvSeriesMetadata.Season> result = grouped(episodes);
        if (result.isEmpty()) throw new IOException("No numbered, aired episode metadata is available");
        return new TvSeriesMetadata(imdb, TVMAZE_SOURCE, sourceUrl, result);
    }

    /** Only explicit numbered episode rows qualify; totalSeasons/episodeCount never create rows. */
    static TvSeriesMetadata parsePukanki(String imdb, JSONObject data, long now) throws IOException {
        if (data == null) return null;
        String declared = firstText(data, "imdbID", "imdb_id", "imdbId");
        if (!declared.isEmpty() && !imdb.equals(canonicalImdb(declared))) return null;
        TreeMap<Integer, TreeMap<Integer, TvSeriesMetadata.Episode>> collected = new TreeMap<>();
        inspectPukanki(data, imdb, -1, false, false, 0, new int[]{0}, collected, now);
        List<TvSeriesMetadata.Season> seasons = grouped(collected);
        return seasons.isEmpty() ? null : new TvSeriesMetadata(imdb, "Pukanki.Fun",
                "https://pukanki.fun", seasons);
    }

    private static void inspectPukanki(Object value, String imdb, int inheritedSeason, boolean episodeContainer,
                                      boolean seasonContainer, int depth, int[] visited,
                                      TreeMap<Integer, TreeMap<Integer, TvSeriesMetadata.Episode>> out,
                                      long now) throws IOException {
        if (depth > 8 || ++visited[0] > 25000) return;
        interrupted();
        if (value instanceof JSONArray) {
            JSONArray rows = (JSONArray) value;
            for (int i = 0; i < Math.min(rows.length(), MAX_EPISODES); i++)
                inspectPukanki(rows.opt(i), imdb, inheritedSeason, episodeContainer, seasonContainer,
                        depth + 1, visited, out, now);
        } else if (value instanceof JSONObject) {
            JSONObject row = (JSONObject) value;
            String declared = firstText(row, "imdbID", "imdb_id", "imdbId");
            if (!declared.isEmpty() && !imdb.equals(canonicalImdb(declared))) return;
            int season = firstNumber(row, true, "season", "Season", "season_number", "seasonNumber");
            if (season < 0 && seasonContainer) season = nonnegativeInt(row.opt("number"));
            if (season < 0) season = inheritedSeason;
            int number = firstNumber(row, false, "episode", "Episode", "episode_number", "episodeNumber");
            if (number < 1 && episodeContainer) number = positiveInt(row.opt("number"));
            if (season >= 0 && number > 0) addEpisode(out, episode(row, season, number, now), now);
            Iterator<String> keys = row.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = row.opt(key);
                if (!(child instanceof JSONObject) && !(child instanceof JSONArray)) continue;
                String normalized = key.toLowerCase(Locale.ROOT).replace("_", "");
                boolean episodes = normalized.equals("episodes") || normalized.equals("episodelist");
                boolean seasons = normalized.equals("seasons") || normalized.equals("seasonlist");
                inspectPukanki(child, imdb, season, episodes, seasons, depth + 1, visited, out, now);
            }
        }
    }

    private static TvSeriesMetadata.Episode episode(JSONObject row, int season, int number, long now) {
        String title = cleanText(firstText(row, "name", "title", "Title"));
        String summary = cleanText(firstText(row, "summary", "description", "Plot", "overview"));
        int duration = firstNumber(row, false, "runtime", "duration", "durationMinutes");
        JSONObject image = row.optJSONObject("image");
        String still = image == null ? firstText(row, "still", "stillUrl", "image")
                : firstText(image, "medium", "original");
        String original = image == null ? firstText(row, "originalStillUrl", "still", "stillUrl", "image")
                : firstText(image, "original", "medium");
        long air = parseDate(firstText(row, "airstamp", "airStamp", "air_datetime"));
        if (air < 0) air = parseDate(firstText(row, "airdate", "air_date", "released", "Released"));
        return new TvSeriesMetadata.Episode(season, number, title, duration, summary,
                safeImageUrl(still), safeImageUrl(original), air, now);
    }

    private static void addEpisode(TreeMap<Integer, TreeMap<Integer, TvSeriesMetadata.Episode>> rows,
                                   TvSeriesMetadata.Episode episode, long now) {
        if (episode.airStampMs > now) return; // Future metadata never implies playable content.
        TreeMap<Integer, TvSeriesMetadata.Episode> season = rows.get(episode.season);
        if (season == null) { season = new TreeMap<>(); rows.put(episode.season, season); }
        if (!season.containsKey(episode.number)) season.put(episode.number, episode);
    }

    private static List<TvSeriesMetadata.Season> grouped(
            TreeMap<Integer, TreeMap<Integer, TvSeriesMetadata.Episode>> source) {
        List<TvSeriesMetadata.Season> seasons = new ArrayList<>();
        // Specials are accurate but separate from normal seasons and placed after them.
        for (Map.Entry<Integer, TreeMap<Integer, TvSeriesMetadata.Episode>> row : source.entrySet()) {
            if (row.getKey() != 0)
                seasons.add(new TvSeriesMetadata.Season(row.getKey(), new ArrayList<>(row.getValue().values())));
        }
        if (source.containsKey(0))
            seasons.add(new TvSeriesMetadata.Season(0, new ArrayList<>(source.get(0).values())));
        return seasons;
    }

    private String fetch(String address) throws IOException {
        URL url = verifiedApiUrl(address);
        for (int redirects = 0; redirects <= 3; redirects++) {
            interrupted();
            politeRequest();
            Response response = transport.get(url);
            if (response.status == 429) {
                long retryAt = retryAt(response.retryAfter, clock.nowMillis());
                synchronized (REQUEST_GATE) { retryUntilMs = Math.max(retryUntilMs, retryAt); }
                throw new IOException("TVmaze rate limit; retry after " + new Date(retryAt));
            }
            if (response.status == 301 || response.status == 302 || response.status == 303
                    || response.status == 307 || response.status == 308) {
                if (redirects == 3 || response.location.isEmpty())
                    throw new IOException("Invalid or excessive TVmaze redirects");
                url = verifiedApiUrl(new URL(url, response.location).toExternalForm());
                continue;
            }
            if (response.status != 200) throw new IOException("TVmaze HTTP " + response.status);
            if (response.body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES)
                throw new IOException("TVmaze response is too large");
            return response.body;
        }
        throw new IOException("TVmaze redirect limit exceeded");
    }

    static URL verifiedApiUrl(String address) throws IOException {
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !"api.tvmaze.com".equalsIgnoreCase(url.getHost())
                || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)
                || url.getRef() != null)
            throw new IOException("Unsafe TVmaze redirect destination");
        return url;
    }

    private void politeRequest() throws IOException {
        long delay;
        synchronized (REQUEST_GATE) {
            long now = clock.nowMillis();
            if (retryUntilMs > now) throw new IOException("TVmaze rate limit is still active");
            long reserved = Math.max(now, nextRequestMs);
            nextRequestMs = reserved + 600L; // Fewer than the documented 20 calls/10 seconds.
            delay = reserved - now;
        }
        if (delay > 0) clock.pause(delay);
        interrupted();
    }

    private void remember(String imdb, TvSeriesMetadata metadata, long now) {
        memory.put(imdb, new CacheEntry(metadata, now));
        File temporary = null;
        try {
            if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) return;
            JSONObject root = new JSONObject().put("schema", 1).put("savedAt", now)
                    .put("imdb", imdb).put("source", metadata.source).put("sourceUrl", metadata.sourceUrl);
            JSONArray episodes = new JSONArray();
            for (TvSeriesMetadata.Season season : metadata.seasons)
                for (TvSeriesMetadata.Episode item : season.episodes)
                    episodes.put(new JSONObject().put("season", item.season).put("number", item.number)
                            .put("name", item.title).put("runtime", item.durationMinutes)
                            .put("summary", item.summary).put("stillUrl", item.stillUrl)
                            .put("originalStillUrl", item.originalStillUrl)
                            .put("airStampMs", item.airStampMs));
            root.put("episodes", episodes);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BODY_BYTES) return;
            temporary = new File(cacheDirectory, imdb + ".tmp");
            try (FileOutputStream stream = new FileOutputStream(temporary)) { stream.write(bytes); }
            File destination = new File(cacheDirectory, imdb + ".json");
            if (!temporary.renameTo(destination)) temporary.delete();
            pruneCache();
        } catch (Exception ignored) {
            // A full or evicted Android cache must not prevent playback selection.
            if (temporary != null) temporary.delete();
        }
    }

    private CacheEntry readCache(String imdb, long now) {
        File file = new File(cacheDirectory, imdb + ".json");
        if (!file.isFile() || file.length() > MAX_BODY_BYTES) return null;
        try (InputStream input = new FileInputStream(file)) {
            JSONObject root = object(readBounded(input));
            if (root.optInt("schema") != 1 || !imdb.equals(root.optString("imdb"))) return null;
            long saved = root.optLong("savedAt", -1L);
            if (saved < 0 || age(now, saved) >= STALE_LIMIT_MS) return null;
            String source = root.optString("source");
            if (!source.equals(TVMAZE_SOURCE) && !source.equals("Pukanki.Fun")) return null;
            JSONArray episodes = root.optJSONArray("episodes");
            if (episodes == null || episodes.length() > MAX_EPISODES) return null;
            boolean legacyArtwork = false;
            TreeMap<Integer, TreeMap<Integer, TvSeriesMetadata.Episode>> grouped = new TreeMap<>();
            for (int i = 0; i < episodes.length(); i++) {
                JSONObject row = episodes.optJSONObject(i);
                if (row == null) continue;
                if (!row.has("originalStillUrl")) legacyArtwork = true;
                int season = nonnegativeInt(row.opt("season"));
                int number = positiveInt(row.opt("number"));
                if (season < 0 || number < 1) continue;
                addEpisode(grouped, new TvSeriesMetadata.Episode(season, number, row.optString("name"),
                        row.optInt("runtime", -1), row.optString("summary"),
                        safeImageUrl(row.optString("stillUrl")),
                        safeImageUrl(row.optString("originalStillUrl", row.optString("stillUrl"))),
                        row.optLong("airStampMs", -1), now), now);
            }
            List<TvSeriesMetadata.Season> seasons = grouped(grouped);
            if (seasons.isEmpty()) return null;
            String sourceUrl = source.equals("Pukanki.Fun") ? "https://pukanki.fun" : root.optString("sourceUrl");
            if (!sourceUrl.matches("https://www\\.tvmaze\\.com/shows/[0-9]+")
                    && !sourceUrl.equals("https://pukanki.fun")) return null;
            // Refresh an old thumbnail-only TVmaze record once to obtain original artwork. It stays
            // usable as stale metadata if offline; identities, episode records and TTL stay intact.
            CacheEntry entry = new CacheEntry(new TvSeriesMetadata(imdb, source, sourceUrl, seasons), saved,
                    legacyArtwork && TVMAZE_SOURCE.equals(source));
            memory.put(imdb, entry);
            return entry;
        } catch (Exception invalidCache) { return null; }
    }

    private void pruneCache() {
        File[] files = cacheDirectory.listFiles((directory, name) -> name.matches("tt[0-9]{7,10}\\.json"));
        if (files == null || files.length <= 64) return;
        List<File> ordered = new ArrayList<>();
        Collections.addAll(ordered, files);
        ordered.sort(Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < ordered.size() - 64; i++) ordered.get(i).delete();
    }

    private static long age(long now, long then) { return now < then ? Long.MAX_VALUE : now - then; }

    private static int firstNumber(JSONObject row, boolean zero, String... names) {
        for (String name : names) {
            int number = zero ? nonnegativeInt(row.opt(name)) : positiveInt(row.opt(name));
            if (number >= (zero ? 0 : 1)) return number;
        }
        return -1;
    }

    private static int positiveInt(Object value) {
        int parsed = nonnegativeInt(value);
        return parsed > 0 ? parsed : -1;
    }

    private static int nonnegativeInt(Object value) {
        if (value == null || value == JSONObject.NULL) return -1;
        String text = String.valueOf(value);
        if (!text.matches("[0-9]{1,6}")) return -1;
        try { return Integer.parseInt(text); } catch (NumberFormatException invalid) { return -1; }
    }

    private static String firstText(JSONObject row, String... keys) {
        for (String key : keys) {
            Object value = row.opt(key);
            if (value instanceof String && !((String) value).trim().isEmpty()
                    && !"N/A".equals(value)) return ((String) value).trim();
        }
        return "";
    }

    private static String cleanText(String text) {
        return text.replaceAll("<[^>]*>", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private static String safeImageUrl(String address) {
        try {
            URL url = new URL(address);
            if ("https".equalsIgnoreCase(url.getProtocol()) && url.getUserInfo() == null
                    && (url.getPort() == -1 || url.getPort() == 443)) return url.toExternalForm();
        } catch (Exception invalid) { /* Unverified image addresses are optional metadata. */ }
        return "";
    }

    private static long parseDate(String text) {
        if (text == null || text.isEmpty()) return -1L;
        for (String pattern : new String[]{"yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd", "dd MMM yyyy"}) {
            SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
            parser.setTimeZone(TimeZone.getTimeZone("UTC"));
            parser.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            Date parsed = parser.parse(text, position);
            if (parsed != null && position.getIndex() == text.length()) {
                long time = parsed.getTime();
                // A date without a broadcast time becomes eligible after that entire UTC date.
                return pattern.contains("HH") ? time : time + 24L * 60L * 60L * 1000L - 1;
            }
        }
        return -1L;
    }

    private static long retryAt(String retryAfter, long now) {
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            if (seconds >= 0 && seconds <= (Long.MAX_VALUE - now) / 1000L) return now + seconds * 1000L;
        } catch (Exception ignored) { /* Retry-After may also be an HTTP date. */ }
        SimpleDateFormat parser = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        parser.setLenient(false);
        parser.setTimeZone(TimeZone.getTimeZone("GMT"));
        ParsePosition position = new ParsePosition(0);
        Date date = parser.parse(retryAfter, position);
        return date != null && position.getIndex() == retryAfter.length() && date.getTime() > now
                ? date.getTime() : now + 60000L;
    }

    private static JSONObject object(String text) throws IOException {
        try { return new JSONObject(text); }
        catch (Exception invalid) { throw new IOException("Invalid series metadata object", invalid); }
    }

    private static JSONArray array(String text) throws IOException {
        try { return new JSONArray(text); }
        catch (Exception invalid) { throw new IOException("Invalid series metadata array", invalid); }
    }

    private static String readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            interrupted();
            if (output.size() + count > MAX_BODY_BYTES) throw new IOException("Series metadata is too large");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Series metadata request cancelled");
    }

    interface Clock { long nowMillis(); void pause(long millis) throws IOException; }
    interface Transport { Response get(URL url) throws IOException; }

    static final class Response {
        final int status;
        final String body;
        final String location;
        final String retryAfter;
        Response(int status, String body, String location, String retryAfter) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.location = location == null ? "" : location;
            this.retryAfter = retryAfter == null ? "" : retryAfter;
        }
    }

    private static final class CacheEntry {
        final TvSeriesMetadata data;
        final long savedAt;
        final boolean needsArtworkRefresh;
        CacheEntry(TvSeriesMetadata data, long savedAt) { this(data, savedAt, false); }
        CacheEntry(TvSeriesMetadata data, long savedAt, boolean needsArtworkRefresh) {
            this.data = data; this.savedAt = savedAt; this.needsArtworkRefresh = needsArtworkRefresh;
        }
    }

    private static final class SystemClock implements Clock {
        @Override public long nowMillis() { return System.currentTimeMillis(); }
        @Override public void pause(long millis) throws IOException {
            try { Thread.sleep(millis); }
            catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new IOException("Series metadata request cancelled", cancelled);
            }
        }
    }

    private static final class HttpTransport implements Transport {
        @Override public Response get(URL url) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "NyamaPlusAndroidTV/series-metadata");
            try {
                int status = connection.getResponseCode();
                String body = "";
                if (status == 200) try (InputStream input = connection.getInputStream()) { body = readBounded(input); }
                return new Response(status, body, connection.getHeaderField("Location"),
                        connection.getHeaderField("Retry-After"));
            } finally { connection.disconnect(); }
        }
    }
}
