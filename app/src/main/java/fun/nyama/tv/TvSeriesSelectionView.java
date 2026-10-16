package fun.nyama.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Html;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Small native TV-only selector. Metadata describes episodes, never provider availability. */
final class TvSeriesSelectionView extends LinearLayout {
    interface Listener {
        void onSelectionChanged(TvSeriesMetadata.Episode episode, boolean metadataPresent);
        void onPlay(TvSeriesMetadata.Episode episode);
        void onSource(String url);
    }

    private final String imdbId;
    private final Listener listener;
    private final SharedPreferences preferences;
    private final boolean bulgarian;
    private final int accent;
    private final boolean rememberedSelection;
    private final List<TvSeriesMetadata.Season> seasons = new ArrayList<>();
    private final List<Button> seasonButtons = new ArrayList<>();
    private final List<Button> episodeButtons = new ArrayList<>();
    private final List<TvSeriesMetadata.Episode> visibleEpisodes = new ArrayList<>();
    private LinearLayout episodeRows;
    private TvSeriesMetadata.Episode selectedEpisode;
    private int selectedSeason = -1;
    private boolean metadataPresent;
    private View above;
    private Button sourceButton;
    private TvEpisodeStillLoader stillLoader;
    private int stillGeneration;
    private final Set<Button> requestedStills = new HashSet<>();
    private final ViewTreeObserver.OnScrollChangedListener visibleStills = this::loadVisibleStills;

    TvSeriesSelectionView(Context context, String imdbId, Listener listener) {
        super(context);
        this.imdbId = imdbId;
        this.listener = listener;
        preferences = context.getSharedPreferences("nyama_tv_series_selection", Context.MODE_PRIVATE);
        rememberedSelection = preferences.contains(imdbId + ":season");
        bulgarian = Config.LANG_BG.equals(new Config(context).language());
        accent = UiTheme.accent(context);
        setOrientation(VERTICAL);
        setPadding(0, dp(2), 0, dp(16));
        showLoading();
    }

    void setAbove(View view) {
        above = view;
        if (view.getId() == View.NO_ID) view.setId(View.generateViewId());
    }
    boolean hasMetadata() { return metadataPresent; }
    boolean hadRememberedSelection() { return rememberedSelection; }
    TvSeriesMetadata.Episode selectedEpisode() {
        return selectedEpisode != null && selectedEpisode.eligibleForPlayback() ? selectedEpisode : null;
    }

    private String tr(String bg, String en) { return bulgarian ? bg : en; }

    void showLoading() {
        removeAllViews();
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar progress = new ProgressBar(getContext());
        UiTheme.tintProgress(getContext(), progress);
        row.addView(progress, new LayoutParams(dp(22), dp(22)));
        TextView label = label(tr("Зареждане на епизодите…", "Loading episodes…"), 14, 0xFFB9C1CA);
        label.setPadding(dp(10), 0, 0, 0);
        row.addView(label);
        addView(row);
    }

    void showUnavailable() {
        cancelStills();
        metadataPresent = false;
        selectedEpisode = null;
        seasons.clear();
        seasonButtons.clear();
        episodeButtons.clear();
        sourceButton = null;
        removeAllViews();
        addView(label(tr("Епизодите не могат да бъдат заредени. Използвайте „Гледай сега“.",
                "Episode details could not be loaded. Use Watch now."), 14, 0xFFB9C1CA));
        listener.onSelectionChanged(null, false);
    }

