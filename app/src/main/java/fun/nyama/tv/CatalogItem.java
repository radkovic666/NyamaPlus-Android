package fun.nyama.tv;

import org.json.JSONObject;

public final class CatalogItem {
    public final String id;
    public final String type;
    public final String title;
    public final String originalTitle;
    public final String year;
    public final String endYear;
    public final String runtime;
    public final String genres;
    public final String rating;
    public final String votes;
    public final String seasons;
    public final String episodeCount;

    public CatalogItem(String id, String type, String title, String originalTitle, String year,
                       String endYear, String runtime, String genres, String rating, String votes,
                       String seasons, String episodeCount) {
        this.id = safe(id);
        this.type = safe(type);
        this.title = safe(title);
        this.originalTitle = safe(originalTitle);
        this.year = safe(year);
        this.endYear = safe(endYear);
        this.runtime = safe(runtime);
        this.genres = safe(genres);
        this.rating = safe(rating);
        this.votes = safe(votes);
        this.seasons = safe(seasons);
        this.episodeCount = safe(episodeCount);
    }

    public static CatalogItem fromJson(JSONObject o) {
        return new CatalogItem(
                o.optString("id"), o.optString("type"), o.optString("title"),
                o.optString("originalTitle"), o.optString("year"), o.optString("endYear"),
                o.optString("runtime"), o.optString("genres"), o.optString("rating"),
                o.optString("votes"), o.optString("seasons"), o.optString("episodeCount"));
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
