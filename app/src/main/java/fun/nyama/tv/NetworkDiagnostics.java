package fun.nyama.tv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.SSLException;

final class NetworkDiagnostics {
    private static final String TAG = "NyamaNetwork";

    private NetworkDiagnostics() {}

    static void logFailure(Context context, String operation, Throwable error) {
        Throwable root = rootCause(error);
        Log.e(TAG, operation + " failed; device=" + deviceSummary()
                + "; network=" + networkSummary(context)
                + "; exception=" + error.getClass().getName()
                + "; message=" + safeMessage(error)
                + "; rootCause=" + root.getClass().getName()
                + "; rootMessage=" + safeMessage(root)
                + "; category=" + category(root));
    }

    static void logInfo(String message) {
        Log.i(TAG, message);
    }

    static String networkSummary(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return "connectivity-service-unavailable";
            Network network = manager.getActiveNetwork();
            if (network == null) return "no-active-network";
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities == null) return "active-network-capabilities-unavailable";
            String transport;
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transport = "ethernet";
            else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transport = "wifi";
            else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transport = "cellular";
            else transport = "other";
            return transport + ",internet="
                    + capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    + ",validated="
                    + capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return "connectivity-check-failed:" + e.getClass().getSimpleName();
        }
    }

    private static String deviceSummary() {
        return clean(Build.MANUFACTURER) + " " + clean(Build.MODEL)
                + ",sdk=" + Build.VERSION.SDK_INT + ",android=" + clean(Build.VERSION.RELEASE);
    }

    private static String category(Throwable error) {
        if (error instanceof UnknownHostException) return "dns";
        if (error instanceof SocketTimeoutException) return "timeout";
        if (error instanceof ConnectException || error instanceof NoRouteToHostException) return "connection";
        if (error instanceof SSLException) return "tls";
        if (error instanceof HttpUtil.DownloadException) {
            HttpUtil.DownloadException download = (HttpUtil.DownloadException) error;
            if (download.statusCode > 0) return "http-status-" + download.statusCode;
            return download.reason;
        }
        return "other";
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        Set<Throwable> seen = new HashSet<>();
        while (current.getCause() != null && seen.add(current)) current = current.getCause();
        return current;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "(none)";
        // Exception messages from platform network stacks can contain the full request URL.
        // Never put paths, query strings, playlist tokens, or credentials in Logcat.
        message = message.replaceAll("(?i)https?://[^\\s]+", "[redacted-url]");
        message = message.replaceAll("\\?[^\\s]+", "?[redacted]");
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static String clean(String value) {
        if (value == null) return "unknown";
        return value.replaceAll("[\\r\\n;]", "_");
    }
}
