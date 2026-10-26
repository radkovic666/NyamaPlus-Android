package fun.nyama.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** TV-only modal captions UI. The media engine renders captions against its own clock. */
final class TvSubtitleController {
    interface Host {
        void evaluate(String script, ValueCallback<String> callback);
        void automatic();
        void pickFile(String language);
        void providerControls();
        void returnFocus();
    }
    private final Activity activity;
    private final Host host;
    private final String origin, path, language;
    private final boolean exactIdentity;
    private final TvSubtitleStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private AlertDialog dialog;
    private ScrollView scroll;
    private LinearLayout content;
    private Snapshot snapshot = new Snapshot();
    private boolean closed, busy, restoring;
    private int generation;
    private String restoredToken = "", message = "", pendingRestoreToken = "", pendingRestoreKey = "";
    private int restoreWaitAttempts;

    TvSubtitleController(Activity activity, Host host, String url, String language) {
        this.activity = activity; this.host = host; this.language = language;
        Uri uri = Uri.parse(url); origin = "https://" + uri.getHost(); path = uri.getPath();
        exactIdentity = path != null && (path.matches("/embed/movie/tt[0-9]{7,10}")
                || path.matches("/embed/tv/tt[0-9]{7,10}/[0-9]+/[1-9][0-9]*"));
        store = new TvSubtitleStore(activity, origin + path);
    }

