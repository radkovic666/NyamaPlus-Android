package fun.nyama.tv;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Handles a TV player return even if Android reclaimed the original Live TV Activity. */
final class PlaylistReturnRefresh {
    interface Refresher { java.util.List<Channel> refresh(Context context) throws Exception; }
    static java.util.concurrent.Executor io = Executors.newSingleThreadExecutor();
    static Refresher refresher = PlaylistRepository::refresh;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final AtomicBoolean RETURN_PENDING = new AtomicBoolean();
    static void markReturn() { RETURN_PENDING.set(true); }
    static boolean consumeReturn() { return RETURN_PENDING.getAndSet(false); }
    static boolean inFlight() { return BUSY.get(); }
    static void request(Context context) {
        Context app = context.getApplicationContext();
        if (new Config(app).isPhoneMode() || !ActivationAccess.playbackAllowed(app) || !BUSY.compareAndSet(false, true)) return;
        Refresher requestRefresher = refresher;
        io.execute(() -> {
            try { MainActivity.applyReturnedPlaylist(requestRefresher.refresh(app)); }
            catch (Exception failure) { NetworkDiagnostics.logFailure(app, "playlist after playback", failure); }
            finally { BUSY.set(false); }
        });
    }
}
