package fun.nyama.tv;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.Executors;

/** Application context only; an Activity's destruction never cancels or duplicates linkage. */
final class DeviceLinking {
    private static DeviceLinkController controller;
    private DeviceLinking() { }

    static synchronized void authorized(Context context, ActivationResponse response) {
        if (!ActivationStore.managed(context) || response.authorizationToken.isEmpty()) return;
        if (controller == null) {
            Context app = context.getApplicationContext();
            controller = new DeviceLinkController(new ActivationStore(app)::matchesAuthorization,
                    (action, body, token) -> new ActivationApi().post(action, body, token),
                    () -> existingPlaylistId(app), Executors.newSingleThreadExecutor(),
                    () -> ActivationStore.managed(app) && ActivationAccess.playbackAllowed(app),
                    SystemClock::elapsedRealtime, code -> Log.w("NyamaDeviceLink", code),
                    Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE);
        }
        controller.authorized(response.authorizationDeviceId, response.authorizationToken);
    }

    private static String existingPlaylistId(Context app) {
        // stableId() creates a nonce if absent. Linking must only observe an existing identity.
        String nonce = app.getSharedPreferences("nyama_device_identity", Context.MODE_PRIVATE)
                .getString("install_nonce", "");
        return nonce == null || nonce.trim().isEmpty() ? null : DeviceIdentity.deviceHeaderValue(app);
    }

    static synchronized void denied() { if (controller != null) controller.denied(); }
    static synchronized void playlistRefreshed() { if (controller != null) controller.playlistRefreshed(); }
}