    boolean isShowing() { return dialog != null && dialog.isShowing(); }
    boolean dispatch(KeyEvent event) { if (isShowing()) { dialog.dispatchKeyEvent(event); return true; } return false; }
    void show() {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
        if (isShowing()) return;
        scroll = new ScrollView(activity); content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(18), dp(8), dp(18), dp(12));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.tv_subtitles_title).setView(scroll)
                .setNegativeButton(R.string.close, (d, which) -> dismiss()).create();
        dialog.setOnDismissListener(d -> { if (!closed) host.returnFocus(); });
        dialog.setOnKeyListener((d, key, event) -> {
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE || key == KeyEvent.KEYCODE_CAPTIONS
                    || key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_INFO) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) dismiss(); return true;
            }
            return false;
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF11161E));
            int width = activity.getResources().getDisplayMetrics().widthPixels;
            int height = activity.getResources().getDisplayMetrics().heightPixels;
            dialog.getWindow().setLayout(Math.min(dp(660), Math.max(1, width - dp(40))), Math.max(1, Math.round(height * .85f)));
        }
        render(); refresh();
    }
    private void refresh() { if (!restorePreference()) inspect(); }
    void dismiss() { if (dialog != null) dialog.dismiss(); dialog = null; }

    private String script(String action, String key, double offset, String imported) {
        return TvSubtitleScript.run(origin, path, action, snapshot.token, key, offset, imported);
    }
    private void inspect() {
        execute("inspect", "", 0, null, result -> {
            snapshot = result;
            if (awaitingTrack(result)) restoreOffset(result, store.read(), false);
            else render();
        });
    }
    private boolean awaitingTrack(Snapshot result) {
        return !pendingRestoreToken.isEmpty() && result.token.equals(pendingRestoreToken)
                && result.selected() != null && result.selected().key.equals(pendingRestoreKey);
    }

    private void execute(String action, String key, double offset, String imported, java.util.function.Consumer<Snapshot> done) {
        if (closed) return;
        int request = ++generation; busy = true; render();
        Runnable timeout = () -> {
            if (closed || request != generation) return;
            generation++; busy = false; restoring = false;
            message = activity.getString(R.string.tv_subtitles_unavailable); render();
        };
        main.postDelayed(timeout, 5000);
        host.evaluate(script(action, key, offset, imported), raw -> {
            if (closed || request != generation) return;
            main.removeCallbacks(timeout); busy = false;
            Snapshot result = Snapshot.parse(raw);
            if (!result.ok) {
                restoring = false;
                if ("inspect".equals(action)) snapshot = result;
                if ("offset".equals(action) && snapshot.selected() != null) snapshot.selected().canOffset = false;
                message = activity.getString(R.string.tv_subtitles_unavailable); render(); return;
            }
            message = ""; done.accept(result);
        });
    }

    private void choose(String mode, Track track, double offset) {
        if (busy) return;
        if ("auto".equals(mode)) {
            pendingRestoreToken = ""; pendingRestoreKey = ""; restoring = false;
            execute("auto", "", 0, null, result -> {
                snapshot = result; if (exactIdentity) store.save("auto", "", "", "", 0); restoredToken = result.token;
                host.automatic(); inspect();
            });
        } else {
            execute(mode, track == null ? "" : track.key, offset, null, result -> {
                snapshot = result; remember(); render();
            });
        }
    }
    private void remember() {
        restoring = false; pendingRestoreToken = ""; pendingRestoreKey = ""; restoreWaitAttempts = 0;
        if (!exactIdentity) return;
        Track selected = snapshot.selected();
        if (selected == null) store.save("off", "", "", "", 0);
        else store.save(!selected.local.isEmpty() ? "local" : !selected.source.isEmpty() ? "track" : "session",
                selected.source, snapshot.fingerprint, selected.local, selected.offset);
        restoredToken = snapshot.token;
    }

    /** Called only at the existing bounded playback-assist opportunities, not by a polling timer. */
    boolean restorePreference() {
        JSONObject saved = store.read(); String mode = saved.optString("mode");
        if (closed || !exactIdentity || mode.isEmpty() || "auto".equals(mode) || "session".equals(mode)) return false;
        if (busy || restoring) return true;
        restoring = true;
        execute("inspect", "", 0, null, result -> {
            snapshot = result;
            if (result.token.equals(restoredToken) || result.manual && !awaitingTrack(result)) { restoring = false; render(); return; }
            if (awaitingTrack(result)) { restoreOffset(result, saved, false); return; }
            if ("off".equals(mode)) {
                execute("off", "", 0, null, applied -> { snapshot = applied; restoredToken = applied.token; restoring = false; render(); });
            } else if ("track".equals(mode)) {
                Track found = null;
                for (Track track : result.tracks) if (!track.source.isEmpty() && track.source.equals(saved.optString("source"))) { found = track; break; }
                if (found == null) { restoring = false; render(); return; }
                execute("select", found.key, 0, null, applied -> restoreOffset(applied, saved, false));
            } else if ("local".equals(mode) && result.canImport) {
                int request = generation;
                files.execute(() -> {
                    try {
                        TvSubtitleFile file = store.load(saved.optString("file"));
                        main.post(() -> {
                            if (closed || generation != request) return;
                            execute("import", "", 0, file.json().toString(), applied -> restoreOffset(applied, saved, true));
                        });
                    } catch (Exception missing) { main.post(() -> { if (!closed && request == generation) { restoring = false; render(); } }); }
                });
            } else { restoring = false; render(); }
        });
        return true;
    }
    private void restoreOffset(Snapshot applied, JSONObject saved, boolean local) {
        snapshot = applied; Track selected = applied.selected(); double offset = saved.optDouble("offset", 0);
        if (!local && selected != null && offset != 0 && validOffset(offset) && applied.fingerprint.isEmpty()) {
            // Selecting a disabled HTML track starts its asynchronous load. Recheck a few times,
            // never block playback, and let an explicit Refresh retry after a very slow load.
            pendingRestoreToken = applied.token; pendingRestoreKey = selected.key; restoring = false;
            if (++restoreWaitAttempts <= 4) main.postDelayed(() -> { if (!closed && !pendingRestoreToken.isEmpty()) restorePreference(); }, 750);
            render(); return;
        }
        pendingRestoreToken = ""; pendingRestoreKey = "";
        boolean sameFile = selected != null && (local ? selected.local.equals(saved.optString("file"))
                : !applied.fingerprint.isEmpty() && applied.fingerprint.equals(saved.optString("fingerprint")));
        if (sameFile && selected.canOffset && validOffset(offset) && offset != 0) {
            execute("offset", selected.key, offset, null, shifted -> {
                snapshot = shifted; restoredToken = shifted.token; restoring = false; render();
            });
        } else { restoredToken = applied.token; restoring = false; render(); }
    }

    void importFile(Uri uri, String selectedLanguage) {
        if (closed || uri == null || !"content".equals(uri.getScheme())) return;
        show(); int request = ++generation; busy = true; render();
        Context app = activity.getApplicationContext();
        files.execute(() -> {
            try {
                String label = "Local " + selectedLanguage.toUpperCase(Locale.ROOT);
                try (Cursor cursor = app.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) label = TvSubtitleFile.clean(cursor.getString(0), 100);
                } catch (RuntimeException noDisplayName) { /* The granted stream can still be readable. */ }
                TvSubtitleFile file;
                try (InputStream input = app.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new java.io.IOException("subtitle_input");
                    file = TvSubtitleFile.read(input, label, selectedLanguage);
                }
                store.cache(file);
                main.post(() -> {
                    if (closed || request != generation) return;
                    busy = false;
                    execute("inspect", "", 0, null, current -> {
                        snapshot = current;
                        execute("import", "", 0, file.json().toString(), applied -> { snapshot = applied; remember(); render(); });
                    });
                });
            } catch (Exception invalid) {
                main.post(() -> {
                    if (closed || request != generation) return;
                    busy = false; message = activity.getString(R.string.tv_subtitles_file_error); render();
                });
            }
        });
    }

    private void render() {
        if (!isShowing() || content == null) return;
        View focused = content.findFocus(); Object tag = focused == null ? null : focused.getTag(); int y = scroll.getScrollY();
        content.removeAllViews();
        text(busy ? activity.getString(R.string.loading) : !message.isEmpty() ? message
                : snapshot.ok ? activity.getString(R.string.tv_subtitles_actual_tracks) : activity.getString(R.string.tv_subtitles_protected));
        button("provider", activity.getString(R.string.tv_subtitles_provider), () -> {
            if (busy) return;
            execute("provider", "", 0, null, result -> { dismiss(); host.providerControls(); });
        }, true);
        if (snapshot.ok) {
            button("auto", (!snapshot.manual ? "● " : "") + activity.getString(R.string.tv_subtitles_auto,
                    "en".equals(language) ? "English" : "български"), () -> choose("auto", null, 0), true);
            button("off", (snapshot.manual && snapshot.selected() == null ? "● " : "") + activity.getString(R.string.tv_subtitles_off), () -> choose("off", null, 0), true);
            int number = 0;
            for (Track track : snapshot.tracks) {
                number++;
                String label = track.label.isEmpty() ? activity.getString(R.string.tv_subtitles_track, number) : track.label;
                if (!track.language.isEmpty()) label += "  ·  " + track.language;
                if ("captions".equals(track.kind)) label += "  ·  " + activity.getString(R.string.tv_subtitles_captions);
                String title = (track.selected ? "● " : "") + label;
                button(track.key, title, () -> choose("select", track, 0), true);
            }
            Track selected = snapshot.selected();
            if (selected != null && selected.canOffset) {
                text(activity.getString(R.string.tv_subtitles_delay, selected.offset));
                LinearLayout timing = new LinearLayout(activity); timing.setOrientation(LinearLayout.HORIZONTAL);
                timing.addView(action("earlier", "−0.5 s", () -> choose("offset", selected, selected.offset - .5), selected.offset > -5), new LinearLayout.LayoutParams(0, dp(54), 1));
                timing.addView(action("reset", activity.getString(R.string.tv_subtitles_reset), () -> choose("offset", selected, 0), true), new LinearLayout.LayoutParams(0, dp(54), 1));
                timing.addView(action("later", "+0.5 s", () -> choose("offset", selected, selected.offset + .5), selected.offset < 5), new LinearLayout.LayoutParams(0, dp(54), 1));
                content.addView(timing); text(activity.getString(R.string.tv_subtitles_delay_help));
            } else if (selected != null) text(activity.getString(R.string.tv_subtitles_delay_unavailable));
            if (snapshot.canImport) {
                text(activity.getString(R.string.tv_subtitles_import_help));
                button("import-bg", activity.getString(R.string.tv_subtitles_import_bg), () -> { dismiss(); host.pickFile("bg"); }, true);
                button("import-en", activity.getString(R.string.tv_subtitles_import_en), () -> { dismiss(); host.pickFile("en"); }, true);
            }
        }
        button("refresh", activity.getString(R.string.tv_subtitles_refresh), this::refresh, true);
        content.post(() -> {
            if (!isShowing()) return;
            View target = tag == null ? null : content.findViewWithTag(tag);
            if (target != null && target.isEnabled()) target.requestFocus();
            else if (content.getChildCount() > 1) content.getChildAt(1).requestFocus();
            scroll.scrollTo(0, y);
        });
    }
    private void text(String value) {
        TextView view = new TextView(activity); view.setText(value); view.setTextColor(0xFFD7DFEA); view.setTextSize(16);
        view.setPadding(0, dp(8), 0, dp(8)); content.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }
    private void button(String tag, String title, Runnable click, boolean enabled) {
        Button button = action(tag, title, click, enabled);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(7);
        content.addView(button, params);
    }
    private Button action(String tag, String title, Runnable click, boolean enabled) {
        Button button = new Button(activity); button.setTag(tag); button.setText(title); button.setAllCaps(false);
        button.setTextSize(16); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD); button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER); button.setMinHeight(dp(52)); button.setPadding(dp(12), dp(9), dp(12), dp(9));
        button.setEnabled(enabled && !busy); button.setAlpha(enabled && !busy ? 1f : .45f); button.setFocusable(true);
        button.setBackground(background(false)); button.setOnFocusChangeListener((view, hasFocus) -> button.setBackground(background(hasFocus)));
        button.setOnClickListener(v -> click.run()); return button;
    }
    private GradientDrawable background(boolean focused) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setCornerRadius(dp(10));
        drawable.setColor(focused ? 0xFF29394A : 0xFF202832); drawable.setStroke(dp(focused ? 3 : 1), focused ? UiTheme.accent(activity) : 0xFF485563);
        return drawable;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    static boolean validOffset(double value) { return Double.isFinite(value) && Math.abs(value) <= 5 && Math.abs(value * 2 - Math.rint(value * 2)) < .001; }
    void pause() { generation++; busy = false; restoring = false; main.removeCallbacksAndMessages(null); dismiss(); }
    void resume() { if (!closed && !pendingRestoreToken.isEmpty()) restorePreference(); }
    void close() { if (closed) return; closed = true; pause(); files.shutdownNow(); }

    static final class Track {
        String key, label, language, kind, source, local;
        boolean selected, canOffset; double offset;
    }
    static final class Snapshot {
        boolean ok, manual, canImport; String token = "", fingerprint = "";
        final List<Track> tracks = new ArrayList<>();
        Track selected() { for (Track track : tracks) if (track.selected) return track; return null; }
        static Snapshot parse(String raw) {
            Snapshot result = new Snapshot();
            if (raw == null || raw.length() > 3 * TvSubtitleFile.MAX_BYTES) return result;
            try {
                Object value = new JSONTokener(raw).nextValue();
                if (value instanceof String) value = new JSONTokener((String) value).nextValue();
                if (!(value instanceof JSONObject)) return result;
                JSONObject data = (JSONObject) value;
                if (!data.optBoolean("ok")) return result;
                result.ok = true; result.token = data.optString("token"); result.manual = data.optBoolean("manual"); result.canImport = data.optBoolean("canImport");
                String signature = data.optString("signature");
                if (!signature.isEmpty() && signature.length() <= TvSubtitleFile.MAX_BYTES) result.fingerprint = TvSubtitleFile.hash(signature);
                JSONArray tracks = data.optJSONArray("tracks");
                if (tracks != null) for (int i = 0; i < Math.min(32, tracks.length()); i++) {
                    JSONObject object = tracks.getJSONObject(i); String kind = object.optString("kind");
                    if (!"subtitles".equals(kind) && !"captions".equals(kind)) continue;
                    Track track = new Track(); track.key = object.getString("key"); track.kind = kind;
                    track.label = TvSubtitleFile.clean(object.optString("label"), 160); track.language = TvSubtitleFile.clean(object.optString("language"), 32);
                    String source = object.optString("source"); track.source = source.isEmpty() ? "" : TvSubtitleFile.hash(source);
                    track.local = object.optString("local"); if (!track.local.matches("[0-9a-f]{64}")) track.local = "";
                    track.selected = object.optBoolean("selected"); track.canOffset = object.optBoolean("canOffset");
                    track.offset = object.optDouble("offset", 0); if (!validOffset(track.offset)) track.offset = 0;
                    result.tracks.add(track);
                }
            } catch (Exception invalid) { return new Snapshot(); }
            return result;
        }
    }
}
