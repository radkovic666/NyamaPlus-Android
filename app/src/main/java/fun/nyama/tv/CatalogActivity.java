package fun.nyama.tv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Native Pukanki catalog with TV-first Nyama+ navigation and styling. */
public final class CatalogActivity extends Activity {
    public static final String EXTRA_SECTION = "section";
    private static final int PAGE_SIZE = 30;
    private static final String STATE_PREFS = "nyama_plus_catalog_state";
    private static final int SIDEBAR_COLLAPSED_DP = 68;
    private static final int SIDEBAR_EXPANDED_DP = 205;

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final ExecutorService rowsIo = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable liveSearch = this::handleSearchChanged;
    private final AtomicInteger requestGeneration = new AtomicInteger();
    private Config config;
    private MovieFavorites favorites;
    private String section;
    private int page = 1;
    private int totalPages = 1;
    private int total = 0;
    private String sortMode = "reviews";
    private boolean favoritesOnly;
    private boolean browseRequested;
    private boolean dailyBrowsing;
    private List<CatalogItem> dailyBrowseItems;
    private String browseLabel = "";
    private int landingScrollY;
    private LinearLayout paging;
    private final ArrayList<String> selectedGenres = new ArrayList<>();
    private List<String> availableGenres = new ArrayList<>();
    private int restorePosition = -1;
    private String restoredQuery = "";
    private boolean firstResume = true;
    private boolean firstGridFocus = true;
    private boolean landingFocusPending = true;
    private AppSidebar sidebar;
    private boolean sidebarExpanded;
    private int lastContentFocus;
    private int catalogColumns = 5;
    private ValueAnimator sidebarAnimator;

    // Reuse already complete catalog snapshots when present. Otherwise query/page requests
    // validate only visible posters; launching a catalog never starts a whole-catalog scan.
    // Mobile behavior intentionally remains unchanged.
    private final ArrayList<CatalogItem> tvRawCatalog = new ArrayList<>();
    private final ArrayList<CatalogItem> tvVisibleCatalog = new ArrayList<>();
    private final ArrayList<CatalogItem> tvInitialVisiblePage = new ArrayList<>();
    private boolean tvCatalogReady;
    private volatile int tvDataGeneration;
    private volatile int tvSearchGeneration;
    private int tvPageGeneration;
    private TvCinematicBackground cinematicBackground;
    private String tvDataKey = "";
    private boolean focusFirstAfterPageChange;

