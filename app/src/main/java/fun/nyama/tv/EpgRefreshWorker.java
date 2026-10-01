package fun.nyama.tv;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class EpgRefreshWorker extends Worker {
    public EpgRefreshWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull @Override public Result doWork() {
        Config config = new Config(getApplicationContext());
        long age = System.currentTimeMillis() - config.epgLastRefresh();
        if (config.epgLastRefresh() > 0L && age < Config.EPG_REFRESH_MS) return Result.success();
        try {
            EpgRepository.refresh(getApplicationContext());
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
