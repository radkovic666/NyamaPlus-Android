package fun.nyama.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TitleDetailActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Config config;
    private MovieFavorites favorites;
    private String id;
    private String section;
    private String title;
    private Button favoriteButton;
    private TextView details;
    private ProgressBar loading;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = new Config(this);
        favorites = new MovieFavorites(this);
        id = safe(getIntent().getStringExtra("id"));
        section = "series".equals(getIntent().getStringExtra("type")) ? "series" : "movies";
        title = safe(getIntent().getStringExtra("title"));
        setContentView(buildUi());
        loadInfo();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        boolean phone = config.isPhoneMode();
        int accent = UiTheme.accent(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xD6090B0E);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(phone ? 18 : 40), dp(phone ? 16 : 28), dp(phone ? 18 : 40), dp(26));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout top = horizontal();
        Button back = plainButton("‹", 18, 0xFF4D535B, 0xFF6B7179, Color.WHITE);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(58), dp(50)));
        if (!phone) {
            TextView heading = text(title.isEmpty() ? getString(R.string.title_information) : title, 33, Color.WHITE, true);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1f); hp.setMargins(dp(12),0,0,0);
            top.addView(heading, hp);
        }
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(phone ? 58 : 64)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(phone ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.TOP);
        root.addView(body, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout posterWrap = new LinearLayout(this);
        posterWrap.setOrientation(LinearLayout.VERTICAL);
        posterWrap.setGravity(phone ? Gravity.CENTER_HORIZONTAL : Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        body.addView(posterWrap, new LinearLayout.LayoutParams(phone ? -1 : dp(300), -2));

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        poster.setBackgroundColor(0xFF1B2026);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(phone ? 220 : 280), dp(phone ? 330 : 420));
        if (phone) pp.gravity = Gravity.CENTER_HORIZONTAL;
        posterWrap.addView(poster, pp);
        PosterLoader.load(this, id, poster);

        if (phone) {
            TextView titleBelow = text(title.isEmpty() ? getString(R.string.title_information) : title, 23, Color.WHITE, true);
            titleBelow.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
            tp.topMargin = dp(12);
            posterWrap.addView(titleBelow, tp);
        }

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(phone ? 0 : 30), dp(phone ? 16 : 0), 0, 0);
        body.addView(info, new LinearLayout.LayoutParams(phone ? -1 : 0, -2, phone ? 0f : 1f));

        TextView meta = text(initialMeta(), 17, 0xFFB9C1CA, false);
        if (phone) meta.setGravity(Gravity.CENTER_HORIZONTAL);
        else meta.setVisibility(View.GONE); // TV metadata is rendered once in the detailed information block below.
        info.addView(meta);

        LinearLayout actions = horizontal();
        actions.setGravity(phone ? Gravity.CENTER_HORIZONTAL : Gravity.START | Gravity.CENTER_VERTICAL);
        Button watch = primaryButton(getString(R.string.watch_now), phone ? 18 : 15, accent);
        watch.setOnClickListener(v -> openPlayer());
        favoriteButton = secondaryButton("", phone ? 17 : 14, accent);
        favoriteButton.setOnClickListener(v -> { favorites.toggle(id); updateFavoriteButton(); });
        updateFavoriteButton();
        if (phone) {
            actions.addView(watch, new LinearLayout.LayoutParams(0, dp(54), 1f));
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, dp(54), 1f); fp.setMargins(dp(10),0,0,0);
            actions.addView(favoriteButton, fp);
        } else {
            // Android TV: deliberately compact buttons so the poster/info remains the focus.
            actions.addView(watch, new LinearLayout.LayoutParams(dp(154), dp(44)));
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(184), dp(44)); fp.setMargins(dp(9),0,0,0);
            actions.addView(favoriteButton, fp);
        }
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2); ap.setMargins(0, dp(18), 0, dp(16));
        info.addView(actions, ap);

        loading = new ProgressBar(this);
        loading.setIndeterminate(true);
        UiTheme.tintProgress(this, loading);
        info.addView(loading, new LinearLayout.LayoutParams(dp(34), dp(34)));

        details = text(getString(R.string.loading), 16, 0xFFD2D7DD, false);
        details.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(-1, -2);
        dp2.topMargin = dp(10);
        info.addView(details, dp2);

        watch.requestFocus();
        return scroll;
    }

    private String initialMeta() {
        StringBuilder sb = new StringBuilder();
        if (config.isPhoneMode()) {
            // Preserve the existing phone title-summary behavior exactly.
            String year = safe(getIntent().getStringExtra("year"));
            String endYear = safe(getIntent().getStringExtra("endYear"));
            String rating = safe(getIntent().getStringExtra("rating"));
            String genres = safe(getIntent().getStringExtra("genres"));
            String runtime = safe(getIntent().getStringExtra("runtime"));
            if (!year.isEmpty()) {
                sb.append(year);
                if (section.equals("series") && !endYear.isEmpty() && !"\\N".equals(endYear)) sb.append("–").append(endYear);
            }
            if (!rating.isEmpty()) appendMeta(sb, "★ " + rating);
            if (!runtime.isEmpty()) appendMeta(sb, runtime + " min");
            if (!genres.isEmpty() && !"\\N".equals(genres)) appendMeta(sb, genres);
            return sb.toString();
        }

        String year = cleanDetailValue(getIntent().getStringExtra("year"));
        String endYear = cleanDetailValue(getIntent().getStringExtra("endYear"));
        String rating = cleanDetailValue(getIntent().getStringExtra("rating"));
        String genres = cleanDetailValue(getIntent().getStringExtra("genres"));
        String runtime = cleanDetailValue(getIntent().getStringExtra("runtime"));
        String votes = cleanDetailValue(getIntent().getStringExtra("votes"));
        String seasons = cleanDetailValue(getIntent().getStringExtra("seasons"));
        String episodes = cleanDetailValue(getIntent().getStringExtra("episodeCount"));
        if (!year.isEmpty()) {
            sb.append(year);
            if (section.equals("series") && !endYear.isEmpty() && !endYear.equals(year)) sb.append("–").append(endYear);
        }
        if (!runtime.isEmpty() && !section.equals("series")) appendMeta(sb, runtime + " min");
        if (!rating.isEmpty()) appendMeta(sb, "★ " + rating);
        if (!votes.isEmpty()) appendMeta(sb, getString(R.string.card_votes_short, formatCompactCount(votes)));
        if (section.equals("series")) {
            if (!seasons.isEmpty()) appendMeta(sb, getString(R.string.card_seasons_short, seasons));
            if (!episodes.isEmpty()) appendMeta(sb, getString(R.string.card_episodes_short, episodes));
        }
        if (!genres.isEmpty()) appendMeta(sb, genres);
        return sb.toString();
    }

    private void loadInfo() {
        io.execute(() -> {
            try {
                JSONObject data = PukankiApi.fetchTitleInfo(this, id);
                runOnUiThread(() -> renderInfo(data));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading.setVisibility(View.GONE);
                    details.setText(getString(R.string.info_unavailable));
                });
            }
        });
    }

    private void renderInfo(JSONObject data) {
        loading.setVisibility(View.GONE);
        StringBuilder sb = new StringBuilder();
        boolean tvImdbRatingShown = false;

        // TV detail view owns the complete metadata. Cards stay intentionally minimal.
        if (!config.isPhoneMode()) {
            String originalTitle = cleanDetailValue(getIntent().getStringExtra("originalTitle"));
            String year = cleanDetailValue(getIntent().getStringExtra("year"));
            String endYear = cleanDetailValue(getIntent().getStringExtra("endYear"));
            String runtime = cleanDetailValue(getIntent().getStringExtra("runtime"));
            String genres = cleanDetailValue(getIntent().getStringExtra("genres"));
            String rating = cleanDetailValue(getIntent().getStringExtra("rating"));
            String votes = cleanDetailValue(getIntent().getStringExtra("votes"));
            String seasons = cleanDetailValue(getIntent().getStringExtra("seasons"));
            String episodes = cleanDetailValue(getIntent().getStringExtra("episodeCount"));

            if (!originalTitle.isEmpty() && !originalTitle.equalsIgnoreCase(title)) {
                addLine(sb, getString(R.string.original_title), originalTitle);
            }
            if (!year.isEmpty()) {
                String years = year;
                if (section.equals("series") && !endYear.isEmpty() && !endYear.equals(year)) years += "–" + endYear;
                addLine(sb, getString(R.string.year_label), years);
            }
            if (!runtime.isEmpty()) addLine(sb, getString(R.string.runtime_label), runtime + " min");
            if (!rating.isEmpty()) {
                addLine(sb, getString(R.string.rating_label), "★ " + rating + " / 10");
                tvImdbRatingShown = true;
            }
            if (!votes.isEmpty()) addLine(sb, getString(R.string.votes_label), formatCompactCount(votes));
            if (!genres.isEmpty()) addLine(sb, getString(R.string.genre), genres);
            if (section.equals("series")) {
                if (!seasons.isEmpty()) addLine(sb, getString(R.string.seasons_label), seasons);
                if (!episodes.isEmpty()) addLine(sb, getString(R.string.episodes_label), episodes);
            }
        }

        addLine(sb, getString(R.string.plot), config.isPhoneMode() ? clean(data.optString("Plot")) : cleanDetailValue(data.optString("Plot")));
        addLine(sb, getString(R.string.director), config.isPhoneMode() ? clean(data.optString("Director")) : cleanDetailValue(data.optString("Director")));
        addLine(sb, getString(R.string.cast), config.isPhoneMode() ? clean(data.optString("Actors")) : cleanDetailValue(data.optString("Actors")));
        if (config.isPhoneMode()) addLine(sb, getString(R.string.genre), clean(data.optString("Genre")));
        addLine(sb, getString(R.string.released), config.isPhoneMode() ? clean(data.optString("Released")) : cleanDetailValue(data.optString("Released")));
        if (config.isPhoneMode()) addLine(sb, "IMDb", clean(data.optString("imdbRating")));
        else {
            String apiVotes = cleanDetailValue(data.optString("imdbVotes"));
            if (cleanDetailValue(getIntent().getStringExtra("rating")).isEmpty()) {
                String imdbRating = cleanDetailValue(data.optString("imdbRating"));
                if (!imdbRating.isEmpty()) {
                    addLine(sb, getString(R.string.rating_label), "★ " + imdbRating + " / 10");
                    tvImdbRatingShown = true;
                }
            }
            if (cleanDetailValue(getIntent().getStringExtra("votes")).isEmpty() && !apiVotes.isEmpty()) {
                addLine(sb, getString(R.string.votes_label), formatCompactCount(apiVotes));
            }
        }
        JSONArray ratings = data.optJSONArray("Ratings");
        if (ratings != null) {
            for (int i = 0; i < ratings.length(); i++) {
                JSONObject r = ratings.optJSONObject(i);
                if (r != null) {
                    String source = config.isPhoneMode() ? clean(r.optString("Source")) : cleanDetailValue(r.optString("Source"));
                    String value = config.isPhoneMode() ? clean(r.optString("Value")) : cleanDetailValue(r.optString("Value"));
                    if (!config.isPhoneMode() && tvImdbRatingShown && isImdbRatingSource(source)) continue;
                    addLine(sb, source, value);
                }
            }
        }
        details.setText(sb.length() == 0 ? getString(R.string.info_unavailable) : sb.toString().trim());
    }

    private void openPlayer() {
        Intent i = new Intent(this, MoviePlayerActivity.class);
        i.putExtra("id", id); i.putExtra("type", section); i.putExtra("title", title);
        startActivity(i);
    }

    private void updateFavoriteButton() {
        if (favoriteButton == null) return;
        favoriteButton.setText(favorites.isFavorite(id) ? getString(R.string.remove_media_favorite) : getString(R.string.add_media_favorite));
    }

    private static String formatCompactCount(String value) {
        String clean = safe(value).replace(",", "").replace(" ", "");
        if (clean.isEmpty()) return "";
        try {
            long n = Long.parseLong(clean);
            if (n >= 1_000_000L) {
                double m = n / 1_000_000.0;
                String out = String.format(java.util.Locale.US, m >= 10.0 ? "%.0fM" : "%.1fM", m);
                return out.replace(".0M", "M");
            }
            if (n >= 1_000L) {
                double k = n / 1_000.0;
                String out = String.format(java.util.Locale.US, k >= 100.0 ? "%.0fK" : "%.1fK", k);
                return out.replace(".0K", "K");
            }
            return Long.toString(n);
        } catch (NumberFormatException ignored) {
            return safe(value);
        }
    }


    private static boolean isImdbRatingSource(String source) {
        String v = safe(source).toLowerCase(java.util.Locale.ROOT);
        return v.contains("imdb") || v.contains("internet movie database");
    }

    private void addLine(StringBuilder sb, String label, String value) {
        if (value.isEmpty() || "N/A".equalsIgnoreCase(value) || "Unknown".equalsIgnoreCase(value)) return;
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(label).append(":\n").append(value);
    }

    private void appendMeta(StringBuilder sb, String value) {
        if (sb.length() > 0) sb.append("  •  ");
        sb.append(value);
    }

    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private Button plainButton(String label, int size, int fill, int stroke, int textColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(UiTheme.sp(this, size));
        b.setTextColor(textColor);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), stroke);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable state = new GradientDrawable();
            state.setCornerRadius(dp(12));
            if (focused) {
                state.setColor((stroke & 0x00FFFFFF) | 0xDD000000);
                state.setStroke(dp(3), Color.WHITE);
                b.setTextColor(Color.WHITE);
                b.animate().scaleX(1.045f).scaleY(1.045f).setDuration(100L).start();
                if (android.os.Build.VERSION.SDK_INT >= 21) b.setElevation(dp(8));
            } else {
                state.setColor(fill);
                state.setStroke(dp(1), stroke);
                b.setTextColor(textColor);
                b.animate().scaleX(1f).scaleY(1f).setDuration(100L).start();
                if (android.os.Build.VERSION.SDK_INT >= 21) b.setElevation(0f);
            }
            b.setBackground(state);
        });
        return b;
    }
    private Button primaryButton(String label, int size, int accent) {
        return plainButton(label, size, accent, accent, Color.WHITE);
    }
    private Button secondaryButton(String label, int size, int accent) {
        return plainButton(label, size, 0xFF21262D, accent, Color.WHITE);
    }
    private TextView text(String label, int size, int color, boolean bold) { TextView v = new TextView(this); v.setText(label); v.setTextColor(color); v.setTextSize(UiTheme.sp(this,size)); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v; }
    private static String clean(String v) { v = safe(v); return "N/A".equalsIgnoreCase(v) ? "" : v; }
    private static String cleanDetailValue(String v) {
        v = safe(v);
        return v.isEmpty() || "N/A".equalsIgnoreCase(v) || "\\N".equals(v) ? "" : v;
    }
    private static String safe(String v) { return v == null ? "" : v.trim(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