    private EditText search;
    private ImageButton filterButton;
    private Button searchClearButton;
    private GridView grid;
    private CatalogAdapter adapter;
    private TextView status;
    private TextView pageStatus;
    private Button prev;
    private Button next;
    private ProgressBar loading;
    private Button catalogRetry;
    private final Object tvFetchLock = new Object();
    private ScrollView homeRows;
    private LinearLayout rowContainer;
    private LinearLayout tvBrowseStack;
    private TextView moreHeading;
    private View lastRowFocus;
    private int rowsGeneration;
    private final List<View> rowCards = new ArrayList<>();

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = new Config(this);
        favorites = new MovieFavorites(this);
        section = "series".equals(getIntent().getStringExtra(EXTRA_SECTION)) ? "series" : "movies";
        if (!ActivationAccess.require(this)) return;
        if (state != null) restoreState(state);
        else restorePersistentState();
        setContentView(buildUi());
        PlayerWebViewStartup.prepare(this);
        loadGenres();
        loadPage();
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing() || !ActivationAccess.require(this)) return;
        if (firstResume) { firstResume = false; return; }
        if (favoritesOnly) loadPage();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (config == null || config.isPhoneMode()) return;
        String target = "series".equals(intent.getStringExtra(EXTRA_SECTION)) ? "series" : "movies";
        if (!target.equals(section)) {
            openSection(target);
            return;
        }
        // CLEAR_TOP | SINGLE_TOP from TV movie playback reveals this same catalogue.
        // Keep its live adapter, query, filters and page rather than fetching them again.
        setIntent(intent);
        if (homeRows != null && rowContainer.getVisibility() == View.VISIBLE && lastContentFocus==3 && lastRowFocus != null) {
            lastRowFocus.requestFocus();
            return;
        }
        if (grid != null && grid.getCount() > 0) {
            final int position = Math.max(0, Math.min(grid.getCount() - 1,
                    restorePosition >= 0 ? restorePosition : grid.getSelectedItemPosition()));
            grid.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                grid.setSelection(position);
                grid.requestFocus();
            });
        }
    }

    @Override protected void onPause() {
        if (grid != null) savePersistentState();
        super.onPause();
    }

    @Override protected void onDestroy() {
        rowsGeneration++;
        requestGeneration.incrementAndGet();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        rowsIo.shutdownNow();
        if (sidebarAnimator != null) sidebarAnimator.cancel();
        tvDataGeneration++;
        tvSearchGeneration++;
        if (cinematicBackground != null) cinematicBackground.release();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("browse_requested", browseRequested);
        out.putBoolean("daily_browsing", dailyBrowsing);
        out.putString("browse_label", browseLabel);
        out.putInt("page", page);
        out.putString("query", search == null ? "" : search.getText().toString());
        out.putString("sort", sortMode);
        out.putBoolean("favorites", favoritesOnly);
        out.putStringArrayList("genres", new ArrayList<>(selectedGenres));
        if (grid != null) { int p = grid.getSelectedItemPosition(); out.putInt("grid_pos", p >= 0 ? p : grid.getFirstVisiblePosition()); }
    }

    private void restoreState(Bundle state) {
        browseRequested = state.getBoolean("browse_requested", false);
        dailyBrowsing = state.getBoolean("daily_browsing", false);
        browseLabel = state.getString("browse_label", "");
        page = Math.max(1, state.getInt("page", 1));
        restoredQuery = state.getString("query", "");
        sortMode = state.getString("sort", "reviews");
        favoritesOnly = state.getBoolean("favorites", false);
        ArrayList<String> genres = state.getStringArrayList("genres");
        if (genres != null) selectedGenres.addAll(genres);
        restorePosition = state.getInt("grid_pos", -1);
    }

    private String stateKey(String suffix) { return section + ":" + suffix; }

    private void restorePersistentState() {
        SharedPreferences p = getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
        browseRequested = p.getBoolean(stateKey("browse_requested"), false);
        dailyBrowsing = p.getBoolean(stateKey("daily_browsing"), false);
        browseLabel = p.getString(stateKey("browse_label"), "");
        page = Math.max(1, p.getInt(stateKey("page"), 1));
        restoredQuery = p.getString(stateKey("query"), "");
        sortMode = p.getString(stateKey("sort"), "reviews");
        favoritesOnly = p.getBoolean(stateKey("favorites"), false);
        restorePosition = p.getInt(stateKey("grid_pos"), -1);
        String packed = p.getString(stateKey("genres"), "");
        if (packed != null && !packed.trim().isEmpty()) {
            for (String g : packed.split("\\|\\|", -1)) if (!g.trim().isEmpty()) selectedGenres.add(g.trim());
        }
    }

    private void savePersistentState() {
        if (section == null) return;
        int pos = restorePosition;
        if (grid != null && grid.getCount() > 0) {
            int selected = grid.getSelectedItemPosition();
            pos = selected >= 0 ? selected : grid.getFirstVisiblePosition();
        }
        StringBuilder genres = new StringBuilder();
        for (String g : selectedGenres) { if (genres.length() > 0) genres.append("||"); genres.append(g); }
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
                .putBoolean(stateKey("browse_requested"), browseRequested)
                .putBoolean(stateKey("daily_browsing"), dailyBrowsing)
                .putString(stateKey("browse_label"), browseLabel)
                .putInt(stateKey("page"), page)
                .putString(stateKey("query"), search == null ? restoredQuery : search.getText().toString())
                .putString(stateKey("sort"), sortMode)
                .putBoolean(stateKey("favorites"), favoritesOnly)
                .putString(stateKey("genres"), genres.toString())
                .putInt(stateKey("grid_pos"), Math.max(0, pos))
                .apply();
    }

    private View buildUi() {
        boolean phone = config.isPhoneMode();
        int accent = UiTheme.accent(this);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setBackgroundColor(Color.TRANSPARENT);

        AppSidebar.Listener navigation = new AppSidebar.Listener() {
                    @Override public void onTv() { returnToTv(); }
                    @Override public void onMovies() { openSection("movies"); }
                    @Override public void onSeries() { openSection("series"); }
                    @Override public void onSettings() { savePersistentState(); startActivity(new Intent(CatalogActivity.this, SettingsActivity.class)); finish(); }
                };
        sidebar = new AppSidebar(this, section, navigation);
        sidebarExpanded = phone;
        if (!phone) sidebar.setExpanded(false, false);
        int sidebarWidthDp = phone ? 0 : SIDEBAR_COLLAPSED_DP;
        if (!phone) shell.addView(sidebar, new LinearLayout.LayoutParams(dp(sidebarWidthDp), -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(!phone ? 0x770A0C0F : 0xD90A0C0F);
        root.setPadding(dp(phone ? 10 : 20), dp(phone ? 8 : 12), dp(phone ? 10 : 20), dp(8));
        shell.addView(root, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout header = horizontal();
        TextView title = text(section.equals("series") ? getString(R.string.home_series) : getString(R.string.home_movies),
                phone ? 24 : 32, Color.WHITE, true);
        if (!phone) title.setLetterSpacing(0.015f);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(phone ? 46 : 48), 1f));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(phone ? 46 : 48)));

        LinearLayout controls = horizontal();
        controls.setGravity(Gravity.CENTER_VERTICAL);

        search = new EditText(this);
        search.setId(View.generateViewId());
        search.setSingleLine(true);
        search.setHint(getString(section.equals("series") ? R.string.search_series : R.string.search_movies));
        search.setText(restoredQuery);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(phone ? 0xFF707A86 : 0xFF9DAABA);
        search.setTextSize(UiTheme.sp(this, phone ? 15 : 16));
        // TV reserves room for the clear-X which is visually inside the search field.
        search.setPadding(dp(14), 0, dp(phone ? 14 : 52), 0);
        search.setBackground(panelBackground(phone ? 0xDD151A20 : 0xE0141D29,
                phone ? 0xFF2A3139 : 0xFF3B4859, 1));
        search.setOnFocusChangeListener((v, focused) -> {
            search.setBackground(panelBackground(phone ? 0xDD151A20 : 0xE0141D29,
                    focused ? accent : (phone ? 0xFF2A3139 : 0xFF3B4859), focused ? 2 : 1));
            if (focused && !phone) setCatalogSidebarExpanded(false, true);
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            main.removeCallbacks(liveSearch);
            page = 1;
            if (phone) loadPage(); else handleSearchChanged();
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchClearButton != null) searchClearButton.setVisibility(phone && s.length() == 0 ? View.GONE : View.VISIBLE);
                if (!phone) { tvSearchGeneration++; tvPageGeneration++; }
                main.removeCallbacks(liveSearch);
                main.postDelayed(liveSearch, 320L);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        if (phone) {
            controls.addView(search, new LinearLayout.LayoutParams(0, dp(46), 1f));
        } else {
            FrameLayout searchBox = new FrameLayout(this);
            searchBox.addView(search, new FrameLayout.LayoutParams(-1, dp(44)));

            searchClearButton = new Button(this);
            searchClearButton.setId(View.generateViewId());
            searchClearButton.setText("×");
            searchClearButton.setAllCaps(false);
            searchClearButton.setTextColor(0xFFD7DDE5);
            searchClearButton.setTextSize(UiTheme.sp(this, 24));
            searchClearButton.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            searchClearButton.setGravity(Gravity.CENTER);
            searchClearButton.setPadding(0, 0, 0, dp(2));
            searchClearButton.setMinWidth(0);
            searchClearButton.setMinimumWidth(0);
            searchClearButton.setMinHeight(0);
            searchClearButton.setMinimumHeight(0);
            searchClearButton.setFocusable(true);
            searchClearButton.setClickable(true);
            searchClearButton.setBackground(panelBackground(0x00151A20, 0x002A3139, 0));
            searchClearButton.setContentDescription(getString(R.string.clear_search));
            searchClearButton.setVisibility(View.VISIBLE);
            searchClearButton.setOnClickListener(v -> resetTvCatalog());
            searchClearButton.setOnFocusChangeListener((v, focused) -> {
                searchClearButton.setTextColor(focused ? Color.WHITE : 0xFFD7DDE5);
                searchClearButton.setBackground(panelBackground(
                        focused ? ((accent & 0x00FFFFFF) | 0x33000000) : 0x00151A20,
                        focused ? accent : 0x002A3139, focused ? 2 : 0));
                if (focused) setCatalogSidebarExpanded(false, true);
            });
            FrameLayout.LayoutParams clearLp = new FrameLayout.LayoutParams(dp(42), dp(40), Gravity.END | Gravity.CENTER_VERTICAL);
            clearLp.rightMargin = dp(2);
            searchBox.addView(searchClearButton, clearLp);
            controls.addView(searchBox, new LinearLayout.LayoutParams(0, dp(44), 1f));
        }

        filterButton = new ImageButton(this);
        filterButton.setId(View.generateViewId());
        filterButton.setImageResource(R.drawable.ic_filter_list);
        filterButton.setBackground(panelBackground(phone ? 0xDD151A20 : 0xE0141D29,
                phone ? 0xFF2A3139 : 0xFF3B4859, 1));
        filterButton.setColorFilter(Color.WHITE);
        filterButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        filterButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        filterButton.setFocusable(true);
        filterButton.setClickable(true);
        filterButton.setContentDescription(getString(R.string.filters));
        filterButton.setOnClickListener(v -> showFilters());
        filterButton.setOnFocusChangeListener((v, focused) -> {
            int fill = focused ? ((accent & 0x00FFFFFF) | 0x33000000) : (phone ? 0xDD151A20 : 0xE0141D29);
            filterButton.setBackground(panelBackground(fill,
                    focused ? accent : (phone ? 0xFF2A3139 : 0xFF3B4859), focused ? 2 : 1));
            if (focused && !phone) setCatalogSidebarExpanded(false, true);
            updateFilterIndicator();
        });
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(dp(phone ? 48 : 46), dp(phone ? 46 : 44));
        filterParams.setMargins(dp(8), 0, 0, 0);
        controls.addView(filterButton, filterParams);
        if (!phone && searchClearButton != null) {
            search.setNextFocusRightId(searchClearButton.getId());
            searchClearButton.setNextFocusLeftId(search.getId());
            searchClearButton.setNextFocusRightId(filterButton.getId());
            filterButton.setNextFocusLeftId(searchClearButton.getId());
        }
        updateFilterIndicator();

        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, dp(phone ? 48 : 46));
        controlsParams.setMargins(0, dp(2), 0, dp(4));
        root.addView(controls, controlsParams);

        LinearLayout statusRow = horizontal();
        status = text(getString(R.string.loading), phone ? 12 : 13, phone ? 0xFF9CA6B1 : 0xFFC2CCDA, false);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, dp(26), 1f));
        loading = new ProgressBar(this);
        loading.setIndeterminate(true);
        statusRow.addView(loading, new LinearLayout.LayoutParams(dp(24), dp(24)));
        catalogRetry = smallButton(Config.LANG_EN.equals(config.language()) ? "Retry" : "Опитай отново");
        catalogRetry.setVisibility(View.GONE);
        catalogRetry.setOnClickListener(v -> loadPage());
        if (!phone) statusRow.addView(catalogRetry, new LinearLayout.LayoutParams(dp(150), dp(28)));
        root.addView(statusRow, new LinearLayout.LayoutParams(-1, dp(28)));
        if (!phone) {
            homeRows = new ScrollView(this); homeRows.setTag("tv-catalog-rows");
            homeRows.setClipToPadding(false); homeRows.setFocusable(false);
            homeRows.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            tvBrowseStack = new LinearLayout(this); tvBrowseStack.setOrientation(LinearLayout.VERTICAL);
            rowContainer = new LinearLayout(this); rowContainer.setOrientation(LinearLayout.VERTICAL);
            rowContainer.setVisibility(View.GONE);
            tvBrowseStack.addView(rowContainer,new LinearLayout.LayoutParams(-1,-2));
            moreHeading=text(Config.LANG_EN.equals(config.language())?"More":"Още",18,0xFFC2CCDA,true);
            moreHeading.setTag("tv-catalog-more");moreHeading.setPadding(dp(8),dp(16),dp(8),dp(8));moreHeading.setVisibility(View.GONE);
            tvBrowseStack.addView(moreHeading,new LinearLayout.LayoutParams(-1,-2));
            homeRows.addView(tvBrowseStack, new ScrollView.LayoutParams(-1, -2));
            homeRows.setOnScrollChangeListener((v, x, y, oldX, oldY) -> loadVisibleRowPosters());
            root.addView(homeRows, new LinearLayout.LayoutParams(-1, 0, 1f));
        }

        grid = phone ? new GridView(this) : new GridView(this) {
            @Override protected void onMeasure(int width, int height) {
                // Only the current bounded page is measured; the outer viewport scrolls rows,
                // this same grid and pagination together. There is no second catalog adapter.
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(MEASURED_SIZE_MASK, MeasureSpec.AT_MOST));
            }
        };
        catalogColumns = phone ? calculateColumns(145, 2, sidebarWidthDp) : calculateColumns(132, 4, SIDEBAR_COLLAPSED_DP);
        if (!phone) catalogColumns = Math.min(6, Math.max(4, catalogColumns));
        if (phone && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) catalogColumns = Math.max(catalogColumns, 3);
        grid.setNumColumns(catalogColumns);
        grid.setHorizontalSpacing(dp(phone ? 8 : 12));
        grid.setVerticalSpacing(dp(phone ? 9 : 12));
        grid.setPadding(dp(phone ? 2 : 8), dp(phone ? 4 : 8), dp(phone ? 2 : 8), dp(phone ? 6 : 10));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        // TV cards draw their own exact accent background and contrasting focus outline.
        grid.setSelector(new ColorDrawable(phone ? (accent & 0x00FFFFFF) | 0x55000000 : Color.TRANSPARENT));
        adapter = new CatalogAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnFocusChangeListener((v, focused) -> {
            if(focused&&!phone){
                lastContentFocus=2;
                View selected=grid.getSelectedView();
                if(selected!=null)selected.requestRectangleOnScreen(new Rect(0,0,selected.getWidth(),selected.getHeight()),false);
            }
            if (focused && !phone) setCatalogSidebarExpanded(false, true);
            if (!phone) updateTvCardHighlights();
        });
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if(!phone)lastContentFocus=2;
            openDetail(adapter.getItem(position));
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private View previous;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!phone) {
                    if (previous != null && previous != view) updateTvCardAppearance(previous);
                    updateTvCardHighlights();
                    if (view != null) updateTvCardAppearance(view);
                    if (view != null && grid.hasFocus()) view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),false);
                    previous = view;
                    showCatalogArtwork(adapter.getItem(position));
                    return;
                }
                if (previous != null && previous != view) {
                    previous.animate().scaleX(1f).scaleY(1f).setDuration(110L).start();
                    if (android.os.Build.VERSION.SDK_INT >= 21) previous.setElevation(0f);
                }
                if (view != null) {
                    view.animate().scaleX(1.035f).scaleY(1.035f).setDuration(110L).start();
                    if (android.os.Build.VERSION.SDK_INT >= 21) view.setElevation(dp(10));
                }
                previous = view;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
                if (!phone) {
                    if (previous != null) updateTvCardAppearance(previous);
                    previous = null;
                    updateTvCardHighlights();
                    return;
                }
                if (previous != null) {
                    previous.animate().scaleX(1f).scaleY(1f).setDuration(110L).start();
                    if (android.os.Build.VERSION.SDK_INT >= 21) previous.setElevation(0f);
                }
                previous = null;
            }
        });
        if(phone) root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        else tvBrowseStack.addView(grid,new LinearLayout.LayoutParams(-1,-2));

        paging = horizontal();
        prev = smallButton(getString(R.string.previous_page));
        next = smallButton(getString(R.string.next_page));
        pageStatus = text("", 12, 0xFFC6CDD6, true);
        pageStatus.setGravity(Gravity.CENTER);
        prev.setOnClickListener(v -> changeCatalogPage(-1));
        next.setOnClickListener(v -> changeCatalogPage(1));
        paging.addView(prev, new LinearLayout.LayoutParams(dp(phone ? 88 : 112), dp(38)));
        paging.addView(pageStatus, new LinearLayout.LayoutParams(0, dp(38), 1f));
        paging.addView(next, new LinearLayout.LayoutParams(dp(phone ? 88 : 112), dp(38)));
        paging.setTag("catalog-pagination");
        if(phone) root.addView(paging, new LinearLayout.LayoutParams(-1, dp(48)));
        else tvBrowseStack.addView(paging,new LinearLayout.LayoutParams(-1,dp(40)));

        if (!phone) {
            grid.setOnKeyListener((view,key,event)->{
                if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                int position=grid.getSelectedItemPosition();
                if(key==KeyEvent.KEYCODE_DPAD_UP && position>=0 && position<catalogColumns
                        && rowContainer.getVisibility()==View.VISIBLE && !rowCards.isEmpty()) {
                    rowCards.get(rowCards.size()-1).requestFocus(); return true;
                }
                int lastRow=grid.getCount()==0?0:((grid.getCount()-1)/catalogColumns)*catalogColumns;
                if(key==KeyEvent.KEYCODE_DPAD_DOWN && position>=lastRow) {
                    if(next.isEnabled()){next.requestFocus();return true;}
                    if(prev.isEnabled()){prev.requestFocus();return true;}
                }
                return false;
            });
            grid.post(() -> { if (grid.getCount() > 0) grid.requestFocus(); else search.requestFocus(); });
        }
        if (!phone) {
            // Artwork is a visual layer only: browsing must never acquire audio focus or touch TV playback.
            FrameLayout cinematicShell = new FrameLayout(this);
            cinematicBackground = new TvCinematicBackground(this);
            cinematicShell.addView(cinematicBackground, new FrameLayout.LayoutParams(-1, -1));
            cinematicShell.addView(shell, new FrameLayout.LayoutParams(-1, -1));
            return cinematicShell;
        }
        return phone ? MobileBottomNav.wrap(this, shell, section, navigation) : shell;
    }

    private void showCatalogArtwork(CatalogItem item) {
        if (cinematicBackground == null || config.isPhoneMode()) return;
        if (item == null || item.id.isEmpty()) cinematicBackground.showFallback();
        else cinematicBackground.show(PukankiApi.posterUrl(this, item.id), true);
    }

    /** TV's X resets the current VOD section, without touching the TV playlist or phone search. */
    private void resetTvCatalog() {
        if (config.isPhoneMode()) return;
        // Invalidate local search results even if a worker has already queued its UI callback.
        tvSearchGeneration++;
        main.removeCallbacks(liveSearch);
        search.setText("");
        main.removeCallbacks(liveSearch);
        restoredQuery = "";
        selectedGenres.clear();
        favoritesOnly = false;
        sortMode = "reviews";
        browseRequested = false; dailyBrowsing = false; dailyBrowseItems = null; browseLabel = "";
        page = 1;
        restorePosition = 0;
        focusFirstAfterPageChange = true;
        updateFilterIndicator();
        savePersistentState();
        if (rowContainer.getChildCount() > 0) restoreLandingRows(); else loadTvPage();
    }

    private void updateTvCardHighlights() {
        if (grid == null || config.isPhoneMode()) return;
        for (int i = 0; i < grid.getChildCount(); i++) updateTvCardAppearance(grid.getChildAt(i));
    }

    private void updateTvCardAppearance(View card) {
        if (config.isPhoneMode() || !(card.getTag() instanceof Holder)) return;
        Holder h = (Holder) card.getTag();
        boolean focused = h.row ? card.hasFocus()
                : grid != null && grid.hasFocus() && h.position == grid.getSelectedItemPosition();
        boolean highlighted = focused || h.hovered;
        int accent = UiTheme.accent(this);
        int foreground = UiTheme.contrastingTextColor(accent);
        card.setBackground(panelBackground(highlighted ? accent : 0xFF11161C,
                highlighted ? foreground : 0xFF242C35, highlighted ? 2 : 1));
        h.title.setTextColor(highlighted ? foreground : Color.WHITE);
        h.meta.setTextColor(highlighted ? foreground : 0xFF9CA6B1);
        // Keep the poster image and its dark matte intact; focus is around the whole card.
        if (h.highlighted != highlighted) {
            float scale = highlighted ? 1.025f : 1f;
            card.animate().scaleX(scale).scaleY(scale).setDuration(120L).start();
            card.setElevation(highlighted ? dp(10) : 0f);
            h.highlighted = highlighted;
        }
    }

    private void setCatalogSidebarExpanded(boolean expanded, boolean animate) {
        if (config.isPhoneMode() || sidebar == null) return;
        sidebarExpanded = expanded;
        sidebar.setExpanded(expanded, animate);
        if (!(sidebar.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) sidebar.getLayoutParams();
        int target = dp(expanded ? SIDEBAR_EXPANDED_DP : SIDEBAR_COLLAPSED_DP);
        int start = lp.width > 0 ? lp.width : target;
        if (sidebarAnimator != null) sidebarAnimator.cancel();
        if (!animate || start == target) {
            lp.width = target;
            sidebar.setLayoutParams(lp);
            return;
        }
        sidebarAnimator = ValueAnimator.ofInt(start, target);
        sidebarAnimator.setDuration(140L);
        sidebarAnimator.setInterpolator(new DecelerateInterpolator());
        sidebarAnimator.addUpdateListener(a -> {
            LinearLayout.LayoutParams current = (LinearLayout.LayoutParams) sidebar.getLayoutParams();
            current.width = (int) a.getAnimatedValue();
            sidebar.setLayoutParams(current);
        });
        sidebarAnimator.start();
    }

    private void focusContentFromSidebar() {
        setCatalogSidebarExpanded(false, true);
        if (lastContentFocus == 3 && lastRowFocus != null && rowContainer.getVisibility() == View.VISIBLE) lastRowFocus.requestFocus();
        else if (lastContentFocus == 1 && filterButton != null) filterButton.requestFocus();
        else if (lastContentFocus == 2 && grid != null && grid.getCount() > 0) grid.requestFocus();
        else if (search != null) search.requestFocus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!config.isPhoneMode() && event.getAction() == KeyEvent.ACTION_DOWN && sidebar != null) {
            int key = event.getKeyCode();
            if (sidebar.hasMenuFocus()) {
                if (key == KeyEvent.KEYCODE_DPAD_UP) { sidebar.moveFocus(-1); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) { sidebar.moveFocus(1); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) return sidebar.activateFocused();
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { focusContentFromSidebar(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) { setCatalogSidebarExpanded(true, true); return true; }
            }

            if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (grid != null && grid.hasFocus()) {
                    int pos = grid.getSelectedItemPosition();
                    if (pos >= 0 && pos % Math.max(1, catalogColumns) == 0) {
                        lastContentFocus = 2;
                        setCatalogSidebarExpanded(true, true);
                        sidebar.focusFirst();
                        return true;
                    }
                } else if (search != null && search.hasFocus()) {
                    lastContentFocus = 0;
                    setCatalogSidebarExpanded(true, true);
                    sidebar.focusFirst();
                    return true;
                }
            }

            if (filterButton != null && filterButton.hasFocus()) lastContentFocus = 1;
            else if (grid != null && grid.hasFocus()) lastContentFocus = 2;
            else if ((search != null && search.hasFocus()) || (searchClearButton != null && searchClearButton.hasFocus())) lastContentFocus = 0;
        }
        return super.dispatchKeyEvent(event);
    }

    private void changeCatalogPage(int delta) {
        int target = page + delta;
        if (target < 1 || target > totalPages) return;
        page = target;
        restorePosition = -1;
        focusFirstAfterPageChange = !config.isPhoneMode();
        if (!config.isPhoneMode() && tvCatalogReady && !tvVisibleCatalog.isEmpty()) {
            applyTvLocalPage(tvVisibleCatalog, currentQuery());
        } else {
            loadPage();
        }
    }

    private void returnToTv() {
        savePersistentState();
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void openSection(String target) {
        String normalized = "series".equals(target) ? "series" : "movies";
        if (normalized.equals(section)) {
            if (homeRows != null && rowContainer.getVisibility() == View.VISIBLE && lastContentFocus==3 && lastRowFocus != null) lastRowFocus.requestFocus();
            else if (grid != null && grid.getCount() > 0) grid.requestFocus();
            return;
        }
        savePersistentState();
        startActivity(new Intent(this, CatalogActivity.class).putExtra(EXTRA_SECTION, normalized));
        finish();
    }

    private void loadGenres() {
        io.execute(() -> {
            try {
                List<String> result = PukankiApi.fetchGenres(this, section);
                runOnUiThread(() -> availableGenres = result);
            } catch (Exception ignored) {}
        });
    }

    private void showFilters() {
        if (availableGenres.isEmpty()) {
            Toast.makeText(this, R.string.genres_loading, Toast.LENGTH_SHORT).show();
            loadGenres();
        }

        int accent = UiTheme.accent(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(14), dp(20), dp(18));
        panel.setBackgroundColor(0xFF101419);

        TextView favHeader = text(getString(R.string.media_favorites), 15, Color.WHITE, true);
        panel.addView(favHeader, new LinearLayout.LayoutParams(-1, dp(34)));
        CheckBox favoriteCheck = new CheckBox(this);
        favoriteCheck.setText(getString(R.string.media_favorites));
        favoriteCheck.setTextColor(0xFFDDE3EA);
        favoriteCheck.setTextSize(UiTheme.sp(this, 15));
        favoriteCheck.setChecked(favoritesOnly);
        favoriteCheck.setFocusable(true);
        favoriteCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
        panel.addView(favoriteCheck, new LinearLayout.LayoutParams(-1, dp(46)));

        TextView sortLabel = text(getString(R.string.sort_order), 15, Color.WHITE, true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(38));
        slp.topMargin = dp(8);
        panel.addView(sortLabel, slp);

        String[] sortLabels = {
                getString(R.string.sort_popular), getString(R.string.sort_rating), getString(R.string.sort_newest),
                getString(R.string.sort_year_desc), getString(R.string.sort_year_asc), getString(R.string.sort_title)
        };
        String[] modes = {"reviews", "rating", "latest", "year_desc", "year_asc", "title"};
        RadioGroup sortGroup = new RadioGroup(this);
        sortGroup.setOrientation(RadioGroup.VERTICAL);
        int initial = Math.max(0, java.util.Arrays.asList(modes).indexOf(sortMode));
        final int[] sortIds = new int[sortLabels.length];
        for (int i = 0; i < sortLabels.length; i++) {
            RadioButton rb = new RadioButton(this);
            sortIds[i] = View.generateViewId();
            rb.setId(sortIds[i]);
            rb.setText(sortLabels[i]);
            rb.setTextColor(0xFFD8DEE6);
            rb.setTextSize(UiTheme.sp(this, 14));
            rb.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
            rb.setFocusable(true);
            sortGroup.addView(rb, new RadioGroup.LayoutParams(-1, dp(40)));
            if (i == initial) rb.setChecked(true);
        }
        panel.addView(sortGroup, new LinearLayout.LayoutParams(-1, -2));

        TextView genreLabel = text(getString(R.string.genres), 15, Color.WHITE, true);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, dp(42));
        glp.topMargin = dp(12);
        panel.addView(genreLabel, glp);

        final ArrayList<CheckBox> genreChecks = new ArrayList<>();
        for (String genre : availableGenres) {
            CheckBox cb = new CheckBox(this);
            cb.setText(genre);
            cb.setTextColor(0xFFD8DEE6);
            cb.setTextSize(UiTheme.sp(this, 14));
            cb.setChecked(selectedGenres.contains(genre));
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
            cb.setFocusable(true);
            genreChecks.add(cb);
            panel.addView(cb, new LinearLayout.LayoutParams(-1, dp(40)));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.filters)
                .setView(scroll)
                .setPositiveButton(R.string.apply, null)
                .setNeutralButton(R.string.clear, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0xFF11161C));
                dialog.getWindow().setLayout(config.isPhoneMode() ? ViewGroup.LayoutParams.MATCH_PARENT : dp(520),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(0xFFB8C0CA);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFFB8C0CA);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                browseRequested = true; dailyBrowsing = false; dailyBrowseItems = null; browseLabel = "";
                favoritesOnly = favoriteCheck.isChecked();
                int checked = 0;
                for (int i = 0; i < sortIds.length; i++)
                    if (sortGroup.getCheckedRadioButtonId() == sortIds[i]) { checked = i; break; }
                sortMode = modes[Math.max(0, Math.min(modes.length - 1, checked))];
                selectedGenres.clear();
                for (int i = 0; i < genreChecks.size(); i++) {
                    if (genreChecks.get(i).isChecked()) selectedGenres.add(availableGenres.get(i));
                }
                page = 1;
                restorePosition = 0;
                updateFilterIndicator();
                savePersistentState();
                loadPage();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                browseRequested = false; dailyBrowsing = false; dailyBrowseItems = null; browseLabel = "";
                favoritesOnly = false;
                sortMode = "reviews";
                selectedGenres.clear();
                page = 1;
                restorePosition = 0;
                updateFilterIndicator();
                savePersistentState();
                loadPage();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void updateFilterIndicator() {
        if (filterButton == null) return;
        boolean active = favoritesOnly || !selectedGenres.isEmpty() || !"reviews".equals(sortMode);
        filterButton.setColorFilter(active ? UiTheme.accent(this) : Color.WHITE);
    }

    private void handleSearchChanged() {
        page = 1;
        restorePosition = 0;
        if (config.isPhoneMode()) {
            loadPage();
            return;
        }
        browseRequested = !currentQuery().isEmpty() || browseRequested;
        dailyBrowsing = false; dailyBrowseItems = null; browseLabel = "";
        ++tvSearchGeneration;
        if (tvCatalogReady) { applyTvLocalPage(tvVisibleCatalog, currentQuery()); return; }
        if (currentQuery().isEmpty() && !tvInitialVisiblePage.isEmpty()) {
            applyTvLocalPage(tvInitialVisiblePage, "");
        }
        loadTvPage();
    }

    private String currentQuery() {
        return search == null ? "" : search.getText().toString().trim();
    }

    private void loadPage() {
        if (config.isPhoneMode()) {
            loadPhonePage();
            return;
        }
        loadTvPage();
    }

    private void loadPhonePage() {
        int generation = requestGeneration.incrementAndGet();
        loading.setVisibility(View.VISIBLE);
        status.setText(R.string.loading);
        prev.setEnabled(false); next.setEnabled(false);
        String q = currentQuery();
        List<String> genres = new ArrayList<>(selectedGenres);
        String favIds = favorites.csv();
        int requestedPage = page;
        io.execute(() -> {
            try {
                CatalogPage result = PukankiApi.fetchCatalog(this, section, requestedPage, PAGE_SIZE,
                        q, genres, sortMode, favoritesOnly, favIds);
                if (generation != requestGeneration.get()) return;
                runOnUiThread(() -> applyPage(result));
            } catch (Exception e) {
                if (generation != requestGeneration.get()) return;
                runOnUiThread(() -> showLoadFailure(e));
            }
        });
    }

    /** Immutable state prevents an old filter request from being cached under new filter values. */
    private static final class TvCatalogRequest {
        final String section;
        final List<String> genres;
        final String sort;
        final boolean favoritesOnly;
        final String favoriteIds;
        final int page;
        final String key;

        TvCatalogRequest(String section, List<String> genres, String sort, boolean favoritesOnly,
                         String favoriteIds, int page) {
            this.section = section;
            this.genres = new ArrayList<>(genres);
            this.sort = sort;
            this.favoritesOnly = favoritesOnly;
            this.favoriteIds = favoriteIds;
            this.page = page;
            StringBuilder keyBuilder = new StringBuilder(section).append('|').append(sort).append('|')
                    .append(favoritesOnly).append('|').append(favoriteIds);
            for (String genre : this.genres) keyBuilder.append('|').append(genre);
            key = keyBuilder.toString();
        }
    }

    private boolean isTvRequestCurrent(int generation) {
        return generation == tvDataGeneration && !isFinishing() && !isDestroyed();
    }

    private void loadTvPage() {
        if (dailyBrowsing) {
            updateDailyRow();
            if (dailyBrowseItems != null) { applyTvLocalPage(dailyBrowseItems, ""); return; }
            final int generation = ++tvPageGeneration;
            loading.setVisibility(View.VISIBLE);
            rowsIo.execute(() -> {
                List<CatalogRows.Row> saved = CatalogRows.observeAndRead(getApplicationContext(), section,
                        java.util.Collections.emptyList(), System.currentTimeMillis());
                List<CatalogItem> items = new ArrayList<>();
                for (CatalogRows.Row row : saved) if ("featured".equals(row.key)) items.addAll(row.items);
                main.post(() -> {
                    if (!dailyBrowsing || generation != tvPageGeneration || isFinishing() || isDestroyed()) return;
                    dailyBrowseItems = items; applyTvLocalPage(items, "");
                });
            });
            return;
        }
        final TvCatalogRequest request = new TvCatalogRequest(section, selectedGenres, sortMode,
                favoritesOnly, favorites.csv(), page);
        final String query = currentQuery();
        if (!request.key.equals(tvDataKey)) {
            tvDataKey = request.key;
            tvDataGeneration++;
            tvCatalogReady = false;
            tvVisibleCatalog.clear();
            tvInitialVisiblePage.clear();
            synchronized (tvRawCatalog) { tvRawCatalog.clear(); }
        }
        final int dataGeneration = tvDataGeneration;
        final int pageGeneration = ++tvPageGeneration;
        final int searchGeneration = tvSearchGeneration;
        if (tvCatalogReady) { applyTvLocalPage(tvVisibleCatalog, query); return; }
        List<CatalogItem> cached = PukankiApi.getCachedVisibleTvCatalog(this, request.section,
                request.genres, request.sort, request.favoritesOnly, request.favoriteIds);
        if (cached != null) {
            tvVisibleCatalog.clear(); tvVisibleCatalog.addAll(cached); tvCatalogReady = true;
            applyTvLocalPage(tvVisibleCatalog, query); return;
        }
        List<CatalogItem> raw = PukankiApi.getCachedRawTvCatalog(this, request.section,
                request.genres, request.sort, request.favoritesOnly, request.favoriteIds);
        if (raw == null) synchronized (tvRawCatalog) { if (!tvRawCatalog.isEmpty()) raw = new ArrayList<>(tvRawCatalog); }
        final List<CatalogItem> rawSnapshot = raw;
        loading.setVisibility(View.VISIBLE);
        catalogRetry.setVisibility(View.GONE);
        status.setText(R.string.loading);
        prev.setEnabled(false); next.setEnabled(false);
        updateDailyRow();
        final Runnable timeout = () -> {
            if (currentTvPage(dataGeneration, pageGeneration, searchGeneration, query)) {
                tvPageGeneration++;
                showLoadFailure(new java.io.IOException("Catalog request timed out"));
            }
        };
        main.postDelayed(timeout, 24000L);
        io.execute(() -> {
            synchronized (tvFetchLock) {
                if (!currentTvPage(dataGeneration, pageGeneration, searchGeneration, query)) return;
                try {
                    CatalogPage result;
                    if (rawSnapshot != null) {
                        List<CatalogItem> matches = PukankiApi.filterLocal(rawSnapshot, query);
                        int pages = Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
                        int target = Math.max(1, Math.min(request.page, pages));
                        int from = Math.min(matches.size(), (target - 1) * PAGE_SIZE);
                        List<CatalogItem> visible = PukankiApi.validateTvPostersFast(this,
                                new ArrayList<>(matches.subList(from, Math.min(matches.size(), from + PAGE_SIZE))));
                        result = new CatalogPage(visible, matches.size(), target, pages);
                    } else result = PukankiApi.fetchTvPage(this, request.section, request.page, PAGE_SIZE,
                            query, request.genres, request.sort, request.favoritesOnly, request.favoriteIds);
                    main.post(() -> {
                        if (!currentTvPage(dataGeneration, pageGeneration, searchGeneration, query)) return;
                        if (query.isEmpty()) { tvInitialVisiblePage.clear(); tvInitialVisiblePage.addAll(result.items); }
                        main.removeCallbacks(timeout);
                        applyPage(result);
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (currentTvPage(dataGeneration, pageGeneration, searchGeneration, query)) { main.removeCallbacks(timeout); showLoadFailure(failure); }
                    });
                }
            }
        });
    }

    private boolean currentTvPage(int data, int paging, int searching, String query) {
        return isTvRequestCurrent(data) && paging == tvPageGeneration && searching == tvSearchGeneration;
    }

    private boolean isRowLanding() {
        return !browseRequested && currentQuery().isEmpty() && !favoritesOnly && selectedGenres.isEmpty()
                && page == 1 && "reviews".equals(sortMode);
    }

    private void updateDailyRow() {
        if (homeRows == null) return;
        rowsGeneration++;
        boolean landing = isRowLanding();
        rowContainer.setVisibility(landing && rowContainer.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        moreHeading.setVisibility(landing ? View.GONE : View.VISIBLE);
        moreHeading.setText(browseLabel.isEmpty() ? (Config.LANG_EN.equals(config.language()) ? "Results" : "Резултати") : browseLabel);
        grid.setVisibility(landing ? View.GONE : View.VISIBLE);
        paging.setVisibility(landing ? View.GONE : View.VISIBLE);
    }

    private void openCategoryBrowse(CatalogRows.Row row) {
        List<String> genres = CatalogRows.catalogGenres(row.key, availableGenres);
        if (!"featured".equals(row.key) && genres.isEmpty()) {
            status.setText(Config.LANG_EN.equals(config.language()) ? "Category unavailable. Try again shortly." : "Категорията не е достъпна. Опитайте отново след малко.");
            loadGenres(); return;
        }
        landingScrollY = homeRows.getScrollY();
        browseRequested = true; dailyBrowsing = "featured".equals(row.key);
        dailyBrowseItems = dailyBrowsing ? new ArrayList<>(row.items) : null;
        browseLabel = Config.LANG_EN.equals(config.language()) ? row.en : row.bg;
        selectedGenres.clear(); selectedGenres.addAll(genres);
        favoritesOnly = false; sortMode = "reviews"; page = 1; restorePosition = -1;
        tvDataGeneration++; tvPageGeneration++; rowsGeneration++;
        focusFirstAfterPageChange = true;
        updateDailyRow(); updateFilterIndicator(); savePersistentState();
        homeRows.scrollTo(0, 0); loadTvPage();
    }

    private void restoreLandingRows() {
        tvDataGeneration++; tvPageGeneration++; tvSearchGeneration++; rowsGeneration++;
        TvCatalogRequest landing = new TvCatalogRequest(section, selectedGenres, sortMode,
                favoritesOnly, favorites.csv(), page);
        if (!landing.key.equals(tvDataKey)) {
            // Keep row views for focus restoration, but never search the previous category's data.
            tvDataKey = ""; tvCatalogReady = false;
            tvVisibleCatalog.clear(); tvInitialVisiblePage.clear();
            synchronized (tvRawCatalog) { tvRawCatalog.clear(); }
        }
        loading.setVisibility(View.GONE); catalogRetry.setVisibility(View.GONE); status.setText("");
        updateDailyRow();
        homeRows.post(() -> {
            if (!isRowLanding() || isFinishing() || isDestroyed()) return;
            if (lastRowFocus != null) lastRowFocus.requestFocus();
            homeRows.scrollTo(0, landingScrollY);
        });
    }

    @Override public void onBackPressed() {
        if (config != null && !config.isPhoneMode() && !isRowLanding()) {
            resetTvCatalog(); return;
        }
        super.onBackPressed();
    }

    private void observeRows(List<CatalogItem> verified) {
        if (homeRows == null || !currentQuery().isEmpty() || favoritesOnly) return;
        final int generation = rowsGeneration;
        final String requestedSection = section;
        final Context application = getApplicationContext();
        final List<CatalogItem> snapshot = new ArrayList<>(verified);
        rowsIo.execute(() -> {
            List<CatalogRows.Row> rows = CatalogRows.observeAndRead(application, requestedSection, snapshot, System.currentTimeMillis());
            main.post(() -> {
                if (generation != rowsGeneration || isFinishing() || isDestroyed() || !isRowLanding()) return;
                renderRows(rows);
            });
        });
    }

    private void renderRows(List<CatalogRows.Row> rows) {
        boolean restoreFocus = rowContainer.hasFocus() || landingFocusPending;
        String focusedMore = lastRowFocus != null && lastRowFocus.getTag() instanceof String ? (String) lastRowFocus.getTag() : "";
        String focusedId = lastRowFocus != null && lastRowFocus.getTag() instanceof Holder
                ? ((Holder) lastRowFocus.getTag()).item.id : "";
        rowContainer.removeAllViews(); rowCards.clear(); lastRowFocus = null;
        for (CatalogRows.Row row : rows) {
            TextView heading = text(Config.LANG_EN.equals(config.language()) ? row.en : row.bg, 18, 0xFFC2CCDA, true);
            heading.setPadding(dp(8), dp(10), dp(8), dp(6));
            rowContainer.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            HorizontalScrollView strip = new HorizontalScrollView(this);
            strip.setHorizontalScrollBarEnabled(false); strip.setClipChildren(false); strip.setClipToPadding(false);
            strip.setTag("catalog-row-" + row.key);
            strip.setOnScrollChangeListener((v, x, y, oldX, oldY) -> loadVisibleRowPosters());
            LinearLayout cards = horizontal(); cards.setPadding(dp(8), dp(8), dp(8), dp(12));
            strip.addView(cards, new HorizontalScrollView.LayoutParams(-2, -2));
            CatalogAdapter rowAdapter = new CatalogAdapter(this); rowAdapter.setItems(row.items); rowAdapter.row = true;
            for (int i = 0; i < row.items.size(); i++) {
                CatalogItem item = row.items.get(i);
                View card = rowAdapter.getView(i, null, cards);
                card.setFocusable(true); card.setClickable(true);
                if (card.getId() == View.NO_ID) card.setId(View.generateViewId());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(144), dp(tvPosterHeightDp() + 92));
                lp.rightMargin = dp(12); cards.addView(card, lp); rowCards.add(card);
                if (focusedId.equals(item.id) && lastRowFocus == null) lastRowFocus = card;
                card.setOnClickListener(v -> openDetail(item));
                card.setOnFocusChangeListener((v, focused) -> {
                    updateTvCardAppearance(v);
                    if (focused) {
                        lastRowFocus = v; lastContentFocus = 3;
                        setCatalogSidebarExpanded(false, true); showCatalogArtwork(item);
                        v.post(this::loadVisibleRowPosters);
                    }
                });
                final int column=i;
                card.setOnKeyListener((v, key, event) -> {
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT && column==0 && event.getAction() == KeyEvent.ACTION_DOWN) {
                        lastRowFocus = v; lastContentFocus = 3;
                        setCatalogSidebarExpanded(true, true); sidebar.focusFirst(); return true;
                    }
                    if (key == KeyEvent.KEYCODE_DPAD_RIGHT && column == row.items.size()-1 && event.getAction() == KeyEvent.ACTION_DOWN) {
                        View more = rowContainer.findViewWithTag("catalog-more-" + row.key);
                        if (more != null) more.requestFocus(); return true;
                    }
                    if (key == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN && row == rows.get(rows.size()-1)) return true;
                    return false;
                });
            }
            TextView more = text((Config.LANG_EN.equals(config.language()) ? "More…" : "Още…") + "  →", 19, Color.WHITE, true);
            more.setId(View.generateViewId()); more.setTag("catalog-more-" + row.key);
            more.setGravity(Gravity.CENTER); more.setFocusable(true); more.setClickable(true);
            more.setPadding(dp(12), dp(12), dp(12), dp(12));
            more.setBackground(panelBackground(0xE51B222C, 0xFF526171, 1));
            more.setContentDescription((Config.LANG_EN.equals(config.language()) ? "More: " : "Още: ") + (Config.LANG_EN.equals(config.language()) ? row.en : row.bg));
            View lastPoster = cards.getChildCount() == 0 ? null : cards.getChildAt(cards.getChildCount()-1);
            if (lastPoster != null) { lastPoster.setNextFocusRightId(more.getId()); more.setNextFocusLeftId(lastPoster.getId()); }
            more.setNextFocusRightId(more.getId());
            more.setOnKeyListener((v,key,event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT || (key == KeyEvent.KEYCODE_DPAD_DOWN && row == rows.get(rows.size()-1))) return true;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && lastPoster != null) { lastPoster.requestFocus(); return true; }
                return false;
            });
            more.setOnFocusChangeListener((v,focused) -> {
                int accent = UiTheme.accent(this);
                more.setBackground(panelBackground(focused ? accent : 0xE51B222C, focused ? accent : 0xFF526171, focused ? 3 : 1));
                more.setTextColor(focused ? UiTheme.contrastingTextColor(accent) : Color.WHITE);
                if (focused) { lastRowFocus = more; lastContentFocus = 3; setCatalogSidebarExpanded(false,true); }
            });
            more.setOnClickListener(v -> openCategoryBrowse(row));
            LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(144), dp(tvPosterHeightDp() + 92));
            moreParams.rightMargin = dp(12); cards.addView(more, moreParams);
            if (focusedMore.equals(more.getTag())) lastRowFocus = more;
            rowContainer.addView(strip, new LinearLayout.LayoutParams(-1, -2));
        }
        if (lastRowFocus == null && !rowCards.isEmpty()) lastRowFocus = rowCards.get(0);
        boolean show = !rows.isEmpty();
        if(show)landingFocusPending=false;
        updateDailyRow();
        if (show && restoreFocus && lastRowFocus != null) lastRowFocus.requestFocus();
        homeRows.post(this::loadVisibleRowPosters);
    }

    private void loadVisibleRowPosters() {
        Rect visible = new Rect();
        List<View> cards=new ArrayList<>(rowCards);
        if(grid!=null)for(int i=0;i<grid.getChildCount();i++)cards.add(grid.getChildAt(i));
        for (View card : cards) {
            if (!(card.getTag() instanceof Holder)) continue;
            Holder holder = (Holder) card.getTag();
            if (!holder.posterRequested && card.isShown() && card.getGlobalVisibleRect(visible)) {
                holder.posterRequested = true;
                PosterLoader.load(this, holder.item.id, holder.poster);
            }
        }
    }

    private void applyTvLocalPage(List<CatalogItem> source, String query) {
        // TV keeps the fully validated catalogue in memory, but the UI remains genuinely paginated.
        // Search filters this in-memory list, then only the requested 30-title page is rendered.
        List<CatalogItem> filtered = PukankiApi.filterLocal(source, query);
        int filteredTotal = filtered.size();
        int pages = Math.max(1, (filteredTotal + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(1, Math.min(page, pages));
        int start = Math.min(filteredTotal, (safePage - 1) * PAGE_SIZE);
        int end = Math.min(filteredTotal, start + PAGE_SIZE);
        List<CatalogItem> pageItems = new ArrayList<>(filtered.subList(start, end));
        applyPage(new CatalogPage(pageItems, filteredTotal, safePage, pages));
    }

    private void showLoadFailure(Exception e) {
        loading.setVisibility(View.GONE);
        if (!config.isPhoneMode() && catalogRetry != null) catalogRetry.setVisibility(View.VISIBLE);
        status.setText(getString(R.string.catalog_failed, safeMessage(e)));
        prev.setEnabled(page > 1);
        next.setEnabled(page < totalPages);
    }

    private void applyPage(CatalogPage result) {
        page = Math.max(1, result.page);
        totalPages = Math.max(1, result.totalPages);
        total = result.total;
        adapter.setItems(result.items);
        if (!config.isPhoneMode()) {
            catalogRetry.setVisibility(View.GONE);
            updateDailyRow();
            if (isRowLanding()) observeRows(tvCatalogReady ? tvVisibleCatalog.subList(0, Math.min(CatalogRows.MAX_CANDIDATES, tvVisibleCatalog.size()))
                    : result.items);
        }
        loading.setVisibility(View.GONE);
        status.setText(config.isPhoneMode() || !isRowLanding() ? getString(R.string.catalog_count,total) : "");
        pageStatus.setText(getString(R.string.page_of, page, totalPages));
        prev.setEnabled(page > 1);
        next.setEnabled(page < totalPages);
        updateFilterIndicator();
        if (isRowLanding() && !config.isPhoneMode()) {
            focusFirstAfterPageChange = false;
            return;
        }
        if (focusFirstAfterPageChange && !config.isPhoneMode() && !result.items.isEmpty()) {
            landingFocusPending=false;
            focusFirstAfterPageChange = false;
            firstGridFocus = false;
            restorePosition = -1;
            grid.setSelection(0);
            grid.post(() -> {
                if (!isRowLanding()) { grid.setSelection(0); grid.requestFocus(); }
            });
        } else if (restorePosition >= 0 && !result.items.isEmpty()) {
            int pos = Math.min(restorePosition, result.items.size() - 1);
            grid.setSelection(pos);
            restorePosition = -1;
        } else if (firstGridFocus && !config.isPhoneMode() && !result.items.isEmpty()) {
            firstGridFocus = false;
            grid.requestFocus();
            grid.setSelection(0);
        }
    }

    private void openDetail(CatalogItem item) {
        if (item == null) return;
        restorePosition = config.isPhoneMode() ? grid.getFirstVisiblePosition()
                : Math.max(0, adapter.snapshot().indexOf(item));
        Intent i = new Intent(this, TitleDetailActivity.class);
        i.putExtra("id", item.id); i.putExtra("type", section); i.putExtra("title", item.title);
        i.putExtra("originalTitle", item.originalTitle); i.putExtra("year", item.year); i.putExtra("endYear", item.endYear);
        i.putExtra("runtime", item.runtime); i.putExtra("genres", item.genres); i.putExtra("rating", item.rating);
        i.putExtra("votes", item.votes); i.putExtra("seasons", item.seasons); i.putExtra("episodeCount", item.episodeCount);
        if (!config.isPhoneMode() && "movies".equals(section) && !item.id.isEmpty()) {
            i.putExtra("tv_artwork_url", PukankiApi.posterUrl(this, item.id));
            i.putExtra("tv_artwork_portrait", true);
        }
        startActivity(i);
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(UiTheme.sp(this, config.isPhoneMode() ? 13 : 14));
        b.setFocusable(true);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(panelBackground(0xFF151A20, 0xFF2A3139, 1));
        b.setOnFocusChangeListener((v, focused) -> b.setBackground(panelBackground(
                focused ? ((UiTheme.accent(this) & 0x00FFFFFF) | 0x33000000) : 0xFF151A20,
                focused ? UiTheme.accent(this) : 0xFF2A3139, focused ? 2 : 1)));
        return b;
    }

    private TextView text(String label, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(label); v.setTextColor(color); v.setTextSize(UiTheme.sp(this, size));
        v.setGravity(Gravity.CENTER_VERTICAL); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private GradientDrawable panelBackground(int fill, int stroke, int strokeWidthDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(config.isPhoneMode() ? 10 : 14));
        if (strokeWidthDp > 0) bg.setStroke(dp(strokeWidthDp), stroke);
        return bg;
    }

    private void addControl(LinearLayout row, View view, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), weight);
        p.setMargins(dp(6), dp(3), 0, dp(3));
        row.addView(view, p);
    }

    private void addControlFixed(LinearLayout row, View view, int widthDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(widthDp), dp(44));
        p.setMargins(dp(7), 0, 0, 0);
        row.addView(view, p);
    }

    private String cardMeta(CatalogItem item) {
        // Android TV cards intentionally stay clean: poster + title + year only.
        String year = cleanCatalogValue(item.year);
        String endYear = cleanCatalogValue(item.endYear);
        if (year.isEmpty()) return "";
        if ("series".equals(section) && !endYear.isEmpty() && !endYear.equals(year)) return year + "–" + endYear;
        return year;
    }

    private static String formatCompactCount(String value) {
        if (value == null) return "";
        String clean = value.trim().replace(",", "").replace(" ", "");
        if (clean.isEmpty()) return "";
        try {
            long n = Long.parseLong(clean);
            if (n >= 1_000_000L) {
                double m = n / 1_000_000.0;
                String out = m >= 10.0
                        ? String.format(java.util.Locale.US, "%.0fM", m)
                        : String.format(java.util.Locale.US, "%.1fM", m);
                return out.replace(".0M", "M");
            }
            if (n >= 1_000L) {
                double k = n / 1_000.0;
                String out = k >= 100.0
                        ? String.format(java.util.Locale.US, "%.0fK", k)
                        : String.format(java.util.Locale.US, "%.1fK", k);
                return out.replace(".0K", "K");
            }
            return Long.toString(n);
        } catch (NumberFormatException ignored) {
            return value.trim();
        }
    }

    private static String cleanCatalogValue(String value) {
        if (value == null) return "";
        value = value.trim();
        return value.isEmpty() || "\\N".equals(value) || "N/A".equalsIgnoreCase(value) ? "" : value;
    }

    private int calculateColumns(int targetCardDp, int minimum, int sidebarDp) {
        float density = getResources().getDisplayMetrics().density;
        int screenDp = Math.round(getResources().getDisplayMetrics().widthPixels / density);
        int available = Math.max(targetCardDp * minimum, screenDp - sidebarDp - (config.isPhoneMode() ? 26 : 58));
        return Math.max(minimum, available / targetCardDp);
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private int tvPosterHeightDp() {
        int screenHeightDp = Math.round(getResources().getDisplayMetrics().heightPixels
                / getResources().getDisplayMetrics().density);
        return screenHeightDp <= 480 ? 140 : screenHeightDp <= 600 ? 158 : 184;
    }

    private final class CatalogAdapter extends BaseAdapter {
        private final Context context;
        private final List<CatalogItem> items = new ArrayList<>();
        private boolean row;
        CatalogAdapter(Context context) { this.context = context; }
        void setItems(List<CatalogItem> fresh) { items.clear(); if (fresh != null) items.addAll(fresh); notifyDataSetChanged(); }
        List<CatalogItem> snapshot() { return new ArrayList<>(items); }
        @Override public int getCount() { return items.size(); }
        @Override public CatalogItem getItem(int position) { return position >= 0 && position < items.size() ? items.get(position) : null; }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                boolean phone = config.isPhoneMode();
                LinearLayout box = new LinearLayout(context);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(phone ? 5 : 7), dp(phone ? 5 : 7), dp(phone ? 5 : 7), dp(phone ? 7 : 9));
                box.setBackground(panelBackground(0xFF11161C, 0xFF242C35, 1));
                ImageView poster = new ImageView(context);
                poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
                if (phone) poster.setBackgroundColor(0xFF11161C);
                else {
                    GradientDrawable matte = new GradientDrawable();
                    matte.setColor(0xFF11161C);
                    matte.setCornerRadius(dp(9));
                    poster.setBackground(matte);
                    poster.setClipToOutline(true);
                }
                // Phone keeps the 1.0.7 card. TV is deliberately poster + name + year only.
                int posterHeightDp = phone ? 188 : tvPosterHeightDp();
                box.addView(poster, new LinearLayout.LayoutParams(-1, dp(posterHeightDp)));
                TextView title = text("", phone ? 13 : 15, Color.WHITE, true);
                title.setMaxLines(2);
                if (!phone) {
                    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    title.setLineSpacing(0f, 1.06f);
                }
                LinearLayout.LayoutParams t = new LinearLayout.LayoutParams(-1, dp(phone ? 44 : 48));
                t.setMargins(dp(phone ? 2 : 4), dp(phone ? 4 : 6), dp(phone ? 2 : 4), 0);
                box.addView(title, t);
                TextView meta = text("", phone ? 11 : 12, 0xFF9CA6B1, false);
                meta.setGravity(Gravity.TOP);
                meta.setLineSpacing(0f, 1.05f);
                meta.setMaxLines(1);
                LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, dp(phone ? 23 : 22));
                if (!phone) metaLp.setMargins(dp(4), 0, dp(4), 0);
                box.addView(meta, metaLp);
                box.setLayoutParams(new AbsListView.LayoutParams(-1, dp(phone ? 272 : posterHeightDp + 92)));
                h = new Holder(poster, title, meta); box.setTag(h); convertView = box;
                if (!phone) box.setOnHoverListener((v, event) -> {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE
                            || action == MotionEvent.ACTION_HOVER_EXIT) {
                        ((Holder) v.getTag()).hovered = action != MotionEvent.ACTION_HOVER_EXIT;
                        updateTvCardAppearance(v);
                        if (action != MotionEvent.ACTION_HOVER_EXIT) {
                            showCatalogArtwork(((Holder) v.getTag()).item);
                        }
                    }
                    return false;
                });
            } else h = (Holder) convertView.getTag();
            // A recycled card must not inherit another title's mouse highlight.
            if (h.position != position) h.hovered = false;
            h.position = position;
            CatalogItem item = getItem(position);
            h.item = item; h.row = row;
            if (item != null) {
                h.poster.setImageDrawable(null);
                h.poster.setTag(null);
                h.title.setText(item.title);
                if (config.isPhoneMode()) {
                    String meta = item.year;
                    if (!item.rating.isEmpty()) meta += (meta.isEmpty() ? "" : "  •  ") + "★ " + item.rating;
                    h.meta.setText(meta);
                } else {
                    h.meta.setText(cardMeta(item));
                }
                h.posterRequested=false;
                if (config.isPhoneMode()) PosterLoader.load(CatalogActivity.this, item.id, h.poster);
                else if(!row && homeRows!=null)homeRows.post(CatalogActivity.this::loadVisibleRowPosters);
            }
            if (!config.isPhoneMode()) updateTvCardAppearance(convertView);
            return convertView;
        }
    }

    private static final class Holder {
        final ImageView poster; final TextView title; final TextView meta;
        int position = -1;
        CatalogItem item;
        boolean row;
        boolean posterRequested;
        boolean hovered;
        boolean highlighted;
        Holder(ImageView poster, TextView title, TextView meta) { this.poster = poster; this.title = title; this.meta = meta; }
    }
}
