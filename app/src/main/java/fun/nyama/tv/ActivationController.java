package fun.nyama.tv;

import org.json.JSONObject;
import java.security.SecureRandom;
import java.util.Locale;

/** Small testable boundary for the exact API contract and credential lifetime. */
final class ActivationController {
    interface Transport { JSONObject post(String action, JSONObject body, String token) throws Exception; }
    interface Credentials {
        String deviceId() throws Exception;
        String token() throws Exception;
        void saveToken(String id, String token) throws Exception;
        void clearToken(String expectedToken) throws Exception;
    }
    private final Transport api;
    private final Credentials credentials;

    ActivationController(Transport api, Credentials credentials) { this.api = api; this.credentials = credentials; }

    JSONObject requestCode(String email) throws Exception {
        return api.post("request-code", new JSONObject().put("email", email).put("device_id", credentials.deviceId()), "");
    }

    PairingSession startPairing(long now) throws Exception {
        String id = credentials.deviceId();
        return new PairingSession(id, api.post("pair-start", new JSONObject().put("device_id", id), ""), now);
    }

    PairingResult pollPairing(PairingSession session) throws Exception {
        return new PairingResult(api.post("pair-status", session.request(), ""));
    }

    // Called only after the visible Activity accepts this session's generation. Transport
    // callbacks never persist credentials: late responses cannot replace a newer token.
    ActivationResponse completePairing(PairingSession session, PairingResult result) throws Exception {
        if (!result.activated()) throw new ActivationApi.Failure("server_unavailable", 0);
        credentials.saveToken(session.deviceId, result.activationToken());
        return new ActivationResponse(result.body(), session.deviceId, result.activationToken());
    }

    boolean hasToken() throws Exception { return !credentials.token().isEmpty(); }

    ActivationResponse verify(String challenge, String code) throws Exception {
        String id = credentials.deviceId();
        JSONObject body = api.post("verify-code", new JSONObject().put("challenge_id", challenge)
                .put("device_id", id).put("code", code), "");
        credentials.saveToken(id, body.getString("activation_token"));
        return new ActivationResponse(body, id, body.getString("activation_token"));
    }

    ActivationResponse config() throws Exception {
        String token = credentials.token();
        if (token.isEmpty()) throw new ActivationApi.Failure("activation_invalid", 0);
        try {
            String id = credentials.deviceId();
            return new ActivationResponse(api.post("config", new JSONObject().put("device_id", id), token), id, token);
        } catch (ActivationApi.Failure error) {
            if ("activation_invalid".equals(error.code)) credentials.clearToken(token);
            throw error;
        }
    }

    static final class Revocation {
        final String deviceId, token;
        Revocation(String deviceId, String token) { this.deviceId = deviceId; this.token = token; }
    }

    Revocation revokeCurrentDevice() throws Exception {
        String token = credentials.token();
        if (!token.matches("[0-9a-f]{64}")) throw new ActivationApi.Failure("activation_invalid", 0);
        String id = credentials.deviceId();
        if (!id.matches("[0-9a-f]{64}")) throw new ActivationApi.Failure("invalid_input", 0);
        try {
            JSONObject reply = api.post("revoke", new JSONObject().put("device_id", id), token);
            if (!Boolean.TRUE.equals(reply.opt("revoked")) || !Boolean.TRUE.equals(reply.opt("playlist_binding_released")))
                throw new ActivationApi.Failure("deactivation_unavailable", 0);
        } catch (ActivationApi.Failure failure) {
            if (!"activation_invalid".equals(failure.code)) throw failure;
            // The authoritative server already considers this token revoked. Clear only its stale local state.
        }
        return new Revocation(id, token);
    }

    static String newInstallationId() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : random) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
