package fun.nyama.tv;

import java.net.URI;
import org.json.JSONObject;

/** Temporary session, held only in memory. Never serialize or log this object. */
final class PairingSession {
    final String deviceId, qrUrl;
    final long expiresAt;
    final int pollAfter;
    private final String pairId, pairSecret;

    PairingSession(String deviceId, JSONObject body, long now) throws Exception {
        this.deviceId = deviceId;
        pairId = body.getString("pair_id");
        pairSecret = body.getString("pair_secret");
        qrUrl = body.getString("qr_url");
        if (!body.optBoolean("ok") || !deviceId.matches("[0-9a-f]{64}")
                || !pairId.matches("[0-9a-f]{32}") || !pairSecret.matches("[0-9a-f]{64}")) invalid();
        URI uri = new URI(qrUrl);
        if (!"https".equals(uri.getScheme()) || !"nyama.fun".equals(uri.getHost())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !"/register_now.php".equals(uri.getPath()) || uri.getFragment() != null
                || !("pair=" + pairId).equals(uri.getRawQuery()) || qrUrl.contains(pairSecret)) invalid();
        int seconds = body.getInt("expires_in");
        if (seconds < 1 || seconds > 1800) invalid();
        expiresAt = now + seconds * 1000L;
        pollAfter = delay(body);
    }

    JSONObject request() throws Exception {
        return new JSONObject().put("device_id", deviceId).put("pair_id", pairId).put("pair_secret", pairSecret);
    }

    static int delay(JSONObject body) throws Exception {
        int value = body.has("poll_after") ? body.getInt("poll_after") : 3;
        if (value < 1) invalid();
        return Math.max(3, value); // Never poll faster than the normal three-second interval.
    }

    static void invalid() throws ActivationApi.Failure { throw new ActivationApi.Failure("server_unavailable", 0); }
}
