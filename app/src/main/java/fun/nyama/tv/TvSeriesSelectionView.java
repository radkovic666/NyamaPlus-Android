package fun.nyama.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** TV-only horizontal selector. Metadata describes episodes, never provider availability. */
final class TvSeriesSelectionView extends LinearLayout {
    interface Listener {
        void onSelectionChanged(TvSeriesMetadata.Episode episode, boolean metadataPresent);
        void onPlay(TvSeriesMetadata.Episode episode);
        void onSource(String url);
        default void onEpisodeFocused(TvSeriesMetadata.Episode episode) { }
    }

    private final String imdbId;
    private final Listener listener;
    private final SharedPreferences preferences;
    private final TvEpisodeProgressStore progressStore;
    private final boolean bulgarian;
    private final int accent;
    private final boolean rememberedSelection;
    private final List<TvSeriesMetadata.Season> seasons = new ArrayList<>();
    private final List<Button> seasonButtons = new ArrayList<>();
    private final List<TvEpisodeCard> episodeCards = new ArrayList<>();
    private final List<TvSeriesMetadata.Episode> visibleEpisodes = new ArrayList<>();
    private LinearLayout episodeRows;
    private TvSeriesMetadata.Episode selectedEpisode;
    private int selectedSeason = -1;
    private boolean metadataPresent;
    private View above;
    private TvEpisodeStillLoader stillLoader;
    private int stillGeneration;
    private final Set<TvEpisodeCard> requestedStills = new HashSet<>();
    private final ViewTreeObserver.OnScrollChangedListener visibleStills = this::loadVisibleStills;

