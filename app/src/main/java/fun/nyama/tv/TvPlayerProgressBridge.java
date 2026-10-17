package fun.nyama.tv;

import android.net.Uri;
import android.os.SystemClock;
import android.webkit.WebView;

import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;

/** An origin-scoped receive-only bridge for VidAPI's documented PLAYER_EVENT postMessages. */
final class TvPlayerProgressBridge {
    static final String MAIN_ORIGIN = "https://vaplayer.ru";
    static final String FRAME_ORIGIN = "https://nextgencloudfabric.com";
    static final String OBJECT_NAME = "__nyamaVerifiedEpisodeProgress";
    static final int MAX_EVENT_CHARS = 16_384;
    private static final long SAVE_INTERVAL_MS = 15_000L;
    private static final long RECOVERY_POSITION_MAX_AGE_MS = 15_000L;
    private final WebView view;
    private final TvEpisodeProgressStore store;
    private final TvEpisodeProgressTracker tracker;
    private final String expectedPath;
    private long lastSaveMs;
    private boolean installed;
    private boolean closed;
    private boolean paused;
    private PositionSnapshot lastPosition;
    private Boolean providerPaused;

    TvPlayerProgressBridge(WebView view, TvEpisodeProgressStore store, String imdb, int season, int episode) {
        this.view = view;
        this.store = store;
        tracker = store.load(imdb, season, episode);
        expectedPath = "/embed/tv/" + imdb + "/" + season + "/" + episode;
        lastSaveMs = SystemClock.elapsedRealtime();
    }

    static TvPlayerProgressBridge attach(WebView view, TvEpisodeProgressStore store, String imdb, int season, int episode) {
        if (view == null || !TvEpisodeProgressTracker.validIdentity(imdb, season, episode)) return null;
        try {
            // Old Android TV WebViews may not expose this secure API. Playback remains unchanged.
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return null;
            TvPlayerProgressBridge bridge = new TvPlayerProgressBridge(view, store, imdb, season, episode);
            WebViewCompat.addWebMessageListener(view, OBJECT_NAME, Collections.singleton(MAIN_ORIGIN),
                    (webView, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        if (bridge.closed || webView != bridge.view || !bridge.matchesDocument(webView.getUrl())
                                || message.getType() != WebMessageCompat.TYPE_STRING) return;
                        bridge.receive(message.getData(), sourceOrigin, isMainFrame, SystemClock.elapsedRealtime());
                    });
            bridge.installed = true;
            return bridge;
        } catch (RuntimeException | LinkageError unsupportedWebView) {
            return null; // Never downgrade to addJavascriptInterface() or all-origin injection.
        }
    }

    void installListener(String url) {
        if (closed || !installed || !matchesDocument(url)) return;
        view.evaluateJavascript(listenerScript(tracker.imdb, tracker.season, tracker.episode), null);
    }

