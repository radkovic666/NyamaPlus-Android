package fun.nyama.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One remote focus target with pinned artwork/identity and complete, internally scrollable text. */
final class TvEpisodeCard extends FrameLayout {
    private final ImageView still;
    private final TextView number;
    private final TextView title;
    private final TextView summary;
    private final TextView flag;
    private final ScrollView information;
    private final int accent;
    private final boolean bulgarian;
    private int informationTargetY;

    TvEpisodeCard(Context context, TvSeriesMetadata.Episode episode, boolean bulgarian) {
        super(context);
        this.bulgarian = bulgarian;
        accent = UiTheme.accent(context);
        setId(View.generateViewId());
        setFocusable(true);
        setClickable(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setPadding(dp(3), dp(3), dp(3), dp(3));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(12), dp(14), dp(12));
        content.setClipToPadding(false);
        addView(content, new LayoutParams(-1, -1));
        still = new ImageView(context);
        still.setTag("episode-card-image");
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        still.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable imageShape = new GradientDrawable();
        imageShape.setColor(0xFF101720);
        imageShape.setCornerRadius(dp(8));
        still.setBackground(imageShape);
        still.setClipToOutline(true);
        content.addView(still, new LinearLayout.LayoutParams(-1, dp(compact() ? 104 : 138)));

        String numbering = (bulgarian ? "Сезон " : "Season ") + episode.season
                + "  ·  " + (bulgarian ? "Епизод " : "Episode ") + episode.number;
        number = label(numbering, 17, true);
        number.setTag("episode-card-identity");
        LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(-1, -2);
        numberParams.topMargin = dp(9);
        content.addView(number, numberParams);
        String details = episode.durationMinutes > 0
                ? episode.durationMinutes + (bulgarian ? " мин" : " min") : "";
        if (!episode.airDate.isEmpty()) details += (details.isEmpty() ? "" : "  ·  ")
                + (bulgarian ? "Излъчен: " : "Aired: ") + episode.airDate;
        TextView metadata = label(details, 14, false);
        metadata.setTag("episode-card-metadata");
        metadata.setTextColor(0xFFDEE6EE);
        if (details.isEmpty()) metadata.setVisibility(GONE);
        content.addView(metadata, new LinearLayout.LayoutParams(-1, -2));

        information = new ScrollView(context);
        information.setTag("episode-card-text-scroll");
        information.setFocusable(false);
        information.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        information.setFillViewport(false);
        information.setSmoothScrollingEnabled(true);
        information.setVerticalScrollBarEnabled(true);
        information.setPadding(0, 0, dp(5), 0);
        LinearLayout.LayoutParams informationParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        informationParams.topMargin = dp(9);
        content.addView(information, informationParams);
        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        information.addView(text, new ScrollView.LayoutParams(-1, -2));
        title = label(episode.title, 21, true);
        title.setTag("episode-card-title");
        text.addView(title, new LinearLayout.LayoutParams(-1, -2));
        // fullSummary is already safely sanitized by the shared metadata repository. Legacy
        // cached previews use its existing text helper, never a second HTML/WebView surface.
        String description = episode.fullSummary.isEmpty()
                ? TvEpisodeText.plainText(episode.summary) : episode.fullSummary;
        if (!episode.eligibleForPlayback()) description = (bulgarian ? "Датата на излъчване не е потвърдена" : "Air date is unconfirmed")
                + (description.isEmpty() ? "" : "\n\n" + description);
        summary = label(description, 17, false);
        summary.setTag("episode-card-summary");
        summary.setLineSpacing(dp(3), 1f);
        summary.setVisibility(description.isEmpty() ? GONE : VISIBLE);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.topMargin = dp(8);
        text.addView(summary, summaryParams);
        TextView hint = label(bulgarian ? "↓ Описание  ·  задръжте ↑ за сезони" : "↓ Description  ·  hold ↑ for seasons", 12, false);
        hint.setTextColor(0xFFCBD5E0);
        hint.setPadding(0, dp(7), 0, 0);
        content.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        flag = label("", 12, true);
        flag.setTag("watch-status");
        flag.setSingleLine(true);
        flag.setPadding(dp(9), dp(5), dp(9), dp(5));
        flag.setVisibility(GONE);
        LayoutParams flagParams = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        flagParams.setMargins(dp(8), dp(13), dp(13), 0);
        addView(flag, flagParams);
        setContentDescription(numbering + ". " + episode.title + (description.isEmpty() ? "" : ". " + description));
        // Metadata with an unknown air date remains readable with a remote. Playback is
        // still guarded by the selector; there is no generic-series playback fallback.
        setClickable(episode.eligibleForPlayback());
        setAlpha(episode.eligibleForPlayback() ? 1f : .62f);
        updateStyle(false);
    }

