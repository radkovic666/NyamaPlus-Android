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

    ActivationResponse verify(String challenge, String code) throws Exception {
        String id = credentials.deviceId();
        JSONObject body = api.post("verify-code", new JSONObject().put("challenge_id", challenge)
                .put("device_id", id).put("code", code), "");
        credentials.saveToken(id, body.getString("activation_token"));
        return new ActivationResponse(body);
    }

    ActivationResponse config() throws Exception {
        String token = credentials.token();
        if (token.isEmpty()) throw new ActivationApi.Failure("activation_invalid", 0);
        try {
            return new ActivationResponse(api.post("config", new JSONObject().put("device_id", credentials.deviceId()), token));
        } catch (ActivationApi.Failure error) {
            if ("activation_invalid".equals(error.code)) credentials.clearToken(token);
            throw error;
        }
    }

    static String newInstallationId() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : random) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
