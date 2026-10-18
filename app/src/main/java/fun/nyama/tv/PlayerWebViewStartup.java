package fun.nyama.tv;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.OptIn;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewStartUpConfig;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prepare WebView's engine while browsing; never create a player or navigate a URL. */
final class PlayerWebViewStartup {
    private static final AtomicBoolean requested = new AtomicBoolean();
    private static final Executor worker = Executors.newSingleThreadExecutor();
    interface Starter { void start(Context context, Executor executor, Runnable ready); }
    static Starter starter = PlayerWebViewStartup::startEngine;

    static void prepare(Context context) {
        if (!requested.compareAndSet(false, true)) return;
        Context application = context.getApplicationContext();
        long started = SystemClock.elapsedRealtime();
        Executor safeWorker = task -> worker.execute(() -> {
            try { task.run(); }
            catch (RuntimeException | LinkageError unavailable) {
                Log.d("NyamaPlayer", "WebView background preparation unavailable");
            }
        });
        try {
            starter.start(application, safeWorker, () -> Log.d("NyamaPlayer",
                    "WebView background preparation ready elapsed_ms=" + (SystemClock.elapsedRealtime() - started)));
        } catch (RuntimeException | LinkageError unavailable) {
            Log.d("NyamaPlayer", "WebView background preparation unavailable");
        }
    }

    @OptIn(markerClass = WebViewCompat.ExperimentalAsyncStartUp.class)
    private static void startEngine(Context context, Executor executor, Runnable ready) {
        WebViewCompat.startUpWebView(context, new WebViewStartUpConfig.Builder(executor)
                .setShouldRunUiThreadStartUpTasks(false).build(), result -> ready.run());
    }
}
