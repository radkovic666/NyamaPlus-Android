package fun.nyama.tv;

import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** No cookies, redirects, logging or custom TLS verification for bearer requests. */
final class ActivationApi {
    private HttpsURLConnection active;
    private boolean cancelled;

    synchronized void cancel() {
        cancelled = true;
        if (active != null) active.disconnect();
    }
    static final class Failure extends Exception {
        final String code;
        final int retryAfter;
        Failure(String code, int retryAfter) { super(code); this.code = code; this.retryAfter = retryAfter; }
    }

    JSONObject post(String action, JSONObject body, String token) throws Exception {
        if (!action.matches("request-code|verify-code|config|revoke|pair-start|pair-status")) throw new IllegalArgumentException();
        HttpsURLConnection connection = (HttpsURLConnection) new URL(
                "https://nyama.fun/api/activation.php?action=" + action).openConnection();
        synchronized (this) {
            if (cancelled) { connection.disconnect(); throw new java.io.InterruptedIOException(); }
            active = connection;
        }
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
            int retry = retryDelay(connection.getHeaderField("Retry-After"), System.currentTimeMillis());
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
                        retrySeconds(result, retry));
            return result;
        } finally {
            synchronized (this) { if (active == connection) active = null; }
            connection.disconnect();
        }
    }

    static int retrySeconds(JSONObject result, int header) {
        return Math.max(Math.max(0, header), Math.max(seconds(result, "retry_after"), seconds(result, "resend_after")));
    }

    private static int seconds(JSONObject result, String field) {
        Object raw = result.opt(field);
        if (!(raw instanceof Number)) return 0;
        double value = ((Number) raw).doubleValue();
        if (value < 0 || value > Integer.MAX_VALUE || value != Math.floor(value)) return 0;
        return ((Number) raw).intValue();
    }

    static int retryDelay(String header, long now) {
        if (header == null) return 0;
        try { return Math.max(0, Integer.parseInt(header.trim())); }
        catch (NumberFormatException notSeconds) {
            try {
                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US);
                format.setLenient(false);
                format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
                long millis = format.parse(header).getTime() - now;
                return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (millis + 999L) / 1000L));
            } catch (Exception invalid) { return 0; }
        }
    }
}
