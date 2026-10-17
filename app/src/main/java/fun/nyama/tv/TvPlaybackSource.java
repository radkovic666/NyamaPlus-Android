package fun.nyama.tv;

import android.net.Uri;
import java.util.Locale;

/** TV-only documented VidAPI embeds. Metadata and a valid route do not assert video availability. */
final class TvPlaybackSource {
    static final String HOST = "vaplayer.ru";

    private TvPlaybackSource() { }

    static boolean validImdb(String id) { return id != null && id.matches("tt[0-9]{7,10}"); }

    private static Uri.Builder base(String id, String type) {
        if (!validImdb(id)) throw new IllegalArgumentException("Invalid IMDb identity");
        return new Uri.Builder().scheme("https").authority(HOST)
                .appendPath("embed").appendPath(type).appendPath(id);
    }

    private static String parameters(Uri.Builder uri, String language, int accent) {
        return uri.appendQueryParameter("ds_lang", "en".equals(language) ? "en" : "bg")
                .appendQueryParameter("autoplay", "1")
                .appendQueryParameter("primaryColor", String.format(Locale.ROOT, "#%06X", accent & 0xFFFFFF))
                .build().toString();
        // Keep provider controls: cross-origin remote behavior has not been verified on hardware.
    }

    static String movie(String imdbId, String language, int accent) {
        return parameters(base(imdbId, "movie"), language, accent);
    }

    static String episode(String imdbId, String seriesImdbId, int season, int episode, String language, int accent) {
        if (!validImdb(imdbId) || !imdbId.equals(seriesImdbId) || season < 0 || episode <= 0)
            throw new IllegalArgumentException("Invalid episode identity");
        return parameters(base(imdbId, "tv").appendPath(Integer.toString(season))
                .appendPath(Integer.toString(episode)), language, accent);
    }
}
