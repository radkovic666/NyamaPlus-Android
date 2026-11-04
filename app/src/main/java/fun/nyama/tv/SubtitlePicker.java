package fun.nyama.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** User-initiated only. Native dialog has its own focus/window, including HTML5 fullscreen. */
final class SubtitlePicker implements AutoCloseable {
    interface Host {
        String url();
        void apply(SubtitleApi.Selection selection, boolean timing, Runnable applied);
        void focusPlayer();
        default void cancelApply() { }
    }
    private final Activity activity;
    private final Host host;
    private final java.util.function.Supplier<SubtitleApi> clients;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SubtitleApi api;
    private AlertDialog dialog;
    private LinearLayout content, fallbackBar;
    private boolean closed, busy;
    private int generation;
    private String identity;
    private SubtitleMedia media;
    private List<SubtitleApi.Release> releases = Collections.emptyList();
    private SubtitleApi.Selection selected;
    private double offset;
    private int message;
    private int nextPage;
    private final boolean subsource;

    SubtitlePicker(Activity activity, Host host) { this(activity, host, () -> new SubtitleApi(activity.getApplicationContext())); }
    SubtitlePicker(Activity activity, Host host, java.util.function.Supplier<SubtitleApi> clients) {
        this.activity = activity; this.host = host; this.clients = clients; this.subsource = !new Config(activity).isPhoneMode();
    }
    boolean dispatch(KeyEvent event) { if (dialog != null && event != null) dialog.dispatchKeyEvent(event); return true; }
    boolean showing() { return dialog != null && dialog.isShowing(); }
    void show() {
        if (closed || showing()) return;
        String nextIdentity = host.url();
        if (identity != null && !Objects.equals(android.net.Uri.parse(identity).getPath(), android.net.Uri.parse(nextIdentity).getPath())) {
            releases = Collections.emptyList(); selected = null; nextPage = 0; offset = 0; message = 0;
        }
        identity = nextIdentity;
        try { media = SubtitleMedia.fromUrl(identity); } catch (IllegalArgumentException generic) { media = null; }
        boolean vidapi = TvPlaybackSource.HOST.equals(android.net.Uri.parse(identity).getHost());
        if (!vidapi) { Toast.makeText(activity, R.string.subs_player2_only, Toast.LENGTH_LONG).show(); return; }
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(12)); content.setBackgroundColor(0xFF141A23);
        scroll.addView(content);
        View panel = scroll;
        if (subsource) {
            LinearLayout fixed = new LinearLayout(activity); fixed.setOrientation(LinearLayout.VERTICAL); fixed.setBackgroundColor(0xFF141A23);
            fallbackBar = new LinearLayout(activity); fallbackBar.setOrientation(LinearLayout.VERTICAL); fallbackBar.setPadding(dp(18),dp(6),dp(18),0);
            fixed.addView(fallbackBar,new LinearLayout.LayoutParams(-1,-2));fixed.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));panel=fixed;
        }
        dialog = new AlertDialog.Builder(activity).setTitle(subsource ? R.string.subs_subsource_title : R.string.player_subtitles).setView(panel)
                .setNegativeButton(R.string.subs_close, (d, w) -> {}).create();
        dialog.setOnKeyListener((d, code, event) -> {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_MENU
                    || code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_CAPTIONS) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) dismiss();
                return true;
            }
            return false;
        });
        dialog.setOnDismissListener(d -> { cancel(); dialog = null; content = null; fallbackBar = null; if (!closed) host.focusPlayer(); });
        draw();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(Math.min(dp(700), activity.getResources().getDisplayMetrics().widthPixels - dp(24)),
                    (int) (activity.getResources().getDisplayMetrics().heightPixels * .85));
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        if (subsource && fallbackBar != null && fallbackBar.getChildCount() > 0) fallbackBar.getChildAt(0).requestFocus();
        else if (content.getChildCount() > 0) content.getChildAt(0).requestFocus();
    }
    private int dp(int n) { return (int) (n * activity.getResources().getDisplayMetrics().density + .5f); }
    private void text(String value) {
        TextView t = new TextView(activity); t.setText(value); t.setTextColor(Color.WHITE); t.setTextSize(16);
        t.setPadding(dp(4), dp(10), dp(4), dp(10)); content.addView(t);
    }
    private Button button(String value, boolean enabled, Runnable action) {
        Button b = new Button(activity); b.setText(value); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setFocusable(true); b.setEnabled(enabled); b.setTextSize(16); b.setMinHeight(dp(52));
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setOnFocusChangeListener((v, f) -> style(b, f)); style(b, false);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(6); content.addView(b, lp);
        return b;
    }
    private void style(Button b, boolean focused) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(focused ? 0xFF1268A4 : 0xFF283440);
        bg.setCornerRadius(dp(8)); bg.setStroke(dp(focused ? 3 : 1), focused ? Color.WHITE : 0xFF526577); b.setBackground(bg);
    }
    private String s(int id) { return activity.getString(id); }
    private void draw() {
        if (content == null) return;
        String focused = null;
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            if (v instanceof Button && v.hasFocus()) focused = ((Button) v).getText().toString();
        }
        content.removeAllViews();
        boolean custom = android.net.Uri.parse(host.url()).getQueryParameter("sub_url") != null;
        Button automatic = button((custom ? "" : "✓ ") + s(R.string.subs_auto_bg), true, () -> {
            cancel(); draw(); host.apply(null, false, this::dismiss);
        });
        if (fallbackBar != null) {
            boolean restore = fallbackBar.hasFocus(); fallbackBar.removeAllViews(); content.removeView(automatic);
            fallbackBar.addView(automatic,new LinearLayout.LayoutParams(-1,-2)); if (restore) automatic.requestFocus();
        }
        button(s(subsource ? R.string.subs_subsource_search : R.string.subs_search), !busy && media != null, () -> search(1));
        text(s(subsource ? R.string.subs_subsource_attribution : R.string.subs_attribution));
        if (media == null) text(s(R.string.subs_episode_required));
        if (busy) { ProgressBar spinner = new ProgressBar(activity); content.addView(spinner, new LinearLayout.LayoutParams(dp(32), dp(32))); text(s(R.string.subs_loading)); }
        else if (message != 0) text(s(message));
        if (custom) text(s(R.string.subs_selected) + ": " + SubtitleApi.label(android.net.Uri.parse(host.url()).getQueryParameter("sub_label")));
        for (SubtitleApi.Release r : releases) {
            button((selected != null && custom && selected.fileId == r.fileId ? "✓ " : "") + r.display(), !busy, () -> select(r.fileId, 0, false));
        }
        if (subsource && nextPage > 0) button(s(R.string.subs_subsource_more), !busy, () -> search(nextPage));
        if (selected != null && custom) {
            text(activity.getString(R.string.subs_offset, String.format(Locale.ROOT, "%+.1f", offset)));
            button(s(R.string.subs_earlier), !busy && offset > -10, () -> { offset -= .5; draw(); });
            button(s(R.string.subs_later), !busy && offset < 10, () -> { offset += .5; draw(); });
            button(s(R.string.subs_reset), !busy, () -> { offset = 0; draw(); });
            button(s(R.string.subs_apply_offset), !busy, () -> select(selected.fileId, offset, true));
        }
        text(s(R.string.subs_reload_notice));
        if (focused != null) for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            if (v instanceof Button && focused.equals(((Button) v).getText().toString()) && v.isEnabled()) { v.requestFocus(); break; }
        }
    }
    private boolean current(int request, String url) {
        return !closed && showing() && request == generation && url.equals(identity) && identity.equals(host.url())
                && !activity.isFinishing() && !activity.isDestroyed();
    }
    private void search(int page) {
        if (busy || media == null) return;
        final SubtitleMedia requested = media;
        run(client -> {
            SubtitleApi.Page found = client.searchPage(requested, page);
            return () -> {
                List<SubtitleApi.Release> merged = page == 1 ? new ArrayList<>() : new ArrayList<>(releases);
                Set<Long> ids = new HashSet<>(); for (SubtitleApi.Release release : merged) ids.add(release.fileId);
                for (SubtitleApi.Release release : found.releases) if (ids.add(release.fileId) && merged.size() < 400) merged.add(release);
                releases = merged; nextPage = found.next;
                message = releases.isEmpty() ? R.string.subs_empty : 0; draw();
            };
        });
    }
    private void select(long fileId, double shift, boolean timing) {
        if (busy || media == null) return;
        final SubtitleMedia requested = media;
        run(client -> {
            SubtitleApi.Selection result = client.select(requested, fileId, shift);
            return () -> { draw(); host.apply(result, timing, () -> { selected = result; offset = shift; dismiss(); }); };
        });
    }
    interface Job { Runnable call(SubtitleApi client) throws Exception; }
    private void run(Job job) {
        if (closed || !showing() || !identity.equals(host.url())) return;
        cancel(); busy = true; message = 0; draw();
        final int request = generation; final String url = identity;
        api = clients.get();
        final SubtitleApi client = api;
        worker.execute(() -> {
            Runnable done;
            try { done = job.call(client); }
            catch (Exception error) {
                String code = error instanceof SubtitleApi.Failure ? ((SubtitleApi.Failure) error).code : "unavailable";
                int label = "unconfigured".equals(code) ? R.string.subs_unconfigured : "unauthorized".equals(code) ? R.string.subs_unauthorized
                        : "rate_limited".equals(code) ? R.string.subs_rate_limited : R.string.subs_unavailable;
                done = () -> { message = label; draw(); };
            }
            final Runnable result = done;
            main.post(() -> { if (current(request, url)) { busy = false; result.run(); } });
        });
    }
    private void cancel() { generation++; busy = false; host.cancelApply(); if (api != null) { api.close(); api = null; } }
    void dismiss() { if (dialog != null) dialog.dismiss(); }
    void pause() { cancel(); dismiss(); }
    @Override public void close() { closed = true; cancel(); dismiss(); main.removeCallbacksAndMessages(null); worker.shutdownNow(); }
}