    void bind(TvSeriesMetadata metadata) {
        if (metadata == null || !imdbId.equals(metadata.imdbId)) { showUnavailable(); return; }
        cancelStills();
        metadataPresent = true;
        selectedEpisode = null;
        selectedSeason = -1;
        seasons.clear();
        seasonButtons.clear();
        episodeButtons.clear();
        sourceButton = null;
        removeAllViews();
        for (TvSeriesMetadata.Season season : metadata.seasons) {
            if (season == null || season.number < 0) continue;
            boolean hasVisibleEpisode = false;
            for (TvSeriesMetadata.Episode episode : season.episodes) {
                if (visible(episode) && episode.season == season.number) { hasVisibleEpisode = true; break; }
            }
            if (hasVisibleEpisode) seasons.add(season);
        }
        TextView heading = label(tr("Сезони и епизоди", "Seasons and episodes"), 19, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addView(heading);
        TextView note = label(tr("Наличността при доставчика не е потвърдена.",
                "Provider availability is not confirmed."), 13, 0xFFB9C1CA);
        note.setPadding(0, dp(5), 0, dp(10));
        addView(note);
        if (metadata.stale) addView(label(tr("Запазени метаданни", "Cached episode details"), 12, 0xFFB9C1CA));
        if (seasons.isEmpty()) {
            addView(label(tr("Няма потвърдени излъчени епизоди.", "No confirmed aired episodes."), 14, 0xFFB9C1CA));
            addSource(metadata);
            listener.onSelectionChanged(null, true);
            return;
        }

        HorizontalScrollView seasonScroll = new HorizontalScrollView(getContext());
        seasonScroll.setHorizontalScrollBarEnabled(false);
        seasonScroll.setFocusable(false);
        LinearLayout seasonRow = new LinearLayout(getContext());
        seasonRow.setPadding(dp(3), dp(4), dp(3), dp(4));
        seasonScroll.addView(seasonRow, new HorizontalScrollView.LayoutParams(-2, -2));
        addView(seasonScroll, new LayoutParams(-1, -2));
        for (TvSeriesMetadata.Season season : seasons) {
            Button button = button(seasonName(season.number));
            button.setTag("season:" + season.number);
            button.setOnClickListener(v -> chooseSeason(season.number, true));
            button.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    chooseSeason(season.number, true);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && above != null) return above.requestFocus();
                return false;
            });
            LayoutParams lp = new LayoutParams(-2, dp(46));
            lp.setMargins(0, 0, dp(8), 0);
            seasonRow.addView(button, lp);
            seasonButtons.add(button);
        }
        for (int i = 0; i < seasonButtons.size(); i++) {
            Button button = seasonButtons.get(i);
            button.setNextFocusLeftId(seasonButtons.get(Math.max(0, i - 1)).getId());
            button.setNextFocusRightId(seasonButtons.get(Math.min(seasonButtons.size() - 1, i + 1)).getId());
        }

        ScrollView episodeScroll = new ScrollView(getContext());
        episodeScroll.setFillViewport(false);
        episodeScroll.setFocusable(false);
        episodeScroll.setNestedScrollingEnabled(true);
        episodeRows = new LinearLayout(getContext());
        episodeRows.setOrientation(VERTICAL);
        episodeRows.setPadding(dp(3), dp(5), dp(3), dp(5));
        episodeScroll.addView(episodeRows, new ScrollView.LayoutParams(-1, -2));
        LayoutParams episodesLayout = new LayoutParams(-1, dp(266));
        episodesLayout.topMargin = dp(7);
        addView(episodeScroll, episodesLayout);
        addSource(metadata);

        int rememberedSeason = preferences.getInt(imdbId + ":season", -1);
        int initialSeason = seasons.get(0).number;
        for (TvSeriesMetadata.Season season : seasons) {
            if (season.number == rememberedSeason) { initialSeason = rememberedSeason; break; }
        }
        chooseSeason(initialSeason, false);
    }

    private boolean visible(TvSeriesMetadata.Episode episode) {
        // A missing air date is explicitly unconfirmed. Scheduled future episodes are omitted.
        return episode != null && episode.number > 0 && episode.season >= 0
                && (episode.airStampMs < 0 || episode.eligibleForPlayback());
    }

    private void chooseSeason(int number, boolean focusEpisodes) {
        TvSeriesMetadata.Season selected = null;
        for (TvSeriesMetadata.Season season : seasons) if (season.number == number) { selected = season; break; }
        if (selected == null) return;
        cancelStills();
        int rememberedSeason = preferences.getInt(imdbId + ":season", -1);
        int rememberedEpisode = preferences.getInt(imdbId + ":episode", -1);
        selectedSeason = number;
        selectedEpisode = null;
        visibleEpisodes.clear();
        episodeButtons.clear();
        episodeRows.removeAllViews();
        for (TvSeriesMetadata.Episode episode : selected.episodes) {
            if (!visible(episode) || episode.season != number) continue;
            visibleEpisodes.add(episode);
            if (episode.eligibleForPlayback() && (selectedEpisode == null
                    || (rememberedSeason == number && rememberedEpisode == episode.number))) selectedEpisode = episode;
        }
        for (TvSeriesMetadata.Episode episode : visibleEpisodes) {
            String caption = tr("Епизод ", "Episode ") + episode.number;
            if (!episode.title.isEmpty()) caption += " · " + episode.title;
            if (episode.durationMinutes > 0) caption += "  ·  " + episode.durationMinutes + tr(" мин", " min");
            String summary = Html.fromHtml(episode.summary, Html.FROM_HTML_MODE_LEGACY).toString().trim();
            if (!summary.isEmpty()) caption += "\n" + summary;
            if (!episode.eligibleForPlayback()) caption += "\n" + tr("Датата на излъчване не е потвърдена", "Air date is unconfirmed");
            Button row = button(caption);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setMaxLines(4);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setTag("episode:" + number + ":" + episode.number);
            row.setEnabled(episode.eligibleForPlayback());
            row.setAlpha(row.isEnabled() ? 1f : 0.6f);
            row.setPadding(dp(14), dp(11), dp(14), dp(11));
            row.setOnClickListener(v -> {
                if (!episode.eligibleForPlayback()) return;
                selectEpisode(episode);
                listener.onPlay(episode);
            });
            row.setOnFocusChangeListener((v, focused) -> {
                if (focused && episode.eligibleForPlayback()) selectEpisode(episode);
                refreshStates();
            });
            row.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP
                        && row == firstEnabledEpisode()) return focusSelectedSeason();
                return false;
            });
            LayoutParams lp = new LayoutParams(-1, -2);
            lp.bottomMargin = dp(7);
            episodeRows.addView(row, lp);
            episodeButtons.add(row);
        }
        saveSelection();
        refreshStates();
        updateFocusOrder();
        listener.onSelectionChanged(selectedEpisode(), true);
        if (focusEpisodes) focusSelectedEpisode();
        post(this::loadVisibleStills);
    }

    private void selectEpisode(TvSeriesMetadata.Episode episode) {
        selectedEpisode = episode;
        saveSelection();
        refreshStates();
        listener.onSelectionChanged(episode, true);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(visibleStills);
        post(this::loadVisibleStills);
    }

    @Override protected void onDetachedFromWindow() {
        if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnScrollChangedListener(visibleStills);
        cancelStills();
        if (stillLoader != null) { stillLoader.close(); stillLoader = null; }
        super.onDetachedFromWindow();
    }

    private void cancelStills() {
        stillGeneration++;
        requestedStills.clear();
        if (stillLoader != null) stillLoader.cancelAll();
    }

    private void loadVisibleStills() {
        if (!isAttachedToWindow() || !isShown()) return;
        Rect visible = new Rect();
        final int generation = stillGeneration;
        for (int i = 0; i < episodeButtons.size(); i++) {
            Button row = episodeButtons.get(i);
            String url = visibleEpisodes.get(i).stillUrl;
            if (!row.getLocalVisibleRect(visible)) {
                if (row.getCompoundDrawablesRelative()[0] != null) {
                    row.setCompoundDrawablesRelative(null, null, null, null);
                    requestedStills.remove(row);
                }
                continue;
            }
            if (url.isEmpty() || requestedStills.contains(row)) continue;
            requestedStills.add(row);
            if (stillLoader == null) stillLoader = new TvEpisodeStillLoader();
            stillLoader.load(url, bitmap -> {
                if (generation != stillGeneration || !isAttachedToWindow() || row.getParent() != episodeRows) return;
                if (!isShown() || !row.getLocalVisibleRect(new Rect())) {
                    requestedStills.remove(row);
                    return;
                }
                BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
                int width = dp(96);
                int height = Math.min(dp(64), Math.max(dp(1), width * bitmap.getHeight() / Math.max(1, bitmap.getWidth())));
                if (width * bitmap.getHeight() / Math.max(1, bitmap.getWidth()) > height) {
                    width = Math.max(dp(1), height * bitmap.getWidth() / Math.max(1, bitmap.getHeight()));
                }
                drawable.setBounds(0, 0, width, height);
                row.setCompoundDrawablePadding(dp(12));
                row.setCompoundDrawablesRelative(drawable, null, null, null);
            });
        }
    }

    private void saveSelection() {
        if (selectedSeason < 0) return;
        preferences.edit().putInt(imdbId + ":season", selectedSeason)
                .putInt(imdbId + ":episode", selectedEpisode == null ? -1 : selectedEpisode.number).apply();
    }

    boolean restoreSelectionFocus() { return focusSelectedEpisode() || focusSelectedSeason(); }

    private boolean focusSelectedEpisode() {
        if (selectedEpisode == null) return false;
        for (int i = 0; i < visibleEpisodes.size(); i++) {
            if (visibleEpisodes.get(i).number == selectedEpisode.number) {
                Button button = episodeButtons.get(i);
                boolean focused = button.requestFocus();
                if (focused) button.requestRectangleOnScreen(new android.graphics.Rect(0, 0, button.getWidth(), button.getHeight()));
                return focused;
            }
        }
        return false;
    }

    private Button firstEnabledEpisode() {
        for (Button button : episodeButtons) if (button.isEnabled()) return button;
        return null;
    }

    private void updateFocusOrder() {
        Button seasonButton = null;
        for (int i = 0; i < seasons.size(); i++) {
            if (seasons.get(i).number == selectedSeason) seasonButton = seasonButtons.get(i);
        }
        if (seasonButton == null) return;
        if (above != null) {
            above.setNextFocusDownId(seasonButton.getId());
            for (Button button : seasonButtons) button.setNextFocusUpId(above.getId());
        }
        List<Button> enabled = new ArrayList<>();
        for (Button button : episodeButtons) if (button.isEnabled()) enabled.add(button);
        for (int i = 0; i < enabled.size(); i++) {
            Button button = enabled.get(i);
            button.setNextFocusUpId(i == 0 ? seasonButton.getId() : enabled.get(i - 1).getId());
            button.setNextFocusDownId(i + 1 < enabled.size() ? enabled.get(i + 1).getId()
                    : sourceButton != null ? sourceButton.getId() : button.getId());
            button.setNextFocusLeftId(button.getId());
            button.setNextFocusRightId(button.getId());
        }
        if (sourceButton != null) sourceButton.setNextFocusUpId(enabled.isEmpty() ? seasonButton.getId()
                : enabled.get(enabled.size() - 1).getId());
    }

    private boolean focusSelectedSeason() {
        for (int i = 0; i < seasons.size(); i++) if (seasons.get(i).number == selectedSeason) return seasonButtons.get(i).requestFocus();
        return false;
    }

    private String seasonName(int number) {
        return number == 0 ? tr("Специални", "Specials") : tr("Сезон ", "Season ") + number;
    }

    private void refreshStates() {
        for (int i = 0; i < seasonButtons.size(); i++) {
            Button button = seasonButtons.get(i);
            boolean selected = seasons.get(i).number == selectedSeason;
            button.setText((selected ? "✓ " : "") + seasonName(seasons.get(i).number));
            style(button, selected);
        }
        for (int i = 0; i < episodeButtons.size(); i++) {
            style(episodeButtons.get(i), selectedEpisode != null && visibleEpisodes.get(i).number == selectedEpisode.number);
        }
    }

    private void addSource(TvSeriesMetadata metadata) {
        String sourceUrl = safeSourceUrl(metadata.sourceUrl);
        if (!sourceUrl.isEmpty()) {
            Button source = button(tr("Метаданни: TVmaze · CC BY-SA ↗", "Metadata: TVmaze · CC BY-SA ↗"));
            sourceButton = source;
            source.setTextSize(UiTheme.sp(getContext(), 12));
            source.setOnClickListener(v -> listener.onSource(sourceUrl));
            LayoutParams lp = new LayoutParams(-2, dp(42));
            lp.topMargin = dp(8);
            addView(source, lp);
        } else if (!metadata.source.isEmpty()) {
            addView(label(tr("Метаданни: ", "Metadata: ") + metadata.source, 12, 0xFFB9C1CA));
        }
    }

    static String safeSourceUrl(String value) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || !("www.tvmaze.com".equalsIgnoreCase(uri.getHost()) || "tvmaze.com".equalsIgnoreCase(uri.getHost()))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getPath().matches("/shows/[0-9]+(?:/[A-Za-z0-9_-]+)?/?")) return "";
            return uri.toASCIIString();
        } catch (Exception ignored) { return ""; }
    }

    private Button button(String caption) {
        Button button = new Button(getContext());
        button.setId(View.generateViewId());
        button.setText(caption);
        button.setAllCaps(false);
        button.setTextSize(UiTheme.sp(getContext(), 14));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(15), 0, dp(15), 0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setFocusable(true);
        button.setOnFocusChangeListener((view, focused) -> {
            refreshStates();
            if (!seasonButtons.contains(button) && !episodeButtons.contains(button)) style(button, false);
        });
        style(button, false);
        return button;
    }

    private void style(Button button, boolean selected) {
        boolean highlighted = selected || button.hasFocus();
        GradientDrawable background = new GradientDrawable();
        background.setColor(highlighted ? accent : 0xFF20262E);
        background.setCornerRadius(dp(9));
        background.setStroke(dp(button.hasFocus() ? 3 : 1), button.hasFocus() ? Color.WHITE : highlighted ? accent : 0xFF414C58);
        button.setBackground(background);
        button.setTextColor(highlighted ? UiTheme.contrastingTextColor(accent) : Color.WHITE);
    }

    private TextView label(String value, int size, int color) {
        TextView label = new TextView(getContext());
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(UiTheme.sp(getContext(), size));
        return label;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
