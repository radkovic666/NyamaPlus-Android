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
        return uri.appendQueryParameter("ds_lang", "bg")
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
    /** Compose from the selected route, preserving identity and unrelated query options. */
    static String subtitles(String url, String signedUrl, String label, Double position, Boolean paused) {
        Uri original = Uri.parse(url);
        if (!HOST.equals(original.getHost())) throw new IllegalArgumentException("provider");
        // Fallback also accepts the generic series chooser, without inventing an episode.
        if (!(original.getPathSegments().size() == 3 && "embed".equals(original.getPathSegments().get(0))
                && "tv".equals(original.getPathSegments().get(1)) && validImdb(original.getPathSegments().get(2))))
            SubtitleMedia.fromUrl(url);
        if (!"https".equals(original.getScheme()) || original.getUserInfo() != null || original.getPort() != -1)
            throw new IllegalArgumentException("origin");
        Uri.Builder b = original.buildUpon().clearQuery().fragment(null);
        for (String name : original.getQueryParameterNames()) {
            if (name.equals("ds_lang") || name.matches("sub_url|sub_file|sub_label|sub_lang|sub_default")) continue;
            if (position != null && name.equals("resumeAt")) continue;
            if (paused != null && name.equals("autoplay")) continue;
            for (String value : original.getQueryParameters(name)) b.appendQueryParameter(name, value);
        }
        b.appendQueryParameter("ds_lang", "bg");
        if (signedUrl != null) {
            SubtitleMedia.fromUrl(url); // No external file on an unidentified series episode.
            Uri file = Uri.parse(signedUrl);
            if (!"https".equals(file.getScheme()) || file.getUserInfo() != null || file.getFragment() != null
                    || file.getHost() == null || file.getPort() != -1 || signedUrl.length() > 4096)
                throw new IllegalArgumentException("subtitle_url");
            b.appendQueryParameter("sub_url", signedUrl).appendQueryParameter("sub_lang", "bg")
                    .appendQueryParameter("sub_label", SubtitleApi.label(label)).appendQueryParameter("sub_default", "true");
        }
        if (position != null) {
            if (!Double.isFinite(position) || position < 0 || position > 259200) throw new IllegalArgumentException("position");
            b.appendQueryParameter("resumeAt", Long.toString((long) Math.floor(position)));
        }
        if (paused != null) b.appendQueryParameter("autoplay", paused ? "0" : "1");
        return b.build().toString();
    }

}
