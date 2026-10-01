package fun.nyama.tv;

import android.app.Application;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public final class NyamaTvApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        DataUsageTracker.checkpoint(this);
        // Migrate/synchronize the autostart preference into device-protected storage and
        // explicitly enable/disable the boot receiver after upgrades.
        AutoStartManager.setEnabled(this, new Config(this).autoStart());
        scheduleRefreshes();
    }

    public void scheduleRefreshes() {
        Constraints network = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();

        PeriodicWorkRequest playlist = new PeriodicWorkRequest.Builder(
                PlaylistRefreshWorker.class, 30, TimeUnit.MINUTES)
                .setConstraints(network)
                .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "nyama_playlist_refresh", ExistingPeriodicWorkPolicy.UPDATE, playlist);

        PeriodicWorkRequest epg = new PeriodicWorkRequest.Builder(
                EpgRefreshWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(network)
                .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "nyama_epg_refresh", ExistingPeriodicWorkPolicy.UPDATE, epg);
    }
}