    TvSeriesSelectionView(Context context, String imdbId, Listener listener) {
        super(context);
        this.imdbId = imdbId;
        this.listener = listener;
        preferences = context.getSharedPreferences("nyama_tv_series_selection", Context.MODE_PRIVATE);
        progressStore = new TvEpisodeProgressStore(context);
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
        TextView label = label(tr("Зареждане на епизодите…", "Loading episodes…"), 16, 0xFFE0E6EE);
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
        episodeCards.clear();
        visibleEpisodes.clear();
        removeAllViews();
        addView(label(tr("Епизодите не могат да бъдат заредени. Отворете отново сериала, за да опитате пак.",
                "Episode details could not be loaded. Reopen this series to try again."), 16, 0xFFE0E6EE));
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
        episodeCards.clear();
        visibleEpisodes.clear();
        removeAllViews();
        for (TvSeriesMetadata.Season season : metadata.seasons) {
            if (season == null || season.number < 0) continue;
            boolean hasVisibleEpisode = false;
            for (TvSeriesMetadata.Episode episode : season.episodes) {
                if (visible(episode) && episode.season == season.number) { hasVisibleEpisode = true; break; }
            }
            if (hasVisibleEpisode) seasons.add(season);
        }
        // Both application languages use chronological left-to-right television navigation.
        // Numbered specials retain their separate position after the normal seasons.
        seasons.sort(Comparator.comparingInt(season -> season.number == 0 ? Integer.MAX_VALUE : season.number));
        TextView heading = label(tr("Сезони", "Seasons"), 21, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addView(heading);
        TextView note = label(tr("Наличността при доставчика не е потвърдена.",
                "Provider availability is not confirmed."), 13, 0xFFE0E6EE);
        note.setPadding(0, dp(4), 0, dp(8));
        addView(note);
        if (metadata.stale) addView(label(tr("Запазени метаданни", "Cached episode details"), 13, 0xFFE0E6EE));
        if (seasons.isEmpty()) {
            addView(label(tr("Няма потвърдени излъчени епизоди.", "No confirmed aired episodes."), 16, 0xFFE0E6EE));
            listener.onSelectionChanged(null, true);
            return;
        }

        HorizontalScrollView seasonScroll = horizontalScroll("season-scroll");
        LinearLayout seasonRow = horizontalRow();
        seasonRow.setTag("season-row");
        seasonScroll.addView(seasonRow, new HorizontalScrollView.LayoutParams(-2, -2));
        addView(seasonScroll, new LayoutParams(-1, -2));
        for (TvSeriesMetadata.Season season : seasons) {
            Button button = button(seasonName(season.number));
            button.setTag("season:" + season.number);
            button.setOnClickListener(v -> chooseSeason(season.number, false));
            button.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    chooseSeason(season.number, true);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (above != null) above.requestFocus();
                    return true;
                }
                return horizontalKey(seasonButtons, button, keyCode);
            });
            LayoutParams lp = new LayoutParams(-2, dp(50));
            lp.setMargins(dp(4), dp(2), dp(8), dp(2));
            seasonRow.addView(button, lp);
            seasonButtons.add(button);
        }
        wireHorizontal(seasonButtons);

        TextView episodesHeading = label(tr("Епизоди", "Episodes"), 20, Color.WHITE);
        episodesHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        episodesHeading.setPadding(0, dp(10), 0, dp(3));
        addView(episodesHeading);
        HorizontalScrollView episodeScroll = horizontalScroll("episode-scroll");
        episodeRows = horizontalRow();
        episodeRows.setTag("episode-row");
        episodeRows.setPadding(dp(4), dp(6), dp(4), dp(6));
        episodeScroll.addView(episodeRows, new HorizontalScrollView.LayoutParams(-2, -2));
        addView(episodeScroll, new LayoutParams(-1, -2));
        episodeScroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> fitEpisodeCards((HorizontalScrollView) view));
        int rememberedSeason = preferences.getInt(imdbId + ":season", -1);
        int initialSeason = seasons.get(0).number;
        for (TvSeriesMetadata.Season season : seasons) {
            if (season.number == rememberedSeason) { initialSeason = rememberedSeason; break; }
        }
        chooseSeason(initialSeason, false);
        seasonScroll.post(() -> reveal(seasonButton(selectedSeason)));
    }

    private HorizontalScrollView horizontalScroll(String tag) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setTag(tag);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setClipToPadding(false);
        // Visual order is explicit, so physical LEFT/RIGHT keep their normal screen meaning.
        scroll.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        return scroll;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        row.setPadding(dp(3), dp(4), dp(3), dp(4));
        return row;
    }

    private boolean visible(TvSeriesMetadata.Episode episode) {
        return episode != null && episode.number > 0 && episode.season >= 0
                && (episode.airStampMs < 0 || episode.eligibleForPlayback());
    }

    private void chooseSeason(int number, boolean focusEpisodes) {
        if (selectedSeason == number && !episodeCards.isEmpty()) {
            if (focusEpisodes && !focusSelectedEpisode()) focusSelectedSeason();
            return;
        }
        TvSeriesMetadata.Season selected = null;
        for (TvSeriesMetadata.Season season : seasons) if (season.number == number) { selected = season; break; }
        if (selected == null) return;
        cancelStills();
        int rememberedSeason = preferences.getInt(imdbId + ":season", -1);
        int rememberedEpisode = preferences.getInt(imdbId + ":episode", -1);
        selectedSeason = number;
        selectedEpisode = null;
        visibleEpisodes.clear();
        episodeCards.clear();
        episodeRows.removeAllViews();
        for (TvSeriesMetadata.Episode episode : selected.episodes) {
            if (!visible(episode) || episode.season != number) continue;
            visibleEpisodes.add(episode);
        }
        visibleEpisodes.sort(Comparator.comparingInt(episode -> episode.number));
        for (TvSeriesMetadata.Episode episode : visibleEpisodes) {
            if (selectedEpisode == null
                    || (rememberedSeason == number && rememberedEpisode == episode.number)) selectedEpisode = episode;
        }
        for (TvSeriesMetadata.Episode episode : visibleEpisodes) {
            TvEpisodeCard card = new TvEpisodeCard(getContext(), episode, bulgarian);
            card.setTag("episode:" + number + ":" + episode.number);
            card.setOnClickListener(v -> {
                if (!episode.eligibleForPlayback()) return;
                selectEpisode(episode);
                listener.onPlay(episode);
            });
            // Installing a click listener enables clicking; retain focus-only unknown metadata.
            card.setClickable(episode.eligibleForPlayback());
            card.setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    selectEpisode(episode);
                    listener.onEpisodeFocused(episode);
                    reveal(card);
                }
                refreshStates();
            });
            card.setOnHoverListener((v, event) -> {
                // Genuine pointer hover only; never generate pointer events or alter playback.
                if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER) {
                    listener.onEpisodeFocused(episode);
                }
                if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT && !card.hasFocus()) {
                    listener.onEpisodeFocused(selectedEpisode);
                }
                return false;
            });
            card.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (event.isLongPress() || !card.scrollInformation(-1)) focusSelectedSeason();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    card.scrollInformation(1);
                    return true;
                }
                return horizontalKey(enabledEpisodeCards(), card, keyCode);
            });
            LayoutParams lp = new LayoutParams(card.preferredWidth(), card.preferredHeight());
            lp.setMargins(dp(4), 0, dp(12), 0);
            episodeRows.addView(card, lp);
            episodeCards.add(card);
        }
        fitEpisodeCards((HorizontalScrollView) episodeRows.getParent());
        saveSelection();
        refreshStates();
        refreshWatchProgress();
        updateFocusOrder();
        listener.onSelectionChanged(selectedEpisode(), true);
        if (focusEpisodes && !focusSelectedEpisode()) focusSelectedSeason();
        post(() -> {
            if (!episodeCards.isEmpty()) reveal(selectedEpisodeCard());
            loadVisibleStills();
        });
    }

    private void selectEpisode(TvSeriesMetadata.Episode episode) {
        selectedEpisode = episode;
        saveSelection();
        refreshStates();
        updateFocusOrder();
        listener.onSelectionChanged(selectedEpisode(), true);
    }

    private void fitEpisodeCards(HorizontalScrollView viewport) {
        if (viewport.getWidth() <= 0) return;
        int available = Math.max(1, viewport.getWidth() - viewport.getPaddingLeft()
                - viewport.getPaddingRight() - dp(32));
        boolean resized = false;
        for (TvEpisodeCard card : episodeCards) {
            LayoutParams params = (LayoutParams) card.getLayoutParams();
            int width = Math.min(card.preferredWidth(), available);
            if (params.width != width) {
                params.width = width;
                card.setLayoutParams(params);
                resized = true;
            }
        }
        if (resized) viewport.post(() -> reveal(selectedEpisodeCard()));
    }

    void refreshWatchProgress() {
        for (int i = 0; i < episodeCards.size(); i++) {
            TvSeriesMetadata.Episode episode = visibleEpisodes.get(i);
            episodeCards.get(i).updateWatchStatus(progressStore.get(imdbId, episode.season, episode.number));
        }
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
        for (TvEpisodeCard card : episodeCards) card.clearStill();
        if (stillLoader != null) stillLoader.cancelAll();
    }

    private void loadVisibleStills() {
        if (!isAttachedToWindow() || !isShown()) return;
        Rect visible = new Rect();
        final int generation = stillGeneration;
        for (int i = 0; i < episodeCards.size(); i++) {
            TvEpisodeCard card = episodeCards.get(i);
            TvSeriesMetadata.Episode episode = visibleEpisodes.get(i);
            String url = episode.stillUrl;
            if (!card.getLocalVisibleRect(visible)) {
                card.clearStill();
                requestedStills.remove(card);
                continue;
            }
            if (url.isEmpty() || requestedStills.contains(card)) continue;
            requestedStills.add(card);
            if (stillLoader == null) stillLoader = new TvEpisodeStillLoader();
            stillLoader.load(url, bitmap -> {
                if (generation != stillGeneration || !isAttachedToWindow() || card.getParent() != episodeRows) return;
                if (!isShown() || !card.getLocalVisibleRect(new Rect())) {
                    requestedStills.remove(card);
                    return;
                }
                card.setStill(bitmap);
            });
        }
    }

    private void saveSelection() {
        if (selectedSeason < 0) return;
        preferences.edit().putInt(imdbId + ":season", selectedSeason)
                .putInt(imdbId + ":episode", selectedEpisode == null ? -1 : selectedEpisode.number).apply();
    }

    boolean restoreSelectionFocus() { return focusSelectedEpisode() || focusSelectedSeason(); }

    private TvEpisodeCard selectedEpisodeCard() {
        if (selectedEpisode != null) for (int i = 0; i < visibleEpisodes.size(); i++) {
            if (visibleEpisodes.get(i).number == selectedEpisode.number) return episodeCards.get(i);
        }
        return null;
    }

    private boolean focusSelectedEpisode() {
        TvEpisodeCard card = selectedEpisodeCard();
        if (card == null) return false;
        boolean focused = card.requestFocus();
        if (focused) reveal(card);
        return focused;
    }

    private List<TvEpisodeCard> enabledEpisodeCards() {
        List<TvEpisodeCard> enabled = new ArrayList<>();
        for (TvEpisodeCard card : episodeCards) if (card.isEnabled()) enabled.add(card);
        return enabled;
    }

    private void updateFocusOrder() {
        Button selectedButton = seasonButton(selectedSeason);
        if (selectedButton == null) return;
        if (above != null) {
            above.setNextFocusDownId(selectedButton.getId());
            for (Button button : seasonButtons) button.setNextFocusUpId(above.getId());
        }
        TvEpisodeCard selectedCard = selectedEpisodeCard();
        for (Button button : seasonButtons) button.setNextFocusDownId(selectedCard == null ? button.getId() : selectedCard.getId());
        List<TvEpisodeCard> enabled = enabledEpisodeCards();
        wireHorizontal(enabled);
        for (TvEpisodeCard card : enabled) {
            card.setNextFocusUpId(selectedButton.getId());
            card.setNextFocusDownId(card.getId());
        }
    }

    private <T extends View> void wireHorizontal(List<T> chronological) {
        for (int i = 0; i < chronological.size(); i++) {
            View current = chronological.get(i);
            int left = Math.max(0, i - 1);
            int right = Math.min(chronological.size() - 1, i + 1);
            current.setNextFocusLeftId(chronological.get(left).getId());
            current.setNextFocusRightId(chronological.get(right).getId());
        }
    }

    private <T extends View> boolean horizontalKey(List<T> chronological, View current, int keyCode) {
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        int index = chronological.indexOf(current);
        int direction = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
        int next = index + direction;
        if (index >= 0 && next >= 0 && next < chronological.size()) {
            View target = chronological.get(next);
            target.requestFocus();
            reveal(target);
        }
        return true;
    }

    private void reveal(View view) {
        if (view == null || view.getParent() == null) return;
        if (view.getWidth() == 0 || view.isLayoutRequested()) {
            // Newly selected seasons create cards before their first layout; a zero rectangle
            // otherwise leaves the focused card only partly visible at the old scroll offset.
            view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View target, int left, int top, int right, int bottom,
                                                     int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    target.removeOnLayoutChangeListener(this);
                    if (target.getParent() != null) reveal(target);
                }
            });
            return;
        }
        int inset = dp(6);
        view.requestRectangleOnScreen(new Rect(-inset, 0, view.getWidth() + inset, view.getHeight()), true);
    }

    private Button seasonButton(int number) {
        for (int i = 0; i < seasons.size(); i++) if (seasons.get(i).number == number) return seasonButtons.get(i);
        return null;
    }

    private boolean focusSelectedSeason() {
        Button button = seasonButton(selectedSeason);
        if (button == null) return false;
        boolean focused = button.requestFocus();
        if (focused) reveal(button);
        return focused;
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
        for (int i = 0; i < episodeCards.size(); i++) {
            episodeCards.get(i).updateStyle(selectedEpisode != null && visibleEpisodes.get(i).number == selectedEpisode.number);
        }
    }

    /** Retained validation helper; attribution is now in the existing About area, outside selector focus. */
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
        button.setTextSize(UiTheme.sp(getContext(), 17));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setFocusable(true);
        button.setOnFocusChangeListener((view, focused) -> {
            refreshStates();
            if (focused) reveal(button);
        });
        style(button, false);
        return button;
    }

    private void style(Button button, boolean selected) {
        boolean highlighted = selected || button.hasFocus();
        GradientDrawable background = new GradientDrawable();
        background.setColor(highlighted ? accent : 0xEB20262E);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(button.hasFocus() ? 3 : 1), button.hasFocus() ? Color.WHITE : highlighted ? accent : 0xFF677485);
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
