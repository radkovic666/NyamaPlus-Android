package fun.nyama.tv;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;

/** One process-owned request; rotation never repeats it or retains an Activity. */
final class DeviceDeactivation {
    interface Listener { void changed(); }
    interface Request { ActivationController.Revocation revoke() throws Exception; }
    interface Commit { boolean clear(ActivationController.Revocation result) throws Exception; }
    private static final DeviceDeactivation INSTANCE = new DeviceDeactivation();
    static DeviceDeactivation instance() { return INSTANCE; }
    private final Handler main = new Handler(Looper.getMainLooper());
    Executor io = Executors.newSingleThreadExecutor();
    private WeakReference<Listener> listener = new WeakReference<>(null);
    private boolean busy;
    private String error = "";
    boolean busy() { return busy; }
    String error() { return error; }
    void observe(Listener value) { listener = new WeakReference<>(value); value.changed(); }
    void forget(Listener value) { if (listener.get() == value) listener.clear(); }
    private void notifyChanged() { Listener value = listener.get(); if (value != null) value.changed(); }

    void start(Context context) {
        Context app = context.getApplicationContext();
        if (busy || !ActivationStore.managed(app) || !ActivationAccess.supportsDeviceRevoke()) return;
        ActivationStore store = new ActivationStore(app);
        start(new ActivationController(new ActivationApi()::post, store)::revokeCurrentDevice,
                result -> store.deactivateIfCurrent(result.deviceId, result.token), () -> {
                    ActivationAccess.deny();
                    MainActivity.stopForDeactivation();
                    MoviePlayerActivity.stopForDeactivation();
                    app.startActivity(new Intent(app, ActivationActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                });
    }

    void start(Request request, Commit commit, Runnable success) {
        if (busy) return;
        busy = true; error = ""; ActivationAccess.setDeactivating(true); notifyChanged();
        io.execute(() -> {
            String failure = "";
            try {
                PlaylistRepository.awaitDeactivationQuiescence();
                ActivationController.Revocation result = request.revoke();
                if (!commit.clear(result)) throw new IllegalStateException("stale_activation");
            } catch (Exception problem) {
                failure = problem instanceof ActivationApi.Failure ? ((ActivationApi.Failure)problem).code : "server_unavailable";
            }
            finally { PlaylistRepository.finishDeactivationRequest(); }
            String result = failure;
            main.post(() -> {
                busy = false; error = result;
                if (result.isEmpty()) success.run();
                ActivationAccess.setDeactivating(false);
                notifyChanged();
            });
        });
    }
}
