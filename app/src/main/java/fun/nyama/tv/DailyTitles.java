package fun.nyama.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Recommendations use only observed poster-validated pages, with no catalog/network requests. */
final class DailyTitles {
    private static String key(Context context, String section) {
        return new Config(context).pukankiBaseUrl() + "|" + section;
    }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("nyama_daily_titles", Context.MODE_PRIVATE);
    }
    static boolean eligible(CatalogItem item) {
        if (item == null || !TvPlaybackSource.validImdb(item.id) || !item.rating.matches("[0-9]+(?:\\.[0-9]+)?")) return false;
        try { double rating = Double.parseDouble(item.rating); return rating >= 7.0 && rating <= 10.0; }
        catch (NumberFormatException invalid) { return false; }
    }
    static void observe(Context context, String section, List<CatalogItem> verified, long now) {
        try {
            String key = key(context, section);
            JSONObject pool = new JSONObject(prefs(context).getString(key + ":pool", "{}"));
            for (String id : new ArrayList<>(keys(pool)))
                if (now - pool.getJSONObject(id).optLong("at") > 86400000L) pool.remove(id);
            for (CatalogItem item : verified) {
                if (!eligible(item)) { pool.remove(item.id); continue; }
                JSONObject o = new JSONObject().put("id", item.id).put("type", item.type).put("title", item.title)
                        .put("originalTitle", item.originalTitle).put("year", item.year).put("endYear", item.endYear)
                        .put("runtime", item.runtime).put("genres", item.genres).put("rating", item.rating)
                        .put("votes", item.votes).put("seasons", item.seasons).put("episodeCount", item.episodeCount).put("at", now);
                pool.put(item.id, o);
            }
            List<String> ids = keys(pool);
            ids.sort(Comparator.comparingLong(id -> pool.optJSONObject(id).optLong("at")));
            while (ids.size() > 240) pool.remove(ids.remove(0));
            prefs(context).edit().putString(key + ":pool", pool.toString()).apply();
        } catch (Exception ignored) { /* Optional presentation must not interrupt browsing. */ }
    }
    static List<CatalogItem> read(Context context, String section, long now) {
        List<CatalogItem> result = new ArrayList<>();
        try {
            SharedPreferences preferences = prefs(context);
            String key = key(context, section);
            String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date(now));
            JSONObject pool = new JSONObject(preferences.getString(key + ":pool", "{}"));
            JSONArray selection;
            if (day.equals(preferences.getString(key + ":day", ""))) selection = new JSONArray(preferences.getString(key + ":selection", "[]"));
            else {
                List<String> candidates = keys(pool);
                Collections.sort(candidates);
                Collections.shuffle(candidates, new Random(day.hashCode() * 31L + section.hashCode()));
                selection = new JSONArray();
                for (String id : candidates) {
                    JSONObject o = pool.getJSONObject(id);
                    if (now - o.optLong("at") <= 86400000L && eligible(CatalogItem.fromJson(o))) selection.put(id);
                    if (selection.length() == 3) break;
                }
                if (selection.length() > 0) preferences.edit().putString(key + ":day", day)
                        .putString(key + ":selection", selection.toString()).apply();
            }
            for (int i = 0; i < selection.length(); i++) {
                JSONObject o = pool.optJSONObject(selection.getString(i));
                if (o == null || now - o.optLong("at") > 86400000L) continue;
                CatalogItem item = CatalogItem.fromJson(o);
                if (eligible(item)) result.add(item);
            }
        } catch (Exception ignored) { }
        return result;
    }
    private static List<String> keys(JSONObject object) {
        List<String> keys = new ArrayList<>(); object.keys().forEachRemaining(keys::add); return keys;
    }
}
