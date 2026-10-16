package fun.nyama.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Conservative coverage of verified TV playback; positions reached by seeking are never coverage. */
final class TvEpisodeProgressTracker {
    static final int MAX_INTERVALS = 256;
    static final long MAX_EVENT_GAP_MS = 15_000L;
    static final double MEANINGFUL_SECONDS = 10d;
    static final double MAX_DURATION_SECONDS = 86_400d;

    final String imdb;
    final int season;
    final int episode;
    private final List<Interval> intervals = new ArrayList<>();
    private double position;
    private double duration;
    private double previousPosition;
    private long previousElapsedMs = -1L;
    private boolean wasPlaying;

    TvEpisodeProgressTracker(String imdb, int season, int episode) {
        if (!validIdentity(imdb, season, episode)) throw new IllegalArgumentException("Invalid episode identity");
        this.imdb = imdb;
        this.season = season;
        this.episode = episode;
    }

    static boolean validIdentity(String imdb, int season, int episode) {
        return imdb != null && imdb.matches("tt[0-9]{7,10}")
                && season >= 0 && season <= 100_000 && episode > 0 && episode <= 100_000;
    }

    static boolean validMeasurement(double position, double duration) {
        return Double.isFinite(position) && Double.isFinite(duration) && duration > 0
                && duration <= MAX_DURATION_SECONDS && position >= 0 && position <= duration + .5d;
    }

    /** Every accepted event has already passed source, identity, type and numeric validation. */
    boolean accept(TvPlayerProgressBridge.Event event, long elapsedMs) {
        if (event == null || !imdb.equals(event.imdb) || season != event.season || episode != event.episode
                || !validMeasurement(event.position, event.duration) || elapsedMs < 0) return false;

        // A different cut/media duration must not inherit intervals from another rendition.
        boolean changedDuration = duration > 0 && Math.abs(event.duration - duration) > Math.max(2d, duration * .02d);
        if (changedDuration) {
            intervals.clear();
            breakContinuity();
            duration = event.duration;
        } else duration = Math.max(duration, event.duration);
        position = Math.min(event.position, duration);

        boolean seek = "seeked".equals(event.status);
        long deltaMs = previousElapsedMs < 0 ? -1 : elapsedMs - previousElapsedMs;
        double deltaPosition = position - previousPosition;
        // The documented heartbeat is approximately five seconds. A late/backgrounded heartbeat,
        // a discontinuity or a seek cannot prove viewing, even if the new position is near the end.
        if (!seek && wasPlaying && deltaMs > 0 && deltaMs <= MAX_EVENT_GAP_MS && deltaPosition > 0
                && deltaPosition <= deltaMs / 1000d * 1.15d + .5d) {
            // Never credit more screen time than real monotonic time elapsed between messages.
            addInterval(previousPosition, previousPosition + Math.min(deltaPosition, deltaMs / 1000d));
        }
        previousElapsedMs = elapsedMs;
        previousPosition = position;
        wasPlaying = "playing".equals(event.status);
        return true;
    }

    void breakContinuity() {
        previousElapsedMs = -1L;
        wasPlaying = false;
    }

    void restore(double position, double duration, List<Interval> stored) {
        if (!validMeasurement(position, duration)) return;
        this.position = Math.min(position, duration);
        this.duration = duration;
        if (stored != null) for (Interval interval : stored) {
            if (interval != null) addInterval(interval.start, interval.end);
        }
        breakContinuity(); // An app restart cannot prove that time between sessions was watched.
    }

    private void addInterval(double start, double end) {
        if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start || duration <= 0) return;
        start = Math.min(start, duration);
        end = Math.min(end, duration);
        if (end <= start) return;
        intervals.add(new Interval(start, end));
        Collections.sort(intervals, Comparator.comparingDouble(item -> item.start));
        for (int index = 1; index < intervals.size();) {
            Interval left = intervals.get(index - 1);
            Interval right = intervals.get(index);
            if (right.start <= left.end) {
                intervals.set(index - 1, new Interval(left.start, Math.max(left.end, right.end)));
                intervals.remove(index);
            } else index++;
        }
        // Keep storage bounded. Dropping the shortest interval can undercount, never invent viewing.
        while (intervals.size() > MAX_INTERVALS) {
            int shortest = 0;
            for (int i = 1; i < intervals.size(); i++)
                if (intervals.get(i).length() < intervals.get(shortest).length()) shortest = i;
            intervals.remove(shortest);
        }
    }

    TvEpisodeProgressStore.Snapshot snapshot() {
        double covered = 0;
        for (Interval interval : intervals) covered += interval.length();
        double fraction = duration > 0 ? Math.min(1d, covered / duration) : 0d;
        boolean watched = fraction >= .9d;
        return new TvEpisodeProgressStore.Snapshot(!watched && covered >= MEANINGFUL_SECONDS,
                watched, position, duration, fraction);
    }

    List<Interval> intervals() { return new ArrayList<>(intervals); }

    static final class Interval {
        final double start;
        final double end;
        Interval(double start, double end) { this.start = start; this.end = end; }
        double length() { return end - start; }
    }
}
