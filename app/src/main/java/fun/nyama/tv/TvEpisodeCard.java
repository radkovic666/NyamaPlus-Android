package fun.nyama.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Html;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One remote focus target; its thumbnail and status never intercept D-pad input. */
final class TvEpisodeCard extends FrameLayout {
    private final ImageView still;
    private final TextView number;
    private final TextView title;
    private final TextView summary;
    private final TextView flag;
    private final int accent;
    private final boolean bulgarian;

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
        content.setPadding(dp(12), dp(10), dp(12), dp(10));
        addView(content, new LayoutParams(-1, -1));
        still = new ImageView(context);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        still.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(still, new LinearLayout.LayoutParams(-1, dp(compact() ? 100 : 116)));

        String numbering = (bulgarian ? "Епизод " : "Episode ") + episode.number;
        if (episode.durationMinutes > 0) numbering += "  ·  " + episode.durationMinutes + (bulgarian ? " мин" : " min");
        number = label(numbering, 17, true);
        number.setMaxLines(1);
        number.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(-1, -2);
        numberParams.topMargin = dp(9);
        content.addView(number, numberParams);
        title = label(episode.title, 20, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(title, new LinearLayout.LayoutParams(-1, -2));
        String description = Html.fromHtml(episode.summary, Html.FROM_HTML_MODE_LEGACY).toString().trim();
        if (!episode.eligibleForPlayback()) description = (bulgarian ? "Датата на излъчване не е потвърдена" : "Air date is unconfirmed")
                + (description.isEmpty() ? "" : " · " + description);
        summary = label(description, 16, false);
        summary.setMaxLines(compact() ? 3 : 4);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        summaryParams.topMargin = dp(5);
        content.addView(summary, summaryParams);

        flag = label("", 12, true);
        flag.setTag("watch-status");
        flag.setSingleLine(true);
        flag.setPadding(dp(9), dp(5), dp(9), dp(5));
        flag.setVisibility(GONE);
        LayoutParams flagParams = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        flagParams.setMargins(dp(8), dp(13), dp(13), 0);
        addView(flag, flagParams);
        setContentDescription(numbering + ". " + episode.title + (description.isEmpty() ? "" : ". " + description));
        setEnabled(episode.eligibleForPlayback());
        setAlpha(isEnabled() ? 1f : .62f);
        updateStyle(false);
    }

    int preferredWidth() {
        int screen = getResources().getConfiguration().screenWidthDp;
        return dp(Math.max(280, Math.min(360, Math.round((screen > 0 ? screen : 960) * .30f))));
    }

    int preferredHeight() { return dp(compact() ? 292 : 324); }

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
                : (bulgarian ? "▶ В процес" : "▶ In progress"));
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
        background.setCornerRadius(dp(10));
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
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
