package fun.nyama.tv;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class PukankiApi {
    private static final ExecutorService POSTER_CHECKS = Executors.newFixedThreadPool(24);
    private static final ExecutorService CATALOG_SCAN = Executors.newFixedThreadPool(4);
    private static final ExecutorService TV_POSTER_CHECKS = Executors.newFixedThreadPool(6);
    private static final Map<String, Boolean> POSTER_AVAILABILITY = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> TV_POSTER_AVAILABILITY = new ConcurrentHashMap<>();

    // Code 13: raw TV catalog cache. JSON catalogue loading is cheap compared with probing every
    // poster. Keep the parsed catalogue separately so search can be purely local while poster
    // validation continues in the background.
    private static final Map<String, PosterCatalogSnapshot> RAW_CATALOG_CACHE =
            new LinkedHashMap<String, PosterCatalogSnapshot>(8, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, PosterCatalogSnapshot> eldest) {
                    return size() > 8;
                }
            };

    // TV needs an exact count after posterless titles are removed. Keep a small in-memory LRU
    // of already-normalized catalogs so normal paging does not rescan the whole backend.
    private static final long POSTER_CATALOG_TTL_MS = 10L * 60L * 1000L;
    private static final Map<String, PosterCatalogSnapshot> POSTER_CATALOG_CACHE =
            new LinkedHashMap<String, PosterCatalogSnapshot>(12, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, PosterCatalogSnapshot> eldest) {
                    return size() > 12;
                }
            };

    private PukankiApi() {}

    /**
     * Original lightweight page fetch used by phone mode. It preserves the current mobile
     * behavior and filters posterless rows only from the requested backend page.
     */
    public static CatalogPage fetchCatalog(Context context, String section, int page, int pageSize,
                                           String query, List<String> genres, String sort,
                                           boolean favoritesOnly, String favoriteIds) throws Exception {
        JSONObject root = fetchCatalogRoot(context, section, page, pageSize, query, genres, sort,
                favoritesOnly, favoriteIds);
        List<CatalogItem> items = parseItems(root);
        // Pukanki may contain catalog entries whose poster has not been cached yet.
        // Nyama+ intentionally omits those entries instead of showing empty/grey cards.
        items = onlyItemsWithPosters(context, items, true);
        return new CatalogPage(items, root.optInt("total", items.size()),
                root.optInt("page", page), root.optInt("total_pages", 1));
    }


    /**
     * Code 13 TV fast path: fetch only one backend page and validate only those posters.
     * This is used for first paint while the complete catalogue is warmed in the background.
     */
    public static CatalogPage fetchTvFastPage(Context context, String section, int page, int pageSize,
                                              List<String> genres, String sort, boolean favoritesOnly,
                                              String favoriteIds) throws Exception {
        JSONObject root = fetchCatalogRoot(context, section, Math.max(1, page), pageSize, "", genres, sort,
                favoritesOnly, favoriteIds);
        List<CatalogItem> parsed = parseItems(root);
        List<CatalogItem> visible = onlyTvItemsWithPostersFast(context, parsed);
        int backendTotal = Math.max(visible.size(), root.optInt("total", visible.size()));
        int backendPages = Math.max(1, root.optInt("total_pages",
                (backendTotal + Math.max(1, pageSize) - 1) / Math.max(1, pageSize)));
        int safePage = Math.max(1, Math.min(Math.max(1, page), backendPages));
        return new CatalogPage(visible, backendTotal, safePage, backendPages);
    }

    /**
     * Fetch and parse the complete filtered/sorted TV catalogue without waiting for poster probes.
     * The result is cached in memory for section switches and is the source for instant local search.
     */
    public static List<CatalogItem> fetchTvRawCatalog(Context context, String section,
                                                      List<String> genres, String sort,
                                                      boolean favoritesOnly, String favoriteIds) throws Exception {
        final String key = rawCatalogKey(context, section, genres, sort, favoritesOnly, favoriteIds);
        PosterCatalogSnapshot cached;
        synchronized (RAW_CATALOG_CACHE) {
            cached = RAW_CATALOG_CACHE.get(key);
            if (cached != null && System.currentTimeMillis() - cached.createdAt > POSTER_CATALOG_TTL_MS) {
                RAW_CATALOG_CACHE.remove(key);
                cached = null;
            }
        }
        if (cached != null) return new ArrayList<>(cached.items);

        final int scanPageSize = 100;
        JSONObject first = fetchCatalogRoot(context, section, 1, scanPageSize, "", genres, sort,
                favoritesOnly, favoriteIds);
        int totalPages = Math.max(1, first.optInt("total_pages", 1));
        List<CatalogItem> all = new ArrayList<>(parseItems(first));
        if (totalPages > 1) {
            List<Callable<List<CatalogItem>>> tasks = new ArrayList<>();
            for (int p = 2; p <= totalPages; p++) {
                final int requestedPage = p;
                tasks.add(() -> parseItems(fetchCatalogRoot(context, section, requestedPage, scanPageSize, "",
                        genres, sort, favoritesOnly, favoriteIds)));
            }
            List<Future<List<CatalogItem>>> futures = CATALOG_SCAN.invokeAll(tasks);
            for (Future<List<CatalogItem>> future : futures) all.addAll(future.get());
        }
        PosterCatalogSnapshot snapshot = new PosterCatalogSnapshot(all, System.currentTimeMillis());
        synchronized (RAW_CATALOG_CACHE) { RAW_CATALOG_CACHE.put(key, snapshot); }
        return new ArrayList<>(all);
    }

    /** Return a cached raw TV catalogue without doing any network I/O. */
    public static List<CatalogItem> getCachedRawTvCatalog(Context context, String section,
                                                          List<String> genres, String sort,
                                                          boolean favoritesOnly, String favoriteIds) {
        String key = rawCatalogKey(context, section, genres, sort, favoritesOnly, favoriteIds);
        synchronized (RAW_CATALOG_CACHE) {
            PosterCatalogSnapshot snapshot = RAW_CATALOG_CACHE.get(key);
            if (snapshot == null) return null;
            if (System.currentTimeMillis() - snapshot.createdAt > POSTER_CATALOG_TTL_MS) {
                RAW_CATALOG_CACHE.remove(key);
                return null;
            }
            return new ArrayList<>(snapshot.items);
        }
    }

    /** Pure in-memory title filtering used by Code 13 live search. */
    public static List<CatalogItem> filterLocal(List<CatalogItem> source, String query) {
        List<CatalogItem> out = new ArrayList<>();
        if (source == null) return out;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) { out.addAll(source); return out; }
        for (CatalogItem item : source) {
            if (item == null) continue;
            String title = item.title == null ? "" : item.title.toLowerCase(Locale.ROOT);
            String original = item.originalTitle == null ? "" : item.originalTitle.toLowerCase(Locale.ROOT);
            if (title.contains(q) || original.contains(q)) out.add(item);
        }
        return out;
    }

    /** Validate a supplied in-memory subset without refetching catalogue JSON. */
    public static List<CatalogItem> validateTvPosters(Context context, List<CatalogItem> source) throws Exception {
        return onlyTvItemsWithPosters(context, source, false);
    }

    /**
     * Series must never expose or count cards whose poster could not be confirmed.
     * After the normal retry pass, unresolved poster probes are omitted rather than leaving the
     * raw backend total on screen indefinitely. Movies keep their existing fail-safe behavior.
     */
    public static List<CatalogItem> validateTvSeriesPosters(Context context, List<CatalogItem> source) throws Exception {
        return onlyTvItemsWithPosters(context, source, true);
    }

    /** Fast validation for a small first-page/search subset. */
    public static List<CatalogItem> validateTvPostersFast(Context context, List<CatalogItem> source) {
        return onlyTvItemsWithPostersFast(context, source);
    }

    public static List<CatalogItem> getCachedVisibleTvCatalog(Context context, String section,
                                                              List<String> genres, String sort,
                                                              boolean favoritesOnly, String favoriteIds) {
        String key = posterCatalogKey(context, section, "", genres, sort, favoritesOnly, favoriteIds);
        synchronized (POSTER_CATALOG_CACHE) {
            PosterCatalogSnapshot snapshot = POSTER_CATALOG_CACHE.get(key);
            if (snapshot == null) return null;
            if (System.currentTimeMillis() - snapshot.createdAt > POSTER_CATALOG_TTL_MS) {
                POSTER_CATALOG_CACHE.remove(key);
                return null;
            }
            return new ArrayList<>(snapshot.items);
        }
    }

    public static void cacheVisibleTvCatalog(Context context, String section, List<String> genres, String sort,
                                             boolean favoritesOnly, String favoriteIds, List<CatalogItem> items) {
        if (items == null || items.isEmpty()) return;
        String key = posterCatalogKey(context, section, "", genres, sort, favoritesOnly, favoriteIds);
        synchronized (POSTER_CATALOG_CACHE) {
            POSTER_CATALOG_CACHE.put(key, new PosterCatalogSnapshot(items, System.currentTimeMillis()));
        }
    }

    /**
     * Android-TV catalog fetch. It builds the visible catalog from every matching backend page,
     * removes posterless titles first, then paginates the remaining titles locally. That makes
     * both the displayed total and the page count describe the titles the user can actually see.
     */
    public static CatalogPage fetchPosterCatalog(Context context, String section, int page, int pageSize,
                                                 String query, List<String> genres, String sort,
                                                 boolean favoritesOnly, String favoriteIds) throws Exception {
        final int safePageSize = Math.max(1, Math.min(100, pageSize));
        final String cacheKey = posterCatalogKey(context, section, query, genres, sort, favoritesOnly, favoriteIds);
        PosterCatalogSnapshot snapshot;
        synchronized (POSTER_CATALOG_CACHE) {
            snapshot = POSTER_CATALOG_CACHE.get(cacheKey);
            if (snapshot != null && System.currentTimeMillis() - snapshot.createdAt > POSTER_CATALOG_TTL_MS) {
                POSTER_CATALOG_CACHE.remove(cacheKey);
                snapshot = null;
            }
        }

        if (snapshot == null) {
            List<CatalogItem> visible = fetchAllVisibleCatalogItems(context, section, query, genres, sort,
                    favoritesOnly, favoriteIds);
            snapshot = new PosterCatalogSnapshot(visible, System.currentTimeMillis());
            // Never pin a suspicious empty result in memory. A transient poster-service failure must
            // not turn the normal Movies/Series catalogue into "0 titles" for the cache lifetime.
            if (!visible.isEmpty()) {
                synchronized (POSTER_CATALOG_CACHE) {
                    POSTER_CATALOG_CACHE.put(cacheKey, snapshot);
                }
            }
        }

        int total = snapshot.items.size();
        int totalPages = Math.max(1, (total + safePageSize - 1) / safePageSize);
        int safePage = Math.max(1, Math.min(Math.max(1, page), totalPages));
        int start = Math.min(total, (safePage - 1) * safePageSize);
        int end = Math.min(total, start + safePageSize);
        List<CatalogItem> pageItems = new ArrayList<>(snapshot.items.subList(start, end));
        return new CatalogPage(pageItems, total, safePage, totalPages);
    }

    private static List<CatalogItem> fetchAllVisibleCatalogItems(Context context, String section,
                                                                 String query, List<String> genres, String sort,
                                                                 boolean favoritesOnly, String favoriteIds) throws Exception {
        final int scanPageSize = 30;
        JSONObject first = fetchCatalogRoot(context, section, 1, scanPageSize, query, genres, sort,
                favoritesOnly, favoriteIds);
        int totalPages = Math.max(1, first.optInt("total_pages", 1));
        List<CatalogItem> all = new ArrayList<>(parseItems(first));

        if (totalPages > 1) {
            List<Callable<List<CatalogItem>>> tasks = new ArrayList<>();
            for (int p = 2; p <= totalPages; p++) {
                final int requestedPage = p;
                tasks.add(() -> parseItems(fetchCatalogRoot(context, section, requestedPage, scanPageSize,
                        query, genres, sort, favoritesOnly, favoriteIds)));
            }
            List<Future<List<CatalogItem>>> futures = CATALOG_SCAN.invokeAll(tasks);
            // invokeAll returns futures in task order, which preserves the server's sort order.
            for (Future<List<CatalogItem>> future : futures) all.addAll(future.get());
        }

        return onlyTvItemsWithPosters(context, all);
    }


    /**
     * Small-subset poster validation used only for first paint and temporary search results.
     * It never turns a timeout into "all items have posters". Completed positive probes are shown;
     * the full background validator can add any uncertain valid titles later.
     */
    private static List<CatalogItem> onlyTvItemsWithPostersFast(Context context, List<CatalogItem> source) {
        List<CatalogItem> result = new ArrayList<>();
        if (source == null || source.isEmpty()) return result;
        List<Callable<Boolean>> checks = new ArrayList<>();
        for (CatalogItem item : source) {
            checks.add(() -> posterExists(context, item == null ? "" : item.id));
        }
        try {
            List<Future<Boolean>> futures = POSTER_CHECKS.invokeAll(checks, 2800L, TimeUnit.MILLISECONDS);
            for (int i = 0; i < source.size() && i < futures.size(); i++) {
                Future<Boolean> future = futures.get(i);
                if (future.isCancelled()) continue;
                try { if (Boolean.TRUE.equals(future.get())) result.add(source.get(i)); }
                catch (Exception ignored) {}
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    /**
     * TV-only exact poster filtering. The previous Code 11 implementation fired a large burst of
     * HEAD requests across the whole catalogue. On the full unfiltered catalogue that could be
     * rate-limited or rejected by the poster endpoint, turning every otherwise valid title into a
     * false negative while small search-result sets still worked.
     *
     * This path uses the same real GET semantics as PosterLoader (with a small byte range), lower
     * concurrency and a retry for transient failures. Definitive 404/410 responses are filtered;
     * uncertain network failures are never silently converted into "no poster".
     */
    private static List<CatalogItem> onlyTvItemsWithPosters(Context context, List<CatalogItem> source) throws Exception {
        return onlyTvItemsWithPosters(context, source, false);
    }

    private static List<CatalogItem> onlyTvItemsWithPosters(Context context, List<CatalogItem> source,
                                                             boolean excludeUnknown) throws Exception {
        if (source == null || source.isEmpty()) return source == null ? new ArrayList<>() : source;

        PosterProbe[] states = new PosterProbe[source.size()];
        List<Callable<PosterProbe>> checks = new ArrayList<>();
        for (CatalogItem item : source) {
            checks.add(() -> probePosterForTv(context, item == null ? "" : item.id));
        }

        List<Future<PosterProbe>> futures = TV_POSTER_CHECKS.invokeAll(checks);
        boolean retryNeeded = false;
        for (int i = 0; i < states.length; i++) {
            try {
                states[i] = futures.get(i).get();
            } catch (Exception ignored) {
                states[i] = PosterProbe.UNKNOWN;
            }
            if (states[i] == PosterProbe.UNKNOWN) retryNeeded = true;
        }

        if (retryNeeded) {
            try { Thread.sleep(450L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
            List<Callable<PosterProbe>> retries = new ArrayList<>();
            List<Integer> retryIndexes = new ArrayList<>();
            for (int i = 0; i < states.length; i++) {
                if (states[i] != PosterProbe.UNKNOWN) continue;
                final CatalogItem item = source.get(i);
                retryIndexes.add(i);
                retries.add(() -> probePosterForTv(context, item == null ? "" : item.id));
            }
            List<Future<PosterProbe>> retryFutures = TV_POSTER_CHECKS.invokeAll(retries);
            for (int i = 0; i < retryFutures.size(); i++) {
                try { states[retryIndexes.get(i)] = retryFutures.get(i).get(); }
                catch (Exception ignored) { states[retryIndexes.get(i)] = PosterProbe.UNKNOWN; }
            }
        }

        List<CatalogItem> visible = new ArrayList<>();
        for (int i = 0; i < states.length; i++) {
            if (states[i] == PosterProbe.AVAILABLE) visible.add(source.get(i));
            else if (states[i] == PosterProbe.UNKNOWN && !excludeUnknown) {
                // Preserve the existing Movies behavior: do not replace a working catalogue with a
                // false low count just because the poster host was temporarily unreachable.
                throw new IllegalStateException("Poster availability temporarily unavailable");
            }
        }
        return visible;
    }

    private static PosterProbe probePosterForTv(Context context, String imdbId) {
        if (imdbId == null || imdbId.trim().isEmpty()) return PosterProbe.MISSING;
        String key = new Config(context).pukankiBaseUrl() + "|" + imdbId;
        Boolean cached = TV_POSTER_AVAILABILITY.get(key);
        if (cached != null) return cached ? PosterProbe.AVAILABLE : PosterProbe.MISSING;

        java.io.File cachedPoster = new java.io.File(new java.io.File(context.getCacheDir(), "pukanki_posters"),
                imdbId.replaceAll("[^A-Za-z0-9._-]", "_") + ".jpg");
        if (cachedPoster.exists() && cachedPoster.length() > 512L) {
            TV_POSTER_AVAILABILITY.put(key, true);
            return PosterProbe.AVAILABLE;
        }

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(posterUrl(context, imdbId)).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(2800);
            c.setReadTimeout(3800);
            c.setUseCaches(true);
            c.setRequestProperty("Range", "bytes=0-1023");
            c.setRequestProperty("Accept", "image/*,*/*;q=0.5");
            c.setRequestProperty("User-Agent", DeviceIdentity.userAgent(context) + " NyamaPlus/1.0.10");
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE) {
                TV_POSTER_AVAILABILITY.put(key, false);
                return PosterProbe.MISSING;
            }
            if (!((code >= 200 && code < 300) || code == HttpURLConnection.HTTP_PARTIAL)) {
                return PosterProbe.UNKNOWN;
            }

            String contentType = c.getContentType();
            byte[] head = new byte[64];
            int used = 0;
            try (InputStream in = c.getInputStream()) {
                while (used < head.length) {
                    int n = in.read(head, used, head.length - used);
                    if (n < 0) break;
                    used += n;
                }
            }
            if (used <= 0) return PosterProbe.UNKNOWN;
            boolean imageType = contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/");
            boolean imageMagic = looksLikeImage(head, used);
            if (imageType || imageMagic) {
                TV_POSTER_AVAILABILITY.put(key, true);
                return PosterProbe.AVAILABLE;
            }
            // A successful HTTP response containing actual non-image data is a definitive bad poster.
            TV_POSTER_AVAILABILITY.put(key, false);
            return PosterProbe.MISSING;
        } catch (Exception ignored) {
            return PosterProbe.UNKNOWN;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean looksLikeImage(byte[] data, int length) {
        if (data == null || length < 4) return false;
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) return true;
        if (length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) return true;
        return length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    private enum PosterProbe { AVAILABLE, MISSING, UNKNOWN }

    private static JSONObject fetchCatalogRoot(Context context, String section, int page, int pageSize,
                                               String query, List<String> genres, String sort,
                                               boolean favoritesOnly, String favoriteIds) throws Exception {
        String base = new Config(context).pukankiBaseUrl();
        Uri.Builder b = Uri.parse(base + "/api/" + ("series".equals(section) ? "series" : "movies")).buildUpon();
        b.appendQueryParameter("page", Integer.toString(Math.max(1, page)));
        b.appendQueryParameter("page_size", Integer.toString(Math.max(1, Math.min(100, pageSize))));
        if (query != null && !query.trim().isEmpty()) b.appendQueryParameter("q", query.trim());
        b.appendQueryParameter("sort", sort == null || sort.isEmpty() ? "reviews" : sort);
        if (genres != null && !genres.isEmpty()) b.appendQueryParameter("genres", join(genres));
        b.appendQueryParameter("view", favoritesOnly ? "favorites" : "all");
        if (favoritesOnly) b.appendQueryParameter("ids", favoriteIds == null ? "" : favoriteIds);
        return new JSONObject(get(context, b.build().toString()));
    }

    private static List<CatalogItem> parseItems(JSONObject root) {
        JSONArray arr = root == null ? null : root.optJSONArray("items");
        List<CatalogItem> items = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) items.add(CatalogItem.fromJson(obj));
            }
        }
        return items;
    }

    private static String rawCatalogKey(Context context, String section, List<String> genres,
                                        String sort, boolean favoritesOnly, String favoriteIds) {
        return new Config(context).pukankiBaseUrl() + "|raw|" + ("series".equals(section) ? "series" : "movies")
                + "|g=" + join(genres) + "|s=" + safe(sort) + "|fav=" + favoritesOnly
                + "|ids=" + (favoritesOnly ? safe(favoriteIds) : "");
    }

    private static String posterCatalogKey(Context context, String section, String query, List<String> genres,
                                           String sort, boolean favoritesOnly, String favoriteIds) {
        return new Config(context).pukankiBaseUrl() + "|" + ("series".equals(section) ? "series" : "movies")
                + "|q=" + safe(query) + "|g=" + join(genres) + "|s=" + safe(sort)
                + "|fav=" + favoritesOnly + "|ids=" + (favoritesOnly ? safe(favoriteIds) : "");
    }

    public static List<String> fetchGenres(Context context, String section) throws Exception {
        String base = new Config(context).pukankiBaseUrl();
        Uri uri = Uri.parse(base + "/api/genres").buildUpon()
                .appendQueryParameter("section", "series".equals(section) ? "series" : "movies")
                .build();
        JSONObject root = new JSONObject(get(context, uri.toString()));
        JSONArray arr = root.optJSONArray("genres");
        List<String> result = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            String value = arr.optString(i, "").trim();
            if (!value.isEmpty() && !"adult".equalsIgnoreCase(value)) result.add(value);
        }
        return result;
    }

    public static JSONObject fetchTitleInfo(Context context, String imdbId) throws Exception {
        String base = new Config(context).pukankiBaseUrl();
        return new JSONObject(get(context, base + "/title-info/" + Uri.encode(imdbId)));
    }

    public static String posterUrl(Context context, String imdbId) {
        return new Config(context).pukankiBaseUrl() + "/posters/" + Uri.encode(imdbId) + ".jpg";
    }

    private static List<CatalogItem> onlyItemsWithPosters(Context context, List<CatalogItem> source,
                                                          boolean shortPageTimeout) {
        if (source == null || source.isEmpty()) return source == null ? new ArrayList<>() : source;
        List<Callable<Boolean>> checks = new ArrayList<>();
        for (CatalogItem item : source) {
            checks.add(() -> posterExists(context, item == null ? "" : item.id));
        }
        List<CatalogItem> result = new ArrayList<>();
        try {
            List<Future<Boolean>> futures = shortPageTimeout
                    ? POSTER_CHECKS.invokeAll(checks, 3, TimeUnit.SECONDS)
                    : POSTER_CHECKS.invokeAll(checks);
            for (int i = 0; i < source.size() && i < futures.size(); i++) {
                Future<Boolean> future = futures.get(i);
                if (!future.isCancelled() && Boolean.TRUE.equals(future.get())) result.add(source.get(i));
            }
        } catch (Exception ignored) {
            // The phone path keeps its historical fail-open behavior. The exact TV path keeps
            // whatever checks completed; a normal reload can retry network failures.
            return shortPageTimeout ? source : result;
        }
        return result;
    }

    private static boolean posterExists(Context context, String imdbId) {
        if (imdbId == null || imdbId.trim().isEmpty()) return false;
        String key = new Config(context).pukankiBaseUrl() + "|" + imdbId;
        Boolean cached = POSTER_AVAILABILITY.get(key);
        if (cached != null) return cached;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(posterUrl(context, imdbId)).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(1600);
            c.setReadTimeout(1600);
            c.setUseCaches(true);
            c.setRequestProperty("User-Agent", DeviceIdentity.userAgent(context) + " NyamaPlus/1.0.10");
            int code = c.getResponseCode();
            boolean ok = code >= 200 && code < 300;
            if (code == HttpURLConnection.HTTP_BAD_METHOD) {
                c.disconnect();
                c = (HttpURLConnection) new URL(posterUrl(context, imdbId)).openConnection();
                c.setConnectTimeout(1600);
                c.setReadTimeout(1600);
                c.setRequestProperty("Range", "bytes=0-0");
                c.setRequestProperty("User-Agent", DeviceIdentity.userAgent(context) + " NyamaPlus/1.0.10");
                int fallback = c.getResponseCode();
                ok = (fallback >= 200 && fallback < 300) || fallback == 206;
            }
            POSTER_AVAILABILITY.put(key, ok);
            return ok;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String get(Context context, String source) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(source).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", DeviceIdentity.userAgent(context) + " NyamaPlus/1.0.10");
        c.setRequestProperty("Accept", "application/json,*/*");
        c.setRequestProperty("Cache-Control", "no-cache");
        int status = c.getResponseCode();
        if (status < 200 || status >= 300) {
            c.disconnect();
            throw new IllegalStateException("HTTP " + status);
        }
        try (InputStream in = c.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) >= 0) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String join(List<String> values) {
        if (values == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(value.trim());
        }
        return sb.toString();
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private static final class PosterCatalogSnapshot {
        final List<CatalogItem> items;
        final long createdAt;
        PosterCatalogSnapshot(List<CatalogItem> items, long createdAt) {
            this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
            this.createdAt = createdAt;
        }
    }
}
