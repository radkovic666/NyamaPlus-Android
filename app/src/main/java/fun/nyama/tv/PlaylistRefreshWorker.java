package fun.nyama.tv;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class PlaylistRefreshWorker extends Worker {
    public PlaylistRefreshWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull @Override public Result doWork() {
        Config config = new Config(getApplicationContext());
        if (config.playlistUrl().isEmpty()) return Result.success();
        long age = System.currentTimeMillis() - config.playlistLastRefresh();
        // Avoid a duplicate network hit when the foreground 30-minute timer just refreshed.
        if (config.playlistLastRefresh() > 0L && age < 29L * 60L * 1000L) return Result.success();
        try {
            PlaylistRepository.refresh(getApplicationContext());
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
