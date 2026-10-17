package fun.nyama.tv;

import org.json.JSONObject;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Optional, process-session linkage. Never writes credentials, downloads playlists or grants access. */
final class DeviceLinkController {
    interface Authorization { boolean matches(String deviceId, String token) throws Exception; }
    interface PlaylistId { String get() throws Exception; }
    private enum State { READY, WAITING, RETRY, LINKED, STOPPED }
    private static final class Session {
        final String id, token;
        State state = State.READY;
        long retryAt;
        Session(String id, String token) { this.id = id; this.token = token; }
    }
    private final Authorization credentials;
    private final ActivationController.Transport api;
    private final PlaylistId playlistId;
    private final Executor executor;
    private final BooleanSupplier allowed;
    private final LongSupplier clock;
    private final Consumer<String> diagnostic;
    private final String manufacturer, model, androidVersion;
    private Session session;
    private boolean inFlight;
    private long playlistSequence;

    DeviceLinkController(Authorization credentials, ActivationController.Transport api,
            PlaylistId playlistId, Executor executor, BooleanSupplier allowed, LongSupplier clock,
            Consumer<String> diagnostic, String manufacturer, String model, String androidVersion) {
        this.credentials = credentials;
        this.api = api;
        this.playlistId = playlistId;
        this.executor = executor;
        this.allowed = allowed;
        this.clock = clock;
        this.diagnostic = diagnostic;
        this.manufacturer = display(manufacturer, 80);
        this.model = display(model, 80);
        this.androidVersion = display(androidVersion, 32);
    }

    synchronized void authorized(String id, String token) {
        if (!hex(id, 64) || !hex(token, 64)) { diagnostic.accept("invalid_input"); return; }
        if (session == null || !session.id.equals(id) || !session.token.equals(token))
            session = new Session(id, token);
        if (session.state == State.READY) schedule();
    }

    synchronized void denied() { session = null; }

    synchronized void playlistRefreshed() {
        playlistSequence++;
        if (session != null && (session.state == State.WAITING || session.state == State.RETRY)) schedule();
    }

    private void schedule() {
        if (inFlight || session == null || clock.getAsLong() < session.retryAt) return;
        Session request = session;
        long observedPlaylist = playlistSequence;
        inFlight = true;
        executor.execute(() -> link(request, observedPlaylist));
    }

    private boolean currentCredentials(Session request) throws Exception {
        return credentials.matches(request.id, request.token);
    }

    private void link(Session request, long observedPlaylist) {
        State result = State.STOPPED;
        String event = null;
        long retryAt = 0;
        try {
            synchronized (this) { if (session != request) return; }
            if (!currentCredentials(request)) return;
            if (!allowed.getAsBoolean()) { result = State.READY; return; }
            String playlist = playlistId.get();
            if (playlist == null) {
                // No existing playlist nonce yet: let ordinary playlist I/O create it, never linking.
                result = State.WAITING;
            } else if (!hex(playlist, 32)) {
                event = "invalid_input";
            } else {
                JSONObject body = new JSONObject().put("device_id", request.id).put("playlist_device_id", playlist)
                        .put("manufacturer", manufacturer).put("model", model).put("android_version", androidVersion);
                JSONObject response = api.post("device-link", body, request.token);
                if (!Boolean.TRUE.equals(response.opt("ok")))
                    throw new ActivationApi.Failure(safeError(response.optString("error")), 0);
                if (Boolean.TRUE.equals(response.opt("linked")) && "linked".equals(response.optString("state")))
                    result = State.LINKED;
                else if (Boolean.FALSE.equals(response.opt("linked")) && "awaiting_playlist".equals(response.optString("state")))
                    result = State.WAITING;
                else throw new ActivationApi.Failure("server_unavailable", 0);
            }
        } catch (ActivationApi.Failure failure) {
            event = safeError(failure.code);
            if ("server_unavailable".equals(event)) {
                result = State.RETRY;
                retryAt = clock.getAsLong() + Math.max(0L, (long) failure.retryAfter) * 1000L;
            }
        } catch (Exception unavailable) {
            event = "server_unavailable";
            result = State.RETRY;
        } finally {
            boolean current = false;
            try { current = currentCredentials(request); } catch (Exception ignored) { }
            synchronized (this) {
                inFlight = false;
                if (session == request) {
                    // Revocation/token changes during I/O cannot mark newer credentials linked.
                    if (!current) session = null;
                    else {
                        session.state = result;
                        session.retryAt = retryAt;
                        if (event != null) diagnostic.accept(event); // Fixed codes only; never server text.
                        // A normal playlist may finish while the initial link request is in flight.
                        if ((result == State.WAITING || result == State.RETRY) && playlistSequence > observedPlaylist)
                            schedule();
                    }
                } else if (session != null && session.state == State.READY) schedule();
            }
        }
    }

    private static boolean hex(String value, int length) {
        return value != null && value.matches("[0-9a-f]{" + length + "}");
    }

    static String display(String value, int limit) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
        if (clean.equalsIgnoreCase("unknown") || clean.equalsIgnoreCase("null")) return "";
        int count = clean.codePointCount(0, clean.length());
        return count <= limit ? clean : clean.substring(0, clean.offsetByCodePoints(0, limit)).trim();
    }

    private static String safeError(String code) {
        switch (code) {
            case "activation_invalid": case "inactive_account": case "email_unverified":
            case "invalid_input": case "device_link_conflict": case "device_link_ambiguous": return code;
            default: return "server_unavailable";
        }
    }
}
