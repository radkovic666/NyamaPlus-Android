package fun.nyama.tv;

import org.json.JSONObject;
import java.net.URI;

/** Server entitlement is independent of permanent device authentication. */
final class ActivationResponse {
    final boolean active;
    final String playlist, epg, pukanki, payment, username, clientNumber;

    ActivationResponse(JSONObject body) throws Exception {
        if (!body.optBoolean("ok")) throw new ActivationApi.Failure(body.optString("error", "server_unavailable"), 0);
        String status = body.optString("access_status");
        active = "active".equals(status);
        if (!active && !"subscription_expired".equals(status))
            throw new ActivationApi.Failure("server_unavailable", 0);
        JSONObject account = body.optJSONObject("account");
        username = account == null ? "" : account.optString("username");
        clientNumber = account == null ? "" : account.optString("client_number");
        if (active) {
            JSONObject config = body.getJSONObject("config");
            playlist = https(config.getString("playlist_url"));
            epg = https(config.optString("epg_url", "https://is.gd/fullepg"));
            pukanki = https(config.optString("pukanki_base_url", "https://pukanki.fun"));
            payment = "";
        } else {
            playlist = epg = pukanki = "";
            payment = paymentUrl(body.getJSONObject("renewal").getString("subscription_payment_url"));
        }
        // token_expires_in/at, including JSON null, never create a local expiry or rotation.
    }

    static String https(String value) throws Exception {
        URI uri = new URI(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new ActivationApi.Failure("server_unavailable", 0);
        return value;
    }

    static String paymentUrl(String value) throws Exception {
        URI uri = new URI(https(value));
        if (!"nyama.fun".equalsIgnoreCase(uri.getHost()) || !"/subscription_payment.php".equals(uri.getPath())
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawQuery() == null
                || uri.getFragment() != null)
            throw new ActivationApi.Failure("server_unavailable", 0);
        boolean tokenPresent = false;
        for (String part : uri.getRawQuery().split("&"))
            if (part.startsWith("token=") && part.length() > 6) tokenPresent = true;
        if (!tokenPresent) throw new ActivationApi.Failure("server_unavailable", 0);
        return value; // Never rebuild or substitute an account's billing URL.
    }
}