    private boolean matchesDocument(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            return sameSecureAuthority(uri, MAIN_ORIGIN) && expectedPath.equals(uri.getPath());
        } catch (RuntimeException invalid) { return false; }
    }

    void receive(String payload, Uri origin, boolean isMainFrame, long elapsedMs) {
        if (closed || paused || !isMainFrame || !exactOrigin(origin, MAIN_ORIGIN)) return;
        Event event = parse(payload, tracker.imdb, tracker.season, tracker.episode);
        if (event == null || !tracker.accept(event, elapsedMs)) return;
        if ("playing".equals(event.status)) providerPaused = false;
        else if ("paused".equals(event.status) || "completed".equals(event.status)) providerPaused = true;
        // Recovery may reuse only a recent measurement from the already verified exact episode.
        // A seeked event updates position, but cannot establish a new play/pause state or coverage.
        lastPosition = new PositionSnapshot(event.position, event.duration, elapsedMs, providerPaused);
        if ("paused".equals(event.status) || "completed".equals(event.status) || elapsedMs - lastSaveMs >= SAVE_INTERVAL_MS) {
            store.save(tracker);
            lastSaveMs = elapsedMs;
        }
    }

    void breakContinuity() { tracker.breakContinuity(); }

    /** Read before closing a failed surface; silence never proves a stalled player. */
    PositionSnapshot recentPosition(long elapsedMs) {
        if (closed || paused || lastPosition == null || elapsedMs < lastPosition.elapsedMs
                || elapsedMs - lastPosition.elapsedMs > RECOVERY_POSITION_MAX_AGE_MS) return null;
        return lastPosition;
    }

    void resume() {
        if (closed) return;
        paused = false;
        tracker.breakContinuity();
    }

    void pause() {
        if (closed) return;
        paused = true;
        lastPosition = null;
        providerPaused = null;
        tracker.breakContinuity();
        store.save(tracker);
        lastSaveMs = SystemClock.elapsedRealtime();
    }

    void close() {
        if (closed) return;
        pause();
        closed = true;
        if (installed) {
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                    WebViewCompat.removeWebMessageListener(view, OBJECT_NAME);
            }
            catch (RuntimeException | LinkageError ignored) { }
            installed = false;
        }
    }

    static boolean exactOrigin(Uri uri, String expected) {
        return sameSecureAuthority(uri, expected) && uri.getQuery() == null && uri.getFragment() == null
                && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()));
    }

    private static boolean sameSecureAuthority(Uri uri, String expected) {
        return uri != null && "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                && uri.getPort() == -1 && expected.equals("https://" + uri.getHost());
    }

    static Event parse(String payload, String expectedImdb, int expectedSeason, int expectedEpisode) {
        if (payload == null || payload.length() > MAX_EVENT_CHARS
                || !TvEpisodeProgressTracker.validIdentity(expectedImdb, expectedSeason, expectedEpisode)) return null;
        try {
            JSONObject root = new JSONObject(payload);
            if (!"PLAYER_EVENT".equals(root.optString("type"))) return null;
            JSONObject data = root.getJSONObject("data");
            JSONObject info = data.getJSONObject("player_info");
            if (!expectedImdb.equals(info.optString("imdb")) || !"tv".equals(info.optString("mediaType"))
                    || !exactInteger(info.opt("season"), expectedSeason)
                    || !exactInteger(info.opt("episode"), expectedEpisode)) return null;
            String status = data.optString("player_status");
            if (!"playing".equals(status) && !"paused".equals(status)
                    && !"seeked".equals(status) && !"completed".equals(status)) return null;
            Object position = data.get("player_progress");
            Object duration = data.get("player_duration");
            if (!(position instanceof Number) || !(duration instanceof Number)) return null;
            double seconds = ((Number) position).doubleValue();
            double length = ((Number) duration).doubleValue();
            if (!TvEpisodeProgressTracker.validMeasurement(seconds, length)) return null;
            return new Event(expectedImdb, expectedSeason, expectedEpisode, status, seconds, length);
        } catch (JSONException | RuntimeException invalid) { return null; }
    }

    private static boolean exactInteger(Object value, int expected) {
        return value instanceof Number && ((Number) value).doubleValue() == expected;
    }

    static String listenerScript(String imdb, int season, int episode) {
        if (!TvEpisodeProgressTracker.validIdentity(imdb, season, episode)) throw new IllegalArgumentException("Invalid episode identity");
        String path = "/embed/tv/" + imdb + "/" + season + "/" + episode;
        // Reads only the outer iframe element's URL and WindowProxy. Its cross-origin DOM is never accessed.
        return "(function(){try{if(window!==window.top||location.origin!=='" + MAIN_ORIGIN
                + "'||location.pathname!=='" + path + "'||window.__nyamaProgressListening)return;"
                + "if(!window." + OBJECT_NAME + "||typeof window." + OBJECT_NAME + ".postMessage!=='function')return;"
                + "window.__nyamaProgressListening=true;window.addEventListener('message',function(e){try{"
                + "var f=document.getElementById('pf');if(!f||e.source!==f.contentWindow||e.origin!=='" + FRAME_ORIGIN + "')return;"
                + "var u=new URL(f.src,location.href);if(u.origin!=='" + FRAME_ORIGIN + "'||u.pathname!=='" + path + "')return;"
                + "var p=e.data;if(!p||typeof p!=='object'||p.type!=='PLAYER_EVENT'||!p.data)return;"
                + "var i=p.data.player_info;if(!i||i.imdb!=='" + imdb + "'||i.mediaType!=='tv'||i.season!==" + season
                + "||i.episode!==" + episode + ")return;"
                + "if(typeof p.data.player_status!=='string'||!/^(playing|paused|seeked|completed)$/.test(p.data.player_status)"
                + "||typeof p.data.player_progress!=='number'||!isFinite(p.data.player_progress)"
                + "||typeof p.data.player_duration!=='number'||!isFinite(p.data.player_duration))return;"
                + "var s=JSON.stringify({type:'PLAYER_EVENT',data:{player_info:{imdb:i.imdb,mediaType:i.mediaType,season:i.season,episode:i.episode},"
                + "player_status:p.data.player_status,player_progress:p.data.player_progress,player_duration:p.data.player_duration}});"
                + "if(s.length>" + MAX_EVENT_CHARS + ")return;"
                + "window." + OBJECT_NAME + ".postMessage(s);}catch(x){}},false);}catch(x){}})();";
    }

    static final class Event {
        final String imdb;
        final int season;
        final int episode;
        final String status;
        final double position;
        final double duration;
        Event(String imdb, int season, int episode, String status, double position, double duration) {
            this.imdb = imdb; this.season = season; this.episode = episode;
            this.status = status; this.position = position; this.duration = duration;
        }
    }

    static final class PositionSnapshot {
        final double position;
        final double duration;
        final long elapsedMs;
        /** Null until a validated playing/paused/completed event establishes the state. */
        final Boolean paused;

        PositionSnapshot(double position, double duration, long elapsedMs, Boolean paused) {
            this.position = position;
            this.duration = duration;
            this.elapsedMs = elapsedMs;
            this.paused = paused;
        }
    }
}
