package fun.nyama.tv;

import android.net.Uri;
import org.json.JSONObject;

/** Exact title identity; a provider's generic series chooser is deliberately unsupported. */
final class SubtitleMedia {
    final String imdb;
    final int season, episode;
    SubtitleMedia(String imdb, int season, int episode) {
        if (!TvPlaybackSource.validImdb(imdb) || !((season == -1 && episode == -1)
                || (season >= 0 && season <= 999 && episode > 0 && episode <= 9999)))
            throw new IllegalArgumentException("identity");
        this.imdb = imdb; this.season = season; this.episode = episode;
    }
    static SubtitleMedia fromUrl(String url) {
        Uri u = Uri.parse(url);
        if (!"https".equals(u.getScheme()) || !TvPlaybackSource.HOST.equals(u.getHost())
                || u.getPort() != -1 || u.getUserInfo() != null) throw new IllegalArgumentException("provider");
        java.util.List<String> p = u.getPathSegments();
        if (p.size() == 3 && "embed".equals(p.get(0)) && "movie".equals(p.get(1)))
            return new SubtitleMedia(p.get(2), -1, -1);
        if (p.size() == 5 && "embed".equals(p.get(0)) && "tv".equals(p.get(1))
                && p.get(3).matches("[0-9]{1,3}") && p.get(4).matches("[0-9]{1,4}"))
            return new SubtitleMedia(p.get(2), Integer.parseInt(p.get(3)), Integer.parseInt(p.get(4)));
        throw new IllegalArgumentException("episode_required");
    }
    JSONObject json() throws Exception {
        JSONObject j = new JSONObject().put("imdb_id", imdb).put("language", "bg");
        if (season >= 0) j.put("season", season).put("episode", episode);
        return j;
    }
    boolean matches(JSONObject j) {
        if (j == null || !imdb.equals(j.optString("imdb_id")) || !"bg".equals(j.optString("language"))) return false;
        return season < 0 ? !j.has("season") && !j.has("episode")
                : j.opt("season") instanceof Number && j.opt("episode") instanceof Number
                && j.optDouble("season", -1) == season && j.optDouble("episode", -1) == episode;
    }
}
