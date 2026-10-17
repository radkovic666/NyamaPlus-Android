package fun.nyama.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Local installation-only episode progress. Kept apart from account, favorites and Live TV data. */
public final class TvEpisodeProgressStore {
    static final String PREFERENCES = "nyama_tv_episode_progress";
    private static final int MAX_RECORD_CHARS = 24_576;
    private final SharedPreferences preferences;

    public TvEpisodeProgressStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public Snapshot get(String imdb, int season, int episode) {
        if (!TvEpisodeProgressTracker.validIdentity(imdb, season, episode)) return Snapshot.EMPTY;
        return load(imdb, season, episode).snapshot();
    }

    TvEpisodeProgressTracker load(String imdb, int season, int episode) {
        TvEpisodeProgressTracker tracker = new TvEpisodeProgressTracker(imdb, season, episode);
        try {
            String value = preferences.getString(key(imdb, season, episode), "");
            if (value == null || value.isEmpty() || value.length() > MAX_RECORD_CHARS) return tracker;
            JSONObject record = new JSONObject(value);
            if (record.optInt("version") != 1 || !imdb.equals(record.optString("imdb"))
                    || record.optInt("season", -1) != season || record.optInt("episode", -1) != episode) return tracker;
            JSONArray saved = record.optJSONArray("intervals");
            if (saved == null || saved.length() > TvEpisodeProgressTracker.MAX_INTERVALS) return tracker;
            double position = record.getDouble("position");
            double duration = record.getDouble("duration");
            if (!TvEpisodeProgressTracker.validMeasurement(position, duration)) return tracker;
            List<TvEpisodeProgressTracker.Interval> intervals = new ArrayList<>();
            for (int i = 0; i < saved.length(); i++) {
                JSONArray pair = saved.optJSONArray(i);
                if (pair == null || pair.length() != 2) return tracker;
                double start = pair.getDouble(0), end = pair.getDouble(1);
                if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end > duration || end <= start) return tracker;
                intervals.add(new TvEpisodeProgressTracker.Interval(start, end));
            }
            tracker.restore(position, duration, intervals);
        } catch (JSONException | ClassCastException ignored) {
            // Malformed or obsolete local data never breaks playback or creates a watch flag.
        }
        return tracker;
    }

    void save(TvEpisodeProgressTracker tracker) {
        Snapshot snapshot = tracker.snapshot();
        if (snapshot.durationSeconds <= 0) return; // No provider measurements means no history record.
        try {
            JSONArray intervals = new JSONArray();
            for (TvEpisodeProgressTracker.Interval interval : tracker.intervals())
                intervals.put(new JSONArray().put(interval.start).put(interval.end));
            JSONObject record = new JSONObject().put("version", 1).put("imdb", tracker.imdb)
                    .put("season", tracker.season).put("episode", tracker.episode)
                    .put("position", snapshot.positionSeconds).put("duration", snapshot.durationSeconds)
                    .put("intervals", intervals);
            String storageKey = key(tracker.imdb, tracker.season, tracker.episode);
            String encoded = record.toString();
            try {
                if (encoded.equals(preferences.getString(storageKey, ""))) return;
            } catch (ClassCastException obsoleteValue) {
                // Replace an invalid local record with this verified measurement.
            }
            // apply() updates in-memory readers immediately and queues disk work off the UI thread.
            preferences.edit().putString(storageKey, encoded).apply();
        } catch (JSONException ignored) {
            // Tracking is optional. An invalid measurement must never interrupt playback.
        }
    }

    private static String key(String imdb, int season, int episode) { return imdb + ":" + season + ":" + episode; }

    public static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(false, false, 0, 0, 0);
        public final boolean inProgress;
        public final boolean watched;
        public final double positionSeconds;
        public final double durationSeconds;
        public final double watchedFraction;

        Snapshot(boolean inProgress, boolean watched, double positionSeconds, double durationSeconds, double watchedFraction) {
            this.inProgress = inProgress;
            this.watched = watched;
            this.positionSeconds = positionSeconds;
            this.durationSeconds = durationSeconds;
            this.watchedFraction = watchedFraction;
        }
    }
}
