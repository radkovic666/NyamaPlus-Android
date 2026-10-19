package fun.nyama.tv;

import android.net.Uri;
import java.util.Locale;

/** Shared TV/mobile routes. Player 1 uses the matching embed contract confirmed by the user. */
final class TvPlaybackSource {
    static final String HOST = "vaplayer.ru";
    static final String PLAYER_1_HOST = "vidrock.to";

    private TvPlaybackSource() { }

    static boolean validImdb(String id) { return id != null && id.matches("tt[0-9]{7,10}"); }

    static String host(String player) { return Config.PLAYER_1.equals(player) ? PLAYER_1_HOST : HOST; }

    private static Uri.Builder base(String id, String type, String player) {
        if (!validImdb(id)) throw new IllegalArgumentException("Invalid IMDb identity");
        return new Uri.Builder().scheme("https").authority(host(player))
                .appendPath("embed").appendPath(type).appendPath(id);
    }

    private static String parameters(Uri.Builder uri, String language, int accent, String player) {
        // These options are documented for Player 2; do not invent options for Player 1.
        if (Config.PLAYER_1.equals(player)) return uri.build().toString();
        return uri.appendQueryParameter("ds_lang", "en".equals(language) ? "en" : "bg")
                .appendQueryParameter("autoplay", "1")
                .appendQueryParameter("primaryColor", String.format(Locale.ROOT, "#%06X", accent & 0xFFFFFF))
                .build().toString();
        // Keep provider controls: cross-origin remote behavior has not been verified on hardware.
    }

    static String movie(String imdbId, String language, int accent) {
        return movie(imdbId, language, accent, Config.PLAYER_2);
    }

    static String movie(String imdbId, String language, int accent, String player) {
        return parameters(base(imdbId, "movie", player), language, accent, player);
    }

    /** No exact episode was selected: keep the provider's series entry, without inventing S1E1. */
    static String series(String imdbId, String language, int accent, String player) {
        return parameters(base(imdbId, "tv", player), language, accent, player);
    }

    static String episode(String imdbId, String seriesImdbId, int season, int episode, String language, int accent) {
        return episode(imdbId, seriesImdbId, season, episode, language, accent, Config.PLAYER_2);
    }

    static String episode(String imdbId, String seriesImdbId, int season, int episode, String language, int accent, String player) {
        if (!validImdb(imdbId) || !imdbId.equals(seriesImdbId) || season < 0 || episode <= 0)
            throw new IllegalArgumentException("Invalid episode identity");
        return parameters(base(imdbId, "tv", player).appendPath(Integer.toString(season))
                .appendPath(Integer.toString(episode)), language, accent, player);
    }
}
