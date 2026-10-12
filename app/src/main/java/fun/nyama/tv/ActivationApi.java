package fun.nyama.tv;

import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** No cookies, redirects, logging or custom TLS verification for bearer requests. */
final class ActivationApi {
    static final class Failure extends Exception {
        final String code;
        final int retryAfter;
        Failure(String code, int retryAfter) { super(code); this.code = code; this.retryAfter = retryAfter; }
    }

    JSONObject post(String action, JSONObject body, String token) throws Exception {
        if (!action.matches("request-code|verify-code|config|revoke")) throw new IllegalArgumentException();
        HttpsURLConnection connection = (HttpsURLConnection) new URL(
                "https://nyama.fun/api/activation.php?action=" + action).openConnection();
        connection.setRequestMethod("POST");
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(data.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(data); }
            int status = connection.getResponseCode();
            int retry = 0;
            try { retry = Math.max(0, Integer.parseInt(connection.getHeaderField("Retry-After"))); }
            catch (Exception ignored) { /* Missing or HTTP-date header: use a safe cooldown. */ }
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) throw new Failure("server_unavailable", retry);
            JSONObject result;
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 65536) throw new Failure("server_unavailable", retry);
                    out.write(buffer, 0, count);
                }
                try { result = new JSONObject(out.toString("UTF-8")); }
                catch (Exception invalid) { throw new Failure("server_unavailable", retry); }
            }
            if (status != 200 || !result.optBoolean("ok"))
                throw new Failure(result.optString("error", "server_unavailable"),
                        Math.max(retry, result.optInt("resend_after", result.optInt("retry_after", 0))));
            return result;
        } finally { connection.disconnect(); }
    }
}
