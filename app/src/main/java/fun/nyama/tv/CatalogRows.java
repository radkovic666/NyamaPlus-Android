package fun.nyama.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** Local-only rows from pages already poster-validated by PukankiApi. Call off the UI thread. */
final class CatalogRows {
    static final int MAX_CANDIDATES = 240;
    static final long TTL_MS = 86400000L;
    static final class Row {
        final String key, bg, en;
        final List<CatalogItem> items;
        Row(String key, String bg, String en, List<CatalogItem> items) {
            this.key = key; this.bg = bg; this.en = en; this.items = items;
        }
    }
    private static final String[][] MOVIES = {
            {"featured", "Избрани днес", "Selected today"}, {"action", "Екшън", "Action"},
            {"animation", "Анимация", "Animation"}, {"adventure", "Приключенски", "Adventure"},
            {"comedy", "Комедии", "Comedy"}, {"scifi", "Научна фантастика", "Sci-Fi"},
            {"fantasy", "Фентъзи", "Fantasy"}, {"crime", "Криминални", "Crime"},
            {"mystery", "Мистерия", "Mystery"}, {"family", "Семейни", "Family"},
            {"thriller", "Трилъри", "Thriller"}, {"drama", "Драма", "Drama"},
            {"horror", "Ужаси", "Horror"}, {"romance", "Романтични", "Romance"},
            {"documentary", "Документални", "Documentary"}, {"history", "Исторически", "History"},
            {"war", "Военни", "War"}, {"music", "Музикални", "Music"}, {"biography", "Биографични", "Biography"}};
    private static final String[][] SERIES = {
            MOVIES[0], MOVIES[1], MOVIES[4], MOVIES[7], MOVIES[11], MOVIES[5], MOVIES[9]};

    static String categoryLabel(String key, boolean english) {
        for (String[] row : MOVIES) if (row[0].equals(key)) return row[english ? 2 : 1];
        return "";
    }

