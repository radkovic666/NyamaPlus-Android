package fun.nyama.tv;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

public final class HttpUtil {
    private HttpUtil() {}

    public static void downloadToFile(Context context, String source, File target) throws IOException {
        if (source == null || source.trim().isEmpty()) throw new IOException("URL is empty");
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        if (tmp.exists()) //noinspection ResultOfMethodCallIgnored
            tmp.delete();

        HttpURLConnection connection = null;
        try {
            connection = open(context, source.trim(), 0);
            InputStream response = connection.getInputStream();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                response = new GZIPInputStream(response);
            }
            try (InputStream in = new BufferedInputStream(response);
             FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                out.flush();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }

        if (target.exists() && !target.delete()) throw new IOException("Cannot replace cached file");
        if (!tmp.renameTo(target)) {
            throw new IOException("Cannot commit downloaded file");
        }
    }

    private static HttpURLConnection open(Context context, String source, int redirectCount) throws IOException {
        if (redirectCount > 8) throw new DownloadException("redirect-limit", 0, "Too many redirects");
        URL url = new URL(source);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", DeviceIdentity.userAgent(context));
        // The User-Agent itself is stable and unique per device so the original auth_playlist.php can bind correctly.
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
        if (host.equals("nyama.fun") || host.endsWith(".nyama.fun")) {
            c.setRequestProperty("X-Nyama-Device-ID", DeviceIdentity.deviceHeaderValue(context));
        }
        c.setRequestProperty("Accept", "*/*");
        // Explicit compression support avoids relying on vendor-specific transparent
        // decompression behavior in older Android TV HttpURLConnection implementations.
        c.setRequestProperty("Accept-Encoding", "gzip");
        // A fresh-start playlist refresh must reach the current server response rather
        // than an HTTP/proxy cache. This is also safe for scheduled EPG refreshes.
        c.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
        c.setRequestProperty("Pragma", "no-cache");
        c.setUseCaches(false);
        int status = c.getResponseCode();
        if (status >= 300 && status < 400) {
            String location = c.getHeaderField("Location");
            c.disconnect();
            if (location == null || location.isEmpty()) {
                throw new DownloadException("redirect", status, "HTTP redirect without Location");
            }
            URL destination = new URL(url, location);
            NetworkDiagnostics.logInfo("HTTP redirect " + status + "; fromHost="
                    + safeHost(url) + "; destinationHost=" + safeHost(destination));
            return open(context, destination.toString(), redirectCount + 1);
        }
        if (status < 200 || status >= 300) {
            c.disconnect();
            throw new DownloadException("http", status, "HTTP status " + status
                    + "; host=" + safeHost(url));
        }
        NetworkDiagnostics.logInfo("HTTP response status=" + status + "; host=" + safeHost(url)
                + "; encoding=" + (c.getContentEncoding() == null ? "identity" : c.getContentEncoding()));
        return c;
    }

    private static String safeHost(URL url) {
        String host = url.getHost();
        return host == null || host.isEmpty() ? "unknown" : host.toLowerCase(Locale.US);
    }

    static final class DownloadException extends IOException {
        final String reason;
        final int statusCode;

        DownloadException(String reason, int statusCode, String message) {
            super(message);
            this.reason = reason;
            this.statusCode = statusCode;
        }
    }
}
