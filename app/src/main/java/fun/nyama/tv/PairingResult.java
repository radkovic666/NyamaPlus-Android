package fun.nyama.tv;

import org.json.JSONObject;

/** Response parsing does not grant entitlement or save a token on a worker thread. */
final class PairingResult {
    final String status;
    final int pollAfter;
    private final JSONObject response;

    PairingResult(JSONObject body) throws Exception {
        if (!body.optBoolean("ok")) PairingSession.invalid();
        status = body.getString("pairing_status");
        if (!"waiting_for_scan".equals(status) && !"waiting_for_confirmation".equals(status)
                && !"activated".equals(status)) PairingSession.invalid();
        pollAfter = PairingSession.delay(body);
        if (activated()) {
            if (!body.getString("activation_token").matches("[0-9a-f]{64}")) PairingSession.invalid();
            String access = body.optString("access_status");
            if (!"active".equals(access) && !"subscription_expired".equals(access)) PairingSession.invalid();
        }
        response = body;
    }

    boolean activated() { return "activated".equals(status); }
    String activationToken() throws Exception { return response.getString("activation_token"); }
    JSONObject body() { return response; }
}
