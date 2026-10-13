package fun.nyama.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Process-only entitlement; never restore authorization from cached URLs or account status. */
final class ActivationAccess implements Application.ActivityLifecycleCallbacks {
    private static volatile boolean authorized;
    private static long authorizedAt;
    private static final long CHECK_INTERVAL_MS = 5L * 60L * 1000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Context context;
    private int started;
    private long generation;
    private WeakReference<Activity> foreground = new WeakReference<>(null);

    ActivationAccess(Application application) {
        context = application;
        application.registerActivityLifecycleCallbacks(this);
    }

    static boolean playbackAllowed(Context context) {
        return !ActivationStore.managed(context) || authorized;
    }

    static boolean require(Activity activity) {
        Config config = new Config(activity);
        if (!ActivationStore.managed(activity) && config.isConfigured()) return true;
        if (ActivationStore.managed(activity) && authorized && config.isConfigured()) return true;
        route(activity);
        return false;
    }

    private static void route(Activity activity) {
        MainActivity.pausePlaybackForOnDemand();
        activity.startActivity(new Intent(activity, ActivationActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        activity.finish();
    }

    static boolean accept(Context context, ActivationResponse response) {
        authorized = false;
        Config config = new Config(context);
        if (!config.saveActivation(response)) return false;
        if (!response.active) return true;
        authorizedAt = android.os.SystemClock.elapsedRealtime();
        authorized = true;
        return true;
    }

    static void deny() { authorized = false; }

    private final Runnable refresh = this::refresh;
    private void refresh() {
        Activity current = foreground.get();
        if (current == null || current instanceof ActivationActivity || !ActivationStore.managed(context) || !authorized) return;
        long requestGeneration = generation;
        io.execute(() -> {
            ActivationResponse response = null;
            try {
                ActivationStore store = new ActivationStore(context);
                response = new ActivationController(new ActivationApi()::post, store).config();
            } catch (Exception failure) {
                // The gate retries and displays the precise failure. Do not delete credentials here.
            }
            ActivationResponse result = response;
            main.post(() -> {
                if (requestGeneration != generation || started == 0) return;
                if (result != null && result.active && accept(context, result)) {
                    schedule();
                } else {
                    deny();
                    Activity activity = foreground.get();
                    if (activity != null && !activity.isFinishing()) route(activity);
                }
            });
        });
    }

    private void schedule() {
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, Math.max(1L, authorizedAt + CHECK_INTERVAL_MS - android.os.SystemClock.elapsedRealtime()));
    }

    @Override public void onActivityStarted(Activity activity) { started++; }
    @Override public void onActivityStopped(Activity activity) {
        started--;
        if (started == 0 && !activity.isChangingConfigurations()) {
            authorized = false;
            generation++;
            main.removeCallbacks(refresh);
        }
    }
    @Override public void onActivityResumed(Activity activity) {
        foreground = new WeakReference<>(activity);
        if (!(activity instanceof ActivationActivity) && authorized && ActivationStore.managed(context)) schedule();
    }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
