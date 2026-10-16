package fun.nyama.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One TV focus target for the complete selected episode text, with bounded scrolling. */
final class TvEpisodeInformationView extends ScrollView {
    interface Navigation {
        void returnToEpisode();
        void adjacentEpisode(int keyCode);
    }

    private final TextView title;
    private final TextView metadata;
    private final TextView summary;
    private final ImageView image;
    private final boolean bulgarian;
    private final int accent;
    private final Navigation navigation;
    private TvSeriesMetadata.Episode episode;

    TvEpisodeInformationView(Context context, boolean bulgarian, Navigation navigation) {
        super(context);
        this.bulgarian = bulgarian;
        this.navigation = navigation;
        accent = UiTheme.accent(context);
        setId(View.generateViewId());
        setTag("episode-information");
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setFillViewport(false);
        setSmoothScrollingEnabled(true);
        setVerticalScrollBarEnabled(true);
        setClipToPadding(false);
        setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        addView(content, new LayoutParams(-1, -2));
        title = label(22, true);
        title.setTag("episode-information-title");
        content.addView(title, new LinearLayout.LayoutParams(-1, -2));
        metadata = label(16, false);
        metadata.setTag("episode-information-metadata");
        metadata.setTextColor(0xFFE0E6EE);
        LinearLayout.LayoutParams metadataParams = new LinearLayout.LayoutParams(-1, -2);
        metadataParams.topMargin = dp(5);
        content.addView(metadata, metadataParams);

        LinearLayout description = new LinearLayout(context);
        description.setOrientation(LinearLayout.HORIZONTAL);
        description.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(9);
        content.addView(description, descriptionParams);
        image = new ImageView(context);
        image.setTag("episode-information-image");
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(176), dp(99));
        imageParams.rightMargin = dp(14);
        description.addView(image, imageParams);
        summary = label(18, false);
        summary.setTag("episode-information-summary");
        summary.setLineSpacing(dp(3), 1f);
        description.addView(summary, new LinearLayout.LayoutParams(0, -2, 1f));
        image.setVisibility(GONE);
        setOnFocusChangeListener((view, focused) -> updateStyle());
        updateStyle();
    }

    void bind(TvSeriesMetadata.Episode value, Drawable still) {
        if (episode == value) { setStill(value, still); return; }
        episode = value;
        if (value == null) {
            title.setText("");
            metadata.setText("");
            summary.setText("");
            image.setImageDrawable(null);
            image.setVisibility(GONE);
            return;
        }
        title.setText(value.title);
        String info = (bulgarian ? "Сезон " : "Season ") + value.season
                + "  ·  " + (bulgarian ? "Епизод " : "Episode ") + value.number;
        if (value.durationMinutes > 0) info += "  ·  " + value.durationMinutes + (bulgarian ? " мин" : " min");
        if (!value.airDate.isEmpty()) info += "  ·  " + (bulgarian ? "Излъчен: " : "Aired: ") + value.airDate;
        metadata.setText(info);
        summary.setText(value.fullSummary.isEmpty() ? TvEpisodeText.plainText(value.summary) : value.fullSummary);
        summary.setVisibility(summary.length() == 0 ? GONE : VISIBLE);
        setStill(value, still);
        scrollTo(0, 0);
        setContentDescription(info + ". " + value.title + ". " + summary.getText());
    }

    void setStill(TvSeriesMetadata.Episode value, Drawable still) {
        if (episode != value) return;
        image.setImageDrawable(still);
        image.setVisibility(still == null ? GONE : VISIBLE);
    }

    int preferredHeight() {
        int screen = getResources().getConfiguration().screenHeightDp;
        return dp(Math.max(150, Math.min(230, Math.round((screen > 0 ? screen : 720) * .23f))));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    navigation.adjacentEpisode(keyCode);
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && getScrollY() == 0) {
                    navigation.returnToEpisode();
                } else {
                    int maximum = Math.max(0, getChildAt(0).getHeight() - getHeight() + getPaddingTop() + getPaddingBottom());
                    int step = Math.max(dp(48), Math.round(getHeight() * .55f));
                    int target = getScrollY() + (keyCode == KeyEvent.KEYCODE_DPAD_UP ? -step : step);
                    // Immediate bounded text scrolling keeps repeated remote presses deterministic.
                    scrollTo(0, Math.max(0, Math.min(maximum, target)));
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void updateStyle() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xEC171E27);
        background.setCornerRadius(dp(9));
        background.setStroke(dp(hasFocus() ? 3 : 1), hasFocus() ? accent : 0xFF526171);
        setBackground(background);
    }

    private TextView label(int size, boolean bold) {
        TextView value = new TextView(getContext());
        value.setTextColor(Color.WHITE);
        value.setTextSize(UiTheme.sp(getContext(), size));
        if (bold) value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        value.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        return value;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
