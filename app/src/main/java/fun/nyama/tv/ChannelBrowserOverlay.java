package fun.nyama.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ChannelBrowserOverlay extends FrameLayout {
    public interface Listener {
        void onPlayChannel(int sourceIndex);
        void onRequestMovies();
        void onRequestSeries();
        void onRequestSettings();
        void onClosed();
        void onFavoriteChanged(Channel channel, boolean favorite);
    }

    private static final int ROW_SELECTED = 0xCC303640;

    private final Config config;
    private final Listener listener;
    private final int accent;
    private final List<Category> categories = new ArrayList<>();
    private final List<Channel> source = new ArrayList<>();
    private final List<Channel> filtered = new ArrayList<>();

    private final AppSidebar appSidebar;
    private final LinearLayout appPane;
    private final LinearLayout categoryPane;
    private final LinearLayout channelPane;
    private final LinearLayout detailPane;
    private final ListView categoryList;
    private final ListView channelList;
    private final CategoryAdapter categoryAdapter = new CategoryAdapter();
    private final ChannelAdapter channelAdapter = new ChannelAdapter();
    private final TextView sectionTitle;
    private final TextView channelCount;
    private final ImageView detailLogo;
    private final TextView detailNumber;
    private final TextView detailName;
    private final TextView detailGroup;
    private final TextView nowTime;
    private final TextView nowTitle;
    private final TextView nowDescription;
    private final ProgressBar nowProgress;
    private final TextView nextTime;
    private final TextView nextTitle;
    private final TextView clock;
    private final ImageButton settingsButton;
    private final boolean compact;
    private final boolean portraitPhone;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView empty;

    private int selectedCategory = 0;
    private int selectedChannel = 0;
    private int foldStage = 0;
    private ValueAnimator foldAnimator;

    public ChannelBrowserOverlay(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.config = new Config(context);
        this.accent = UiTheme.accent(context);
        this.compact = config.isPhoneMode();
        int widthDp = Math.round(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int heightDp = Math.round(getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density);
        this.portraitPhone = compact && heightDp > widthDp;
        setVisibility(GONE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0x66000000);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0x33000000);
        addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(portraitPhone ? 10 : (compact ? 12 : 28)), dp(compact ? 6 : 10),
                dp(portraitPhone ? 10 : (compact ? 12 : 28)), dp(compact ? 6 : 10));
        top.setBackgroundColor(portraitPhone ? 0xE60D0F13 : 0xC00D0F13);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(portraitPhone ? 54 : (compact ? 60 : 76))));

        clock = text("", compact ? 15 : 18, Color.WHITE, true);
        clock.setTextSize(UiTheme.sp(context, compact ? 15 : 18) * config.clockScale());
        clock.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        clock.setVisibility(config.showClock() ? VISIBLE : GONE);
        top.addView(clock, new LinearLayout.LayoutParams(dp(portraitPhone ? 68 : (compact ? 80 : 96)), -1));

        LinearLayout titleBox = new LinearLayout(context);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);
        sectionTitle = text(context.getString(R.string.channels), compact ? 17 : 21, Color.WHITE, true);
        channelCount = text("", compact ? 12 : 14, 0xFFB1B7C0, false);
        titleBox.addView(sectionTitle);
        titleBox.addView(channelCount);
        top.addView(titleBox, new LinearLayout.LayoutParams(0, -1, 1f));

        settingsButton = new ImageButton(context);
        settingsButton.setImageResource(R.drawable.ic_settings);
        settingsButton.setBackgroundColor(Color.TRANSPARENT);
        settingsButton.setColorFilter(Color.WHITE);
        settingsButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        settingsButton.setPadding(dp(compact ? 12 : 16), dp(compact ? 12 : 16), dp(compact ? 12 : 16), dp(compact ? 12 : 16));
        settingsButton.setFocusable(true);
        settingsButton.setClickable(true);
        settingsButton.setContentDescription(context.getString(R.string.settings_title));
        settingsButton.setOnClickListener(v -> { if (listener != null) listener.onRequestSettings(); });
        settingsButton.setOnFocusChangeListener((v, hasFocus) -> settingsButton.setColorFilter(hasFocus ? accent : Color.WHITE));
        // Settings is now a destination inside the category list, not a separate direct button.
        settingsButton.setFocusable(false);
        settingsButton.setVisibility(GONE);

        LinearLayout columns = new LinearLayout(context);
        columns.setOrientation(portraitPhone ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        if (portraitPhone) columns.setPadding(dp(7), dp(6), dp(7), dp(7));
        root.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Portrait phone is intentionally a compact stacked design: a short app tab strip,
        // a scrollable category band, the channel list as the main area, and a fixed EPG card.
        // Fixed heights prevent the four menu areas from drawing over each other on narrow phones.
        final int portraitCategoryHeight = Math.max(104, Math.min(128, Math.round(heightDp * 0.20f)));
        final int portraitDetailHeight = Math.max(142, Math.min(172, Math.round(heightDp * 0.27f)));

        appPane = pane(0xE60C0F13);
        appSidebar = new AppSidebar(context, AppSidebar.SECTION_TV, new AppSidebar.Listener() {
            @Override public void onTv() {
                selectedCategory = 0;
                applyCategory(false);
                categoryList.requestFocus();
                categoryAdapter.notifyDataSetChanged();
                channelAdapter.notifyDataSetChanged();
            }
            @Override public void onMovies() {
                hide();
                if (listener != null) listener.onRequestMovies();
            }
            @Override public void onSeries() {
                hide();
                if (listener != null) listener.onRequestSeries();
            }
            @Override public void onSettings() {
                hide();
                if (listener != null) listener.onRequestSettings();
            }
        }, true);
        appPane.addView(appSidebar, new LinearLayout.LayoutParams(-1, -1));
        if (portraitPhone) appPane.setBackground(rounded(0xE60C0F13, 12));
        LinearLayout.LayoutParams appPaneParams = portraitPhone
                ? new LinearLayout.LayoutParams(-1, dp(62))
                : new LinearLayout.LayoutParams(0, -1, 0.18f);
        if (portraitPhone) appPaneParams.bottomMargin = dp(6);
        columns.addView(appPane, appPaneParams);

        View divider0 = new View(context);
        divider0.setBackgroundColor(0x7730343B);
        columns.addView(divider0, portraitPhone
                ? new LinearLayout.LayoutParams(-1, 0)
                : new LinearLayout.LayoutParams(dp(1), -1));

        categoryPane = pane(0xC00D0F13);
        TextView categoryHeader = text(context.getString(R.string.categories), compact ? 11 : 13, 0xFFB1B7C0, true);
        categoryHeader.setPadding(dp(portraitPhone ? 12 : (compact ? 12 : 22)),
                dp(portraitPhone ? 5 : (compact ? 8 : 18)), dp(8), dp(portraitPhone ? 3 : (compact ? 6 : 10)));
        categoryPane.addView(categoryHeader, new LinearLayout.LayoutParams(-1, dp(portraitPhone ? 30 : (compact ? 38 : 54))));
        categoryList = new ListView(context);
        categoryList.setDividerHeight(0);
        categoryList.setSelector(android.R.color.transparent);
        categoryList.setAdapter(categoryAdapter);
        categoryPane.addView(categoryList, new LinearLayout.LayoutParams(-1, 0, 1f));
        if (portraitPhone) categoryPane.setBackground(rounded(0xE60D0F13, 12));
        LinearLayout.LayoutParams categoryPaneParams = portraitPhone
                ? new LinearLayout.LayoutParams(-1, dp(portraitCategoryHeight))
                : new LinearLayout.LayoutParams(0, -1, 0.18f);
        if (portraitPhone) categoryPaneParams.bottomMargin = dp(6);
        columns.addView(categoryPane, categoryPaneParams);

        View divider1 = new View(context);
        divider1.setBackgroundColor(0x7730343B);
        columns.addView(divider1, portraitPhone
                ? new LinearLayout.LayoutParams(-1, 0)
                : new LinearLayout.LayoutParams(dp(1), -1));

        channelPane = pane(0xB814171C);
        channelList = new ListView(context);
        channelList.setDividerHeight(0);
        channelList.setSelector(android.R.color.transparent);
        channelList.setAdapter(channelAdapter);

        channelPane.addView(channelList, new LinearLayout.LayoutParams(-1, 0, 1f));
        empty = text(context.getString(R.string.no_channels_category), 18, 0xFFD0D4DA, false);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(GONE);
        channelPane.addView(empty, new LinearLayout.LayoutParams(-1, dp(60)));
        if (portraitPhone) channelPane.setBackground(rounded(0xEC14171C, 12));
        LinearLayout.LayoutParams channelPaneParams = portraitPhone
                ? new LinearLayout.LayoutParams(-1, 0, 1f)
                : new LinearLayout.LayoutParams(0, -1, 0.34f);
        if (portraitPhone) channelPaneParams.bottomMargin = dp(6);
        columns.addView(channelPane, channelPaneParams);

        View divider2 = new View(context);
        divider2.setBackgroundColor(0x7730343B);
        columns.addView(divider2, portraitPhone
                ? new LinearLayout.LayoutParams(-1, 0)
                : new LinearLayout.LayoutParams(dp(1), -1));

        detailPane = pane(0xC00D0F13);
        LinearLayout detail = detailPane;
        detail.setPadding(dp(portraitPhone ? 14 : (compact ? 14 : 30)), dp(portraitPhone ? 8 : (compact ? 8 : 20)),
                dp(portraitPhone ? 14 : (compact ? 14 : 30)), dp(portraitPhone ? 8 : (compact ? 8 : 20)));
        if (portraitPhone) detail.setBackground(rounded(0xE60D0F13, 12));
        columns.addView(detail, portraitPhone
                ? new LinearLayout.LayoutParams(-1, dp(portraitDetailHeight))
                : new LinearLayout.LayoutParams(0, -1, 0.30f));

        detailLogo = new ImageView(context);
        detailLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        detail.addView(detailLogo, new LinearLayout.LayoutParams(-1, dp(portraitPhone ? 42 : (compact ? 70 : 105))));

        // v3.0.6: keep the right-side guide pane focused on programme information.
        // Channel number/name/group already exist in the centre channel list, so showing
        // them again here wastes vertical space and can push NEXT off-screen.
        detailNumber = text("", compact ? 12 : 15, accent, true);
        detailNumber.setVisibility(GONE);
        detail.addView(detailNumber, new LinearLayout.LayoutParams(-1, 0));
        detailName = text("", compact ? 19 : 26, Color.WHITE, true);
        detailName.setVisibility(GONE);
        detail.addView(detailName, new LinearLayout.LayoutParams(-1, 0));
        detailGroup = text("", compact ? 12 : 14, 0xFFB1B7C0, false);
        detailGroup.setVisibility(GONE);
        detail.addView(detailGroup, new LinearLayout.LayoutParams(-1, 0));

        TextView nowLabel = text(context.getString(R.string.now), 13, accent, true);
        detail.addView(nowLabel);
        nowTime = text("", compact ? 12 : 14, 0xFFCDD2D8, false);
        detail.addView(nowTime);
        nowTitle = text(context.getString(R.string.no_epg), compact ? 16 : 20, Color.WHITE, true);
        nowTitle.setPadding(0, dp(3), 0, dp(5));
        detail.addView(nowTitle);
        nowProgress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        nowProgress.setMax(1000);
        UiTheme.tintProgress(context, nowProgress);
        detail.addView(nowProgress, new LinearLayout.LayoutParams(-1, dp(7)));
        // Description remains available with UP / programme information. Hiding it from
        // this compact right pane guarantees room for both NOW and NEXT.
        nowDescription = text("", compact ? 12 : 14, 0xFFC3C9D1, false);
        nowDescription.setVisibility(GONE);
        detail.addView(nowDescription, new LinearLayout.LayoutParams(-1, 0));

        TextView nextLabel = text(context.getString(R.string.next), 13, accent, true);
        nextLabel.setPadding(0, dp(16), 0, 0);
        detail.addView(nextLabel);
        nextTime = text("", compact ? 12 : 14, 0xFFCDD2D8, false);
        detail.addView(nextTime);
        nextTitle = text(context.getString(R.string.no_upcoming), compact ? 14 : 17, Color.WHITE, false);
        nextTitle.setPadding(0, dp(3), 0, 0);
        detail.addView(nextTitle);

        TextView help = text(context.getString(R.string.browser_help), compact ? 11 : 13, 0xFFAEB5BF, false);
        help.setGravity(Gravity.BOTTOM | Gravity.START);
        help.setVisibility(portraitPhone ? GONE : VISIBLE);
        detail.addView(help, new LinearLayout.LayoutParams(-1, 0, 1f));

        categoryList.setOnItemClickListener((p, v, pos, id) -> {
            selectedCategory = pos;
            applyCategory(true);
            channelList.requestFocus();
        });
        categoryList.setOnItemSelectedListener(new SimpleItemSelectedListener(pos -> {
            selectedCategory = pos;
            applyCategory(false);
        }));
        channelList.setOnItemClickListener((p, v, pos, id) -> playSelected(pos));
        channelList.setOnItemSelectedListener(new SimpleItemSelectedListener(pos -> {
            selectedChannel = pos;
            updateDetail();
            channelAdapter.notifyDataSetChanged();
        }));
        categoryList.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) setFoldStage(1, true);
            categoryAdapter.notifyDataSetChanged();
        });
        channelList.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) setFoldStage(1, true);
            channelAdapter.notifyDataSetChanged();
        });
    }

    public boolean isOpen() { return getVisibility() == VISIBLE; }

    public void show(List<Channel> channels, int currentSourceIndex) {
        show(channels, currentSourceIndex, false, false);
    }

    public void show(List<Channel> channels, int currentSourceIndex, boolean focusCategories) {
        show(channels, currentSourceIndex, focusCategories, false);
    }

    public void show(List<Channel> channels, int currentSourceIndex, boolean focusCategories, boolean focusSidebar) {
        source.clear();
        if (channels != null) source.addAll(channels);
        rebuildCategories();

        Channel current = currentSourceIndex >= 0 && currentSourceIndex < source.size() ? source.get(currentSourceIndex) : null;
        applyCategory(false);
        selectedChannel = findFiltered(current);
        if (selectedChannel < 0) selectedChannel = 0;
        setVisibility(VISIBLE);
        updateClock();
        categoryList.setSelection(selectedCategory);
        channelList.setSelection(selectedChannel);
        if (focusSidebar) {
            setFoldStage(0, false);
            appSidebar.focusFirst();
        } else if (focusCategories) {
            setFoldStage(1, false);
            categoryList.requestFocus();
        } else {
            setFoldStage(1, false);
            channelList.requestFocus();
        }
        channelAdapter.notifyDataSetChanged();
        categoryAdapter.notifyDataSetChanged();
        updateDetail();

        // Keep the currently watched channel visible in context instead of pinning the
        // highlighted row to the very top of the list. This lets previous channels remain
        // visible above it and the selector travel naturally down the screen.
        if (!focusCategories && selectedChannel >= 0) {
            channelList.post(() -> centerChannelSelection(selectedChannel));
        }
    }

    public void hide() {
        if (!isOpen()) return;
        setVisibility(GONE);
        if (listener != null) listener.onClosed();
    }

    public void refreshEpg() {
        if (isOpen()) {
            channelAdapter.notifyDataSetChanged();
            updateDetail();
        }
    }

    public boolean handleKey(KeyEvent event) {
        if (!isOpen()) return false;
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return consumes(keyCode);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            // BACK closes the browser in one press and leaves live TV running behind it.
            hide();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            setFoldStage(0, true);
            appSidebar.focusFirst();
            return true;
        }
        if (appSidebar.hasFocus()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                appSidebar.moveFocus(-1);
                setFoldStage(0, true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                appSidebar.moveFocus(1);
                setFoldStage(0, true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                return appSidebar.activateFocused();
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                setFoldStage(1, true);
                categoryList.requestFocus();
                categoryAdapter.notifyDataSetChanged();
                channelAdapter.notifyDataSetChanged();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                setFoldStage(0, true);
                return true;
            }
            return true;
        }
        if (categoryList.hasFocus()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { moveCategory(-1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { moveCategory(1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (!filtered.isEmpty()) {
                    setFoldStage(1, true);
                    channelList.requestFocus();
                    channelAdapter.notifyDataSetChanged();
                    categoryAdapter.notifyDataSetChanged();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                setFoldStage(0, true);
                appSidebar.focusFirst();
                categoryAdapter.notifyDataSetChanged();
                channelAdapter.notifyDataSetChanged();
                return true;
            }
        } else {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                moveChannel(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
                moveChannel(-1); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
                moveChannel(1); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                setFoldStage(1, true);
                categoryList.requestFocus();
                categoryAdapter.notifyDataSetChanged();
                channelAdapter.notifyDataSetChanged();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // Two-stage TV menu only: once collapsed, do not create another fold level.
                setFoldStage(1, true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                playSelected(selectedChannel);
                return true;
            }
        }
        return false;
    }

    private boolean consumes(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return true;
            default:
                return false;
        }
    }

    /**
     * TV-only folding has exactly two states: expanded when the app sidebar is in focus,
     * and collapsed when the user moves right into categories/channels.
     */
    private void setFoldStage(int requestedStage, boolean animate) {
        if (compact) return; // Phone layouts keep their existing fixed geometry.
        int stage = requestedStage <= 0 ? 0 : 1;
        foldStage = stage;
        appSidebar.setExpanded(stage == 0, animate);

        final float[] target = stage == 0
                ? new float[]{0.18f, 0.18f, 0.34f, 0.30f}
                : new float[]{0.06f, 0.16f, 0.42f, 0.36f};

        final LinearLayout[] panes = new LinearLayout[]{appPane, categoryPane, channelPane, detailPane};
        final float[] start = new float[panes.length];
        for (int i = 0; i < panes.length; i++) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) panes[i].getLayoutParams();
            start[i] = lp == null ? target[i] : lp.weight;
        }

        if (foldAnimator != null) foldAnimator.cancel();
        if (!animate) {
            for (int i = 0; i < panes.length; i++) applyPaneWeight(panes[i], target[i]);
            return;
        }

        foldAnimator = ValueAnimator.ofFloat(0f, 1f);
        foldAnimator.setDuration(150L);
        foldAnimator.setInterpolator(new DecelerateInterpolator());
        foldAnimator.addUpdateListener(animation -> {
            float f = (float) animation.getAnimatedValue();
            for (int i = 0; i < panes.length; i++) {
                applyPaneWeight(panes[i], start[i] + (target[i] - start[i]) * f);
            }
        });
        foldAnimator.start();
    }

    private void applyPaneWeight(LinearLayout pane, float weight) {
        if (pane == null || pane.getLayoutParams() == null) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pane.getLayoutParams();
        lp.weight = weight;
        pane.setLayoutParams(lp);
    }

    private void moveCategory(int delta) {
        if (categories.isEmpty()) return;
        selectedCategory = Math.max(0, Math.min(categories.size() - 1, selectedCategory + delta));
        categoryList.setSelection(selectedCategory);
        applyCategory(false);
    }

    private void moveChannel(int delta) {
        if (filtered.isEmpty()) return;
        int size = filtered.size();
        int current = selectedChannel < 0 ? 0 : selectedChannel;
        int next = (current + delta) % size;
        if (next < 0) next += size;
        if (next == selectedChannel) return;

        boolean wrapped = (delta > 0 && current == size - 1 && next == 0)
                || (delta < 0 && current == 0 && next == size - 1);
        View selectedView = channelList.getSelectedView();
        int rowHeight = selectedView != null && selectedView.getHeight() > 0
                ? selectedView.getHeight() : dp(compact ? 60 : 78);
        int oldTop = selectedView != null ? selectedView.getTop() : Math.max(0, (channelList.getHeight() - rowHeight) / 2);
        int maxTop = Math.max(0, channelList.getHeight() - rowHeight);
        int targetTop = Math.max(0, Math.min(maxTop, oldTop + delta * rowHeight));

        selectedChannel = next;
        if (wrapped) {
            channelList.setSelection(selectedChannel);
            channelList.post(() -> centerChannelSelection(selectedChannel));
        } else {
            channelList.setSelectionFromTop(selectedChannel, targetTop);
        }
        updateDetail();
        channelAdapter.notifyDataSetChanged();
    }

    private void centerChannelSelection(int position) {
        if (position < 0 || position >= filtered.size() || channelList.getHeight() <= 0) return;
        View selectedView = channelList.getSelectedView();
        int rowHeight = selectedView != null && selectedView.getHeight() > 0
                ? selectedView.getHeight() : dp(compact ? 60 : 78);
        int top = Math.max(0, (channelList.getHeight() - rowHeight) / 2);
        channelList.setSelectionFromTop(position, top);
    }

    private void playSelected(int pos) {
        if (pos < 0 || pos >= filtered.size()) return;
        Channel channel = filtered.get(pos);
        int sourceIndex = findSource(channel.stableKey());
        hide();
        if (sourceIndex >= 0 && listener != null) listener.onPlayChannel(sourceIndex);
    }

    private void toggleFavorite() {
        if (selectedChannel < 0 || selectedChannel >= filtered.size()) return;
        Channel channel = filtered.get(selectedChannel);
        boolean favorite = config.toggleFavorite(channel.stableKey());
        if (!categories.isEmpty()
                && "favorites".equals(categories.get(selectedCategory).key)
                && !favorite) {
            applyCategory(false);
        } else {
            rebuildCategories();
            categoryAdapter.notifyDataSetChanged();
            channelAdapter.notifyDataSetChanged();
            updateDetail();
        }
        if (listener != null) listener.onFavoriteChanged(channel, favorite);
    }

    private void rebuildCategories() {
        String selectedKey = categories.isEmpty() || selectedCategory < 0 || selectedCategory >= categories.size()
                ? "all" : categories.get(selectedCategory).key;
        categories.clear();
        categories.add(new Category("all", getContext().getString(R.string.all_channels), source.size()));

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Channel channel : source) {
            String group = displayGroup(channel);
            counts.put(group, counts.getOrDefault(group, 0) + 1);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            categories.add(new Category("group:" + entry.getKey(), entry.getKey(), entry.getValue()));
        }
        // Favorites stay after the channel groups. Top-level Nyama+ destinations
        // are now shown in the dedicated sidebar, not mixed into TV categories.
        categories.add(new Category("favorites", getContext().getString(R.string.favorites), countFavorites()));
        int restored = findCategoryByKey(selectedKey);
        if (restored >= 0) selectedCategory = restored;
        else if (selectedCategory >= categories.size()) selectedCategory = Math.max(0, categories.size() - 1);
    }

    private void applyCategory(boolean focusChannels) {
        if (categories.isEmpty()) return;
        selectedCategory = Math.max(0, Math.min(categories.size() - 1, selectedCategory));
        Category category = categories.get(selectedCategory);
        filtered.clear();
        Set<String> favorites = config.favoriteKeys();
        for (Channel channel : source) {
            if ("favorites".equals(category.key) && favorites.contains(channel.stableKey())) filtered.add(channel);
            else if ("all".equals(category.key)) filtered.add(channel);
            else if (category.key.startsWith("group:") && category.key.substring(6).equals(displayGroup(channel))) filtered.add(channel);
        }
        selectedChannel = filtered.isEmpty() ? -1 : Math.min(Math.max(selectedChannel, 0), filtered.size() - 1);
        sectionTitle.setText(category.label);
        channelCount.setText(getResources().getQuantityString(R.plurals.channel_count, filtered.size(), filtered.size()));
        empty.setVisibility(filtered.isEmpty() ? VISIBLE : GONE);
        categoryAdapter.notifyDataSetChanged();
        channelAdapter.notifyDataSetChanged();
        if (!filtered.isEmpty()) channelList.setSelection(selectedChannel);
        if (focusChannels && !filtered.isEmpty()) channelList.requestFocus();
        updateDetail();
    }

    private int findCategoryFor(Channel current) {
        if (current == null) return 0;
        int index = findCategoryByKey("group:" + displayGroup(current));
        return index >= 0 ? index : 0;
    }

    private int findCategoryByKey(String key) {
        for (int i = 0; i < categories.size(); i++) if (categories.get(i).key.equals(key)) return i;
        return -1;
    }

    private int findFiltered(Channel current) {
        if (current == null) return -1;
        for (int i = 0; i < filtered.size(); i++) if (filtered.get(i).stableKey().equals(current.stableKey())) return i;
        return -1;
    }

    private int findSource(String key) {
        for (int i = 0; i < source.size(); i++) if (source.get(i).stableKey().equals(key)) return i;
        return -1;
    }

    private int countFavorites() {
        int n = 0;
        Set<String> favorites = config.favoriteKeys();
        for (Channel channel : source) if (favorites.contains(channel.stableKey())) n++;
        return n;
    }

    private String displayGroup(Channel channel) {
        return channel == null || channel.group == null || channel.group.trim().isEmpty()
                ? getContext().getString(R.string.other) : channel.group.trim();
    }

    private void updateDetail() {
        updateClock();
        if (selectedChannel < 0 || selectedChannel >= filtered.size()) {
            detailLogo.setImageDrawable(null);
            detailNumber.setText("");
            detailName.setText(getContext().getString(R.string.no_channel_selected));
            detailGroup.setText("");
            nowTime.setText("");
            nowTitle.setText(getContext().getString(R.string.no_epg));
            nowDescription.setText("");
            nowProgress.setProgress(0);
            nextTime.setText("");
            nextTitle.setText(getContext().getString(R.string.no_upcoming));
            return;
        }
        Channel channel = filtered.get(selectedChannel);
        boolean favorite = config.isFavorite(channel.stableKey());
        String number = getContext().getString(R.string.ch_number, channel.number);
        if (favorite) number += "   " + getContext().getString(R.string.favorite_caps);
        detailNumber.setText(number);
        detailName.setText(channel.name);
        detailGroup.setText(displayGroup(channel));
        LogoRepository.load(getContext(), channel.logo, detailLogo);

        EpgEvent now = EpgRepository.current(channel);
        EpgEvent next = EpgRepository.next(channel);
        if (now != null) {
            nowTime.setText(time(now.startMs) + " - " + time(now.stopMs));
            nowTitle.setText(now.title.isEmpty() ? getContext().getString(R.string.untitled_programme) : now.title);
            nowDescription.setText(now.description);
            long duration = Math.max(1L, now.stopMs - now.startMs);
            long elapsed = Math.max(0L, Math.min(duration, System.currentTimeMillis() - now.startMs));
            nowProgress.setProgress((int) (elapsed * 1000L / duration));
        } else {
            nowTime.setText("");
            nowTitle.setText(getContext().getString(R.string.no_epg));
            nowDescription.setText("");
            nowProgress.setProgress(0);
        }
        if (next != null) {
            nextTime.setText(time(next.startMs) + " - " + time(next.stopMs));
            nextTitle.setText(next.title.isEmpty() ? getContext().getString(R.string.untitled_programme) : next.title);
        } else {
            nextTime.setText("");
            nextTitle.setText(getContext().getString(R.string.no_upcoming));
        }
    }

    private String time(long ms) { return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms)); }
    private void updateClock() {
        if (config.showClock()) clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        else clock.setText("");
    }

    private LinearLayout pane(int color) {
        LinearLayout pane = new LinearLayout(getContext());
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setBackgroundColor(color);
        return pane;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(UiTheme.sp(getContext(), sp));
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class CategoryAdapter extends BaseAdapter {
        @Override public int getCount() { return categories.size(); }
        @Override public Object getItem(int position) { return categories.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView instanceof TextView ? (TextView) convertView : text("", 18, Color.WHITE, false);
            row.setPadding(dp(portraitPhone ? 14 : (compact ? 12 : 22)), 0, dp(compact ? 8 : 14), 0);
            row.setMinHeight(dp(portraitPhone ? 40 : (compact ? 44 : 58)));
            if (portraitPhone) row.setTextSize(UiTheme.sp(getContext(), 15));
            Category category = categories.get(position);
            String prefix = "favorites".equals(category.key) ? "★  " : "";
            row.setText(prefix + category.label + (category.count >= 0 ? "   " + category.count : ""));
            boolean selected = position == selectedCategory;
            if (selected) {
                row.setTextColor(Color.WHITE);
                row.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                row.setBackground(rounded(categoryList.hasFocus() ? accent : ROW_SELECTED, 8));
            } else {
                row.setTextColor(0xFFD7DBE2);
                row.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                row.setBackgroundColor(Color.TRANSPARENT);
            }
            return row;
        }
    }

    private final class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public Object getItem(int position) { return filtered.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView number;
            ImageView logo;
            TextView name;
            TextView programme;
            TextView favorite;
            if (convertView instanceof LinearLayout && ((LinearLayout) convertView).getChildCount() == 4) {
                row = (LinearLayout) convertView;
                number = (TextView) row.getChildAt(0);
                logo = (ImageView) row.getChildAt(1);
                LinearLayout labels = (LinearLayout) row.getChildAt(2);
                name = (TextView) labels.getChildAt(0);
                programme = (TextView) labels.getChildAt(1);
                favorite = (TextView) row.getChildAt(3);
            } else {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(portraitPhone ? 8 : (compact ? 6 : 10)), dp(compact ? 3 : 6),
                        dp(portraitPhone ? 10 : (compact ? 8 : 12)), dp(compact ? 3 : 6));
                row.setMinimumHeight(dp(portraitPhone ? 58 : (compact ? 60 : 78)));

                number = text("", compact ? 13 : 16, 0xFFB4BBC5, true);
                number.setGravity(Gravity.CENTER);
                row.addView(number, new LinearLayout.LayoutParams(dp(compact ? 42 : 58), -1));

                logo = new ImageView(getContext());
                logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                row.addView(logo, new LinearLayout.LayoutParams(dp(compact ? 58 : 82), dp(compact ? 44 : 58)));

                LinearLayout labels = new LinearLayout(getContext());
                labels.setOrientation(LinearLayout.VERTICAL);
                labels.setGravity(Gravity.CENTER_VERTICAL);
                labels.setPadding(dp(compact ? 9 : 16), 0, dp(8), 0);
                name = text("", compact ? 16 : 19, Color.WHITE, true);
                programme = text("", compact ? 12 : 14, 0xFFB7BDC6, false);
                labels.addView(name);
                labels.addView(programme);
                row.addView(labels, new LinearLayout.LayoutParams(0, -1, 1f));

                favorite = text("", 22, 0xFFFFD54F, false);
                favorite.setGravity(Gravity.CENTER);
                row.addView(favorite, new LinearLayout.LayoutParams(dp(38), -1));
            }

            Channel channel = filtered.get(position);
            number.setText(String.valueOf(channel.number));
            name.setText(channel.name);
            EpgEvent now = EpgRepository.current(channel);
            programme.setText(now == null || now.title.isEmpty() ? displayGroup(channel) : now.title);
            favorite.setText(config.isFavorite(channel.stableKey()) ? "★" : "");
            LogoRepository.load(getContext(), channel.logo, logo);

            boolean selected = position == selectedChannel;
            row.setBackground(selected ? rounded(channelList.hasFocus() ? accent : ROW_SELECTED, 8) : rounded(Color.TRANSPARENT, 8));
            programme.setTextColor(selected && channelList.hasFocus() ? 0xFFF0FFFC : 0xFFB7BDC6);
            return row;
        }
    }

    private static final class Category {
        final String key;
        final String label;
        final int count;
        Category(String key, String label, int count) { this.key = key; this.label = label; this.count = count; }
    }

    static final class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        interface Callback { void selected(int position); }
        private final Callback callback;
        SimpleItemSelectedListener(Callback callback) { this.callback = callback; }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { callback.selected(position); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }
}
