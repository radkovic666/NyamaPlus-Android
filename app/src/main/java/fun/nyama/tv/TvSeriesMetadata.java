package fun.nyama.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Episode descriptions are metadata, never a promise of provider playback availability. */
public final class TvSeriesMetadata {
    public final String imdbId;
    public final String source;
    public final String sourceUrl;
    public final List<Season> seasons;
    public final boolean stale;

    public TvSeriesMetadata(String imdbId, String source, String sourceUrl, List<Season> seasons) {
        this(imdbId, source, sourceUrl, seasons, false);
    }

    TvSeriesMetadata(String imdbId, String source, String sourceUrl, List<Season> seasons, boolean stale) {
        this.imdbId = text(imdbId);
        this.source = text(source);
        this.sourceUrl = text(sourceUrl);
        this.seasons = Collections.unmodifiableList(new ArrayList<>(seasons));
        this.stale = stale;
    }

    TvSeriesMetadata asStale() {
        return new TvSeriesMetadata(imdbId, source, sourceUrl, seasons, true);
    }

    public static final class Season {
        public final int number;
        public final List<Episode> episodes;

        public Season(int number, List<Episode> episodes) {
            this.number = number;
            this.episodes = Collections.unmodifiableList(new ArrayList<>(episodes));
        }
    }

    public static final class Episode {
        public final int season;
        public final int number;
        public final String title;
        public final int durationMinutes;
        public final String summary;
        /** Complete plain-text description with readable source paragraph breaks. */
        public final String fullSummary;
        /** Verified source calendar date (yyyy-MM-dd), or empty when it was not supplied. */
        public final String airDate;
        public final String stillUrl;
        /** Original-size artwork is separate from the compact episode-thumbnail source. */
        public final String originalStillUrl;
        /** -1 means that the source supplied no verifiable air date. */
        public final long airStampMs;
        private final long snapshotNowMs;

        public Episode(int season, int number, String title, int durationMinutes, String summary,
                       String stillUrl, long airStampMs, long snapshotNowMs) {
            this(season, number, title, durationMinutes, summary, stillUrl, stillUrl, airStampMs, snapshotNowMs);
        }

        public Episode(int season, int number, String title, int durationMinutes, String summary,
                       String stillUrl, String originalStillUrl, long airStampMs, long snapshotNowMs) {
            this(season, number, title, durationMinutes, summary, stillUrl, originalStillUrl,
                    airStampMs, snapshotNowMs, summary, "");
        }

        public Episode(int season, int number, String title, int durationMinutes, String summary,
                       String stillUrl, String originalStillUrl, long airStampMs, long snapshotNowMs,
                       String fullSummary, String airDate) {
            this.season = season;
            this.number = number;
            this.title = text(title);
            this.durationMinutes = durationMinutes > 0 ? durationMinutes : -1;
            this.summary = text(summary);
            this.fullSummary = text(fullSummary);
            this.airDate = text(airDate);
            this.stillUrl = text(stillUrl);
            this.originalStillUrl = text(originalStillUrl);
            this.airStampMs = airStampMs;
            this.snapshotNowMs = snapshotNowMs;
        }

        /** Only establishes that it has aired. Actual provider availability remains unknown. */
        public boolean eligibleForPlayback() {
            return season >= 0 && number > 0 && airStampMs >= 0 && airStampMs <= snapshotNowMs;
        }
    }

    private static String text(String value) { return value == null ? "" : value; }
}