    // Serialize read/merge/write across Activity recreation. No network operations in this class.
    static synchronized List<Row> observeAndRead(Context context, String section, List<CatalogItem> verified, long now) {
        List<Row> result = new ArrayList<>();
        try {
            SharedPreferences preferences = context.getSharedPreferences("nyama_catalog_rows", 0);
            String key = new Config(context).pukankiBaseUrl() + "|" + section;
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(now));
            boolean newDay = !day.equals(preferences.getString(key + ":day", ""));
            long seed = newDay || preferences.getInt(key + ":selection_version", 0) != 3
                    ? new java.security.SecureRandom().nextLong() : preferences.getLong(key + ":seed", 0);
            JSONObject selection = preferences.getInt(key + ":selection_version", 0) == 3
                    && day.equals(preferences.getString(key + ":day", ""))
                    ? new JSONObject(preferences.getString(key + ":selection", "{}")) : new JSONObject();
            Set<String> pinned = new HashSet<>();
            for (String row : keys(selection)) {
                JSONArray saved = selection.optJSONArray(row);
                if(saved!=null)for(int i=0;i<saved.length();i++)pinned.add(saved.optString(i));
            }
            JSONObject pool = preferences.getInt(key + ":selection_version", 0) == 3
                    ? new JSONObject(preferences.getString(key + ":pool", "{}")) : new JSONObject();
            JSONObject yesterday = new JSONObject(preferences.getString(key + ":selection", "{}"));
            for (String id : keys(pool)) if (!pinned.contains(id) && now - pool.getJSONObject(id).optLong("at") > TTL_MS) pool.remove(id);
            for (CatalogItem item : verified) {
                if (item == null || !TvPlaybackSource.validImdb(item.id)) continue;
                if (!DailyTitles.eligible(item) || !DailyTitles.typeMatches(item, section) || !PukankiApi.hasVerifiedTvPoster(context, item.id)) {
                    pool.remove(item.id); continue;
                }
                pool.put(item.id, new JSONObject().put("id", item.id).put("type", item.type).put("title", item.title)
                        .put("originalTitle", item.originalTitle).put("year", item.year).put("endYear", item.endYear)
                        .put("runtime", item.runtime).put("genres", item.genres).put("rating", item.rating)
                        .put("votes", item.votes).put("seasons", item.seasons).put("episodeCount", item.episodeCount).put("at", now));
            }
            List<String> ids = keys(pool);
            ids.sort(Comparator.<String>comparingInt(id -> pinned.contains(id)?1:0)
                    .thenComparingLong(id -> pool.optJSONObject(id).optLong("at")));
            while (ids.size() > MAX_CANDIDATES) pool.remove(ids.remove(0));
            Set<String> used = new HashSet<>();
            for (String[] definition : "series".equals(section) ? SERIES : MOVIES) {
                List<CatalogItem> chosen = new ArrayList<>();
                JSONArray prior = selection.optJSONArray(definition[0]);
                if (prior != null) for (int i = 0; i < prior.length(); i++) {
                    JSONObject value = pool.optJSONObject(prior.optString(i));
                    if (value != null && DailyTitles.typeMatches(CatalogItem.fromJson(value), section) && matches(CatalogItem.fromJson(value), definition[0])) chosen.add(CatalogItem.fromJson(value));
                }
                // A nonempty saved row stays fixed, including small pools; later pages add missing genres only.
                if (prior == null) {
                    chosen.clear();
                    List<CatalogItem> candidates = new ArrayList<>();
                    for (String id : keys(pool)) {
                        CatalogItem item = CatalogItem.fromJson(pool.getJSONObject(id));
                        if (DailyTitles.typeMatches(item, section) && matches(item, definition[0])) candidates.add(item);
                    }
                    candidates.sort(Comparator.comparing(item -> item.id));
                    List<CatalogItem> unused = new ArrayList<>();
                    for (CatalogItem item : candidates) if (!used.contains(item.id)) unused.add(item);
                    if (!unused.isEmpty()) candidates = unused;
                    Set<String> oldIds = new HashSet<>();
                    if (newDay) {
                        JSONArray old = yesterday.optJSONArray(definition[0]);
                        if (old != null) for (int i = 0; i < old.length(); i++) oldIds.add(old.optString(i));
                    }
                    Set<CatalogItem> old = new HashSet<>(); for (CatalogItem item : candidates) if (oldIds.contains(item.id)) old.add(item);
                    chosen.addAll(DailyTitles.sample(candidates, old, 6, new Random(seed ^ definition[0].hashCode())));
                    if (chosen.isEmpty()) continue;
                    // Avoid an identical row of the same titles under a different heading.
                    Set<String> proposed = new HashSet<>(); for (CatalogItem item : chosen) proposed.add(item.id);
                    boolean duplicate = false;
                    for (Row row : result) {
                        Set<String> previous = new HashSet<>(); for (CatalogItem item : row.items) previous.add(item.id);
                        if (previous.equals(proposed)) duplicate = true;
                    }
                    if (duplicate) continue;
                    JSONArray saved = new JSONArray(); for (CatalogItem item : chosen) saved.put(item.id);
                    selection.put(definition[0], saved);
                }
                if (chosen.isEmpty()) continue;
                for (CatalogItem item : chosen) used.add(item.id);
                result.add(new Row(definition[0], definition[1], definition[2], chosen));
            }
            preferences.edit().putLong(key + ":seed", seed).putString(key + ":pool", pool.toString()).putString(key + ":day", day)
                    .putInt(key + ":selection_version", 3)
                    .putString(key + ":selection", selection.toString()).apply();
        } catch (Exception ignored) { /* Keep normal paginated browsing available. */ }
        return result;
    }

    static boolean matches(CatalogItem item, String genre) {
        if (!DailyTitles.eligible(item)) return false;
        if ("featured".equals(genre)) return true;
        return genreMatches(item.genres, genre);
    }

    private static boolean genreMatches(String genres, String genre) {
        String value = genres == null ? "" : genres.toLowerCase(Locale.ROOT);
        switch (genre) {
            case "action": return value.contains("action") || value.contains("екшън");
            case "comedy": return value.contains("comedy") || value.contains("комеди");
            case "horror": return value.contains("horror") || value.contains("ужас");
            case "family": return value.contains("family") || value.contains("семе");
            case "thriller": return value.contains("thriller") || value.contains("трилър");
            case "drama": return value.contains("drama") || value.contains("драма");
            case "crime": return value.contains("crime") || value.contains("криминал");
            case "animation": return value.contains("animation") || value.contains("animated") || value.contains("анимац");
            case "adventure": return value.contains("adventure") || value.contains("приключ");
            case "fantasy": return value.contains("fantasy") || value.contains("фентъз");
            case "mystery": return value.contains("mystery") || value.contains("мистер") || value.contains("мистери");
            case "romance": return value.contains("romance") || value.contains("romantic") || value.contains("романт");
            case "documentary": return value.contains("documentary") || value.contains("документ");
            case "history": return value.contains("history") || value.contains("historical") || value.contains("историч");
            case "war": return value.matches(".*(?:^|[,;/ ]|\\b)war(?:$|[,;/ ]|\\b).*") || value.contains("военн");
            case "music": return value.contains("music") || value.contains("музик");
            case "biography": return value.contains("biography") || value.contains("biographical") || value.contains("биограф");
            case "scifi": return value.contains("sci-fi") || value.contains("sci fi") || value.contains("science fiction") || value.contains("фантаст");
            default: return false;
        }
    }
    /** Preserve the API's actual labels; never submit an invented or unrelated genre. */
    static List<String> catalogGenres(String category, List<String> available) {
        List<String> result = new ArrayList<>();
        if ("featured".equals(category)) return result;
        for (String genre : available) if (genreMatches(genre, category)) result.add(genre);
        return result;
    }

    private static List<String> keys(JSONObject object) {
        List<String> result = new ArrayList<>(); object.keys().forEachRemaining(result::add); return result;
    }
}
