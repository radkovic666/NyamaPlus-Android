package fun.nyama.tv;

import org.json.JSONArray;
import org.json.JSONObject;

/** Normalize already-returned TV title metadata; no extra endpoints or invented values. */
final class TvTitleInfo {
    static JSONObject normalize(JSONObject source) throws Exception {
        JSONObject out = new JSONObject(source.toString());
        alias(out, "Plot", "overview", "description", "summary");
        alias(out, "Released", "first_air_date", "release_date");
        alias(out, "Year", "year", "first_air_date");
        alias(out, "imdbRating", "rating", "vote_average");
        alias(out, "imdbVotes", "votes", "vote_count");
        alias(out, "totalSeasons", "number_of_seasons");
        alias(out, "totalEpisodes", "number_of_episodes");
        alias(out, "Runtime", "runtime");
        if (out.optString("Year").matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) out.put("Year", out.getString("Year").substring(0, 4));
        for (String key : new String[]{"totalSeasons", "totalEpisodes", "Runtime"})
            if ("0".equals(out.optString(key))) out.remove(key);
        JSONArray runtime = out.optJSONArray("episode_run_time");
        if (missing(out.optString("Runtime")) && runtime != null && runtime.length() > 0 && runtime.optInt(0) > 0)
            out.put("Runtime", runtime.optInt(0) + " min");
        if (missing(out.optString("Genre"))) {
            JSONArray genres = out.optJSONArray("genres"); StringBuilder names = new StringBuilder();
            if (genres != null) for (int i = 0; i < genres.length(); i++) {
                JSONObject genre = genres.optJSONObject(i);
                String name = genre == null ? (genres.opt(i) instanceof String ? genres.optString(i, "") : "") : genre.optString("name");
                if (!missing(name)) { if (names.length() > 0) names.append(", "); names.append(name); }
            }
            if (names.length() == 0) alias(out, "Genre", "genres"); else out.put("Genre", names.toString());
        }
        return out;
    }
    static String fallback(String original, JSONObject data, String key) {
        return missing(original) ? (missing(data.optString(key)) ? "" : data.optString(key)) : original;
    }
    private static void alias(JSONObject data, String target, String... sources) throws Exception {
        if (!missing(data.optString(target))) return;
        for (String key : sources) {
            Object value = data.opt(key);
            if ((value instanceof String || value instanceof Number) && !missing(value.toString())) { data.put(target, value); return; }
        }
    }
    private static boolean missing(String s) { return s == null || s.trim().isEmpty() || s.equalsIgnoreCase("N/A") || s.equalsIgnoreCase("null") || s.equalsIgnoreCase("unknown") || s.equals("\\N"); }
}
