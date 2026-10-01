package fun.nyama.tv;

public final class EpgEvent {
    public final long startMs;
    public final long stopMs;
    public final String title;
    public final String description;

    public EpgEvent(long startMs, long stopMs, String title) {
        this(startMs, stopMs, title, "");
    }

    public EpgEvent(long startMs, long stopMs, String title, String description) {
        this.startMs = startMs;
        this.stopMs = stopMs;
        this.title = title == null ? "" : title.trim();
        this.description = description == null ? "" : description.trim();
    }
}