    int preferredWidth() {
        int screen = getResources().getConfiguration().screenWidthDp;
        return dp(Math.max(350, Math.min(560, Math.round((screen > 0 ? screen : 960) * .45f))));
    }

    int preferredHeight() {
        int screen = getResources().getConfiguration().screenHeightDp;
        return dp(Math.max(320, Math.min(440, Math.round((screen > 0 ? screen : 720) * .54f))));
    }

    /** Text stays inside this focus target; LEFT/RIGHT and OK remain available at every offset. */
    boolean scrollInformation(int direction) {
        int maximum = information.getChildCount() == 0 ? 0 : Math.max(0,
                information.getChildAt(0).getHeight() - information.getHeight()
                        + information.getPaddingTop() + information.getPaddingBottom());
        int current = information.getScrollY();
        int previousTarget = Math.max(0, Math.min(maximum, informationTargetY));
        if (direction < 0 && current == 0 && previousTarget == 0) return false;
        int step = Math.max(dp(40), Math.round(information.getHeight() * .55f));
        // Retain the requested target while an animation is running so rapid presses accumulate.
        informationTargetY = Math.max(0, Math.min(maximum, previousTarget + direction * step));
        if (informationTargetY == 0 || informationTargetY == maximum)
            information.scrollTo(0, informationTargetY);
        else information.smoothScrollTo(0, informationTargetY);
        return true;
    }


    private boolean compact() {
        int height = getResources().getConfiguration().screenHeightDp;
        return height > 0 && height <= 720;
    }

    void setStill(Bitmap bitmap) { still.setImageBitmap(bitmap); }
    void clearStill() { still.setImageDrawable(null); }
    boolean hasStill() { return still.getDrawable() != null; }
    void updateWatchStatus(TvEpisodeProgressStore.Snapshot progress) {
        boolean watched = progress != null && progress.watched;
        boolean underway = progress != null && progress.inProgress;
        if (!watched && !underway) { flag.setVisibility(GONE); return; }
        flag.setText(watched ? (bulgarian ? "✓ Гледан" : "✓ Watched")
                : (bulgarian ? "▶ Започнат" : "▶ Started"));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(5));
        background.setColor(watched ? accent : 0xF020262E);
        background.setStroke(dp(1), accent);
        flag.setBackground(background);
        flag.setTextColor(watched ? UiTheme.contrastingTextColor(accent) : Color.WHITE);
        flag.setVisibility(VISIBLE);
    }

    void updateStyle(boolean selected) {
        boolean focused = hasFocus();
        GradientDrawable background = new GradientDrawable();
        // Keep artwork and description readable while a strong theme border locates focus.
        background.setColor(focused ? 0xF52B333E : 0xE51B222C);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(focused ? 4 : selected ? 2 : 1), focused || selected ? accent : 0xFF526171);
        setBackground(background);
        number.setTextColor(Color.WHITE);
        title.setTextColor(Color.WHITE);
        summary.setTextColor(0xFFE2E8EF);
    }

    private TextView label(String value, int size, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(UiTheme.sp(getContext(), size));
        view.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
