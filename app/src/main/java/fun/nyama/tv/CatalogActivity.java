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
    private final ArrayList<String> selectedGenres = new ArrayList<>();
    private List<String> availableGenres = new ArrayList<>();
    private int restorePosition = -1;
    private String restoredQuery = "";
    private boolean firstResume = true;
    private boolean firstGridFocus = true;
    private AppSidebar sidebar;
    private boolean sidebarExpanded;
    private int lastContentFocus;
    private int catalogColumns = 5;
    private ValueAnimator sidebarAnimator;

    // Code 13 TV catalogue state. Raw JSON is cached separately from poster validation so
    // search never needs to refetch the catalogue and the first usable grid is not blocked by
    // a full-catalogue poster sweep. Mobile behavior intentionally remains unchanged.
    private final ArrayList<CatalogItem> tvRawCatalog = new ArrayList<>();
    private final ArrayList<CatalogItem> tvVisibleCatalog = new ArrayList<>();
    private final ArrayList<CatalogItem> tvInitialVisiblePage = new ArrayList<>();
    private boolean tvCatalogReady;
    private int tvWarmGeneration = -1;
    private int tvDataGeneration;
    private int tvSearchGeneration;
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

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = new Config(this);
        favorites = new MovieFavorites(this);
        section = "series".equals(getIntent().getStringExtra(EXTRA_SECTION)) ? "series" : "movies";
        if (state != null) restoreState(state);
        else restorePersistentState();
        setContentView(buildUi());
        loadGenres();
        loadPage();
    }

    @Override protected void onResume() {
        super.onResume();
        if (firstResume) { firstResume = false; return; }
        if (favoritesOnly) loadPage();
    }

    @Override protected void onPause() {
        savePersistentState();
        super.onPause();
    }

    @Override protected void onDestroy() {
        requestGeneration.incrementAndGet();
        main.removeCallbacks(liveSearch);
        io.shutdownNow();
        if (sidebarAnimator != null) sidebarAnimator.cancel();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
        out.putString("query", search == null ? "" : search.getText().toString());
        out.putString("sort", sortMode);
        out.putBoolean("favorites", favoritesOnly);
        out.putStringArrayList("genres", new ArrayList<>(selectedGenres));
        if (grid != null) { int p = grid.getSelectedItemPosition(); out.putInt("grid_pos", p >= 0 ? p : grid.getFirstVisiblePosition()); }
    }

    private void restoreState(Bundle state) {
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

        sidebar = new AppSidebar(this,
                "series".equals(section) ? AppSidebar.SECTION_SERIES : AppSidebar.SECTION_MOVIES,
                new AppSidebar.Listener() {
                    @Override public void onTv() { returnToTv(); }
                    @Override public void onMovies() { openSection("movies"); }
                    @Override public void onSeries() { openSection("series"); }
                    @Override public void onSettings() { savePersistentState(); startActivity(new Intent(CatalogActivity.this, SettingsActivity.class)); finish(); }
                });
        sidebarExpanded = phone;
        if (!phone) sidebar.setExpanded(false, false);
        shell.addView(sidebar, new LinearLayout.LayoutParams(dp(phone ? 112 : SIDEBAR_COLLAPSED_DP), -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xD90A0C0F);
        root.setPadding(dp(phone ? 10 : 16), dp(phone ? 8 : 10), dp(phone ? 10 : 16), dp(8));
        shell.addView(root, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout header = horizontal();
        TextView title = text(section.equals("series") ? getString(R.string.home_series) : getString(R.string.home_movies),
                phone ? 24 : 31, Color.WHITE, true);
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
        search.setHintTextColor(0xFF707A86);
        search.setTextSize(UiTheme.sp(this, phone ? 15 : 16));
        // TV reserves room for the clear-X which is visually inside the search field.
        search.setPadding(dp(14), 0, dp(phone ? 14 : 52), 0);
        search.setBackground(panelBackground(0xDD151A20, 0xFF2A3139, 1));
        search.setOnFocusChangeListener((v, focused) -> {
            search.setBackground(panelBackground(0xDD151A20, focused ? accent : 0xFF2A3139, focused ? 2 : 1));
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
                if (searchClearButton != null) searchClearButton.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                main.removeCallbacks(liveSearch);
                main.postDelayed(liveSearch, phone ? 320L : 120L);
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
            searchClearButton.setVisibility(search.length() == 0 ? View.GONE : View.VISIBLE);
            searchClearButton.setOnClickListener(v -> {
                search.setText("");
                main.removeCallbacks(liveSearch);
                page = 1;
                restorePosition = 0;
                search.requestFocus();
                if (phone) loadPage(); else handleSearchChanged();
            });
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
        filterButton.setBackground(panelBackground(0xDD151A20, 0xFF2A3139, 1));
        filterButton.setColorFilter(Color.WHITE);
        filterButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        filterButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        filterButton.setFocusable(true);
        filterButton.setClickable(true);
        filterButton.setContentDescription(getString(R.string.filters));
        filterButton.setOnClickListener(v -> showFilters());
        filterButton.setOnFocusChangeListener((v, focused) -> {
            int fill = focused ? ((accent & 0x00FFFFFF) | 0x33000000) : 0xDD151A20;
            filterButton.setBackground(panelBackground(fill, focused ? accent : 0xFF2A3139, focused ? 2 : 1));
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
        status = text(getString(R.string.loading), 12, 0xFF9CA6B1, false);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, dp(26), 1f));
        loading = new ProgressBar(this);
        loading.setIndeterminate(true);
        statusRow.addView(loading, new LinearLayout.LayoutParams(dp(24), dp(24)));
        root.addView(statusRow, new LinearLayout.LayoutParams(-1, dp(28)));

        grid = new GridView(this);
        catalogColumns = phone ? calculateColumns(145, 2, 112) : calculateColumns(132, 4, SIDEBAR_COLLAPSED_DP);
        if (!phone) catalogColumns = Math.min(6, Math.max(4, catalogColumns));
        if (phone && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) catalogColumns = Math.max(catalogColumns, 3);
        grid.setNumColumns(catalogColumns);
        grid.setHorizontalSpacing(dp(phone ? 8 : 8));
        grid.setVerticalSpacing(dp(phone ? 9 : 8));
        grid.setPadding(dp(2), dp(4), dp(2), dp(6));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setSelector(new ColorDrawable((accent & 0x00FFFFFF) | 0x55000000));
        adapter = new CatalogAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnFocusChangeListener((v, focused) -> {
            if (focused && !phone) setCatalogSidebarExpanded(false, true);
        });
        grid.setOnItemClickListener((parent, view, position, id) -> openDetail(adapter.getItem(position)));
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private View previous;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
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
                if (previous != null) {
                    previous.animate().scaleX(1f).scaleY(1f).setDuration(110L).start();
                    if (android.os.Build.VERSION.SDK_INT >= 21) previous.setElevation(0f);
                }
                previous = null;
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout paging = horizontal();
        prev = smallButton(getString(R.string.previous_page));
        next = smallButton(getString(R.string.next_page));
        pageStatus = text("", 12, 0xFFC6CDD6, true);
        pageStatus.setGravity(Gravity.CENTER);
        prev.setOnClickListener(v -> changeCatalogPage(-1));
        next.setOnClickListener(v -> changeCatalogPage(1));
        paging.addView(prev, new LinearLayout.LayoutParams(dp(phone ? 88 : 112), dp(38)));
        paging.addView(pageStatus, new LinearLayout.LayoutParams(0, dp(38), 1f));
        paging.addView(next, new LinearLayout.LayoutParams(dp(phone ? 88 : 112), dp(38)));
        root.addView(paging, new LinearLayout.LayoutParams(-1, dp(phone ? 48 : 40)));

        if (!phone) {
            grid.post(() -> { if (grid.getCount() > 0) grid.requestFocus(); else search.requestFocus(); });
        }
        return shell;
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
        if (lastContentFocus == 1 && filterButton != null) filterButton.requestFocus();
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
            if (grid != null && grid.getCount() > 0) grid.requestFocus();
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
        for (int i = 0; i < sortLabels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(6100 + i);
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
                favoritesOnly = favoriteCheck.isChecked();
                int checked = sortGroup.getCheckedRadioButtonId() - 6100;
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
        // TV search is local. Never show the loading spinner and never refetch catalogue JSON
        // simply because the user typed another character.
        if (tvCatalogReady) {
            applyTvLocalPage(tvVisibleCatalog, currentQuery());
            return;
        }
        List<CatalogItem> rawSnapshot;
        synchronized (tvRawCatalog) { rawSnapshot = new ArrayList<>(tvRawCatalog); }
        if (!rawSnapshot.isEmpty()) {
            if (currentQuery().isEmpty()) return;
            List<CatalogItem> matches = PukankiApi.filterLocal(rawSnapshot, currentQuery());
            final int generation = ++tvSearchGeneration;
            io.execute(() -> {
                List<CatalogItem> visible = PukankiApi.validateTvPostersFast(this, matches);
                if (generation != tvSearchGeneration || isFinishing()) return;
                runOnUiThread(() -> applyTvLocalPage(visible, ""));
            });
            return;
        }
        // Raw catalogue is still warming: filter the stable first page rather than repeatedly
        // filtering an already-filtered adapter when the user types/backspaces.
        applyTvLocalPage(tvInitialVisiblePage.isEmpty() ? adapter.snapshot() : tvInitialVisiblePage, currentQuery());
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

    private void loadTvPage() {
        final List<String> genres = new ArrayList<>(selectedGenres);
        final String favIds = favorites.csv();
        StringBuilder keyBuilder = new StringBuilder(section).append('|').append(sortMode).append('|')
                .append(favoritesOnly).append('|').append(favIds);
        for (String g : genres) keyBuilder.append('|').append(g);
        String newDataKey = keyBuilder.toString();
        if (!newDataKey.equals(tvDataKey)) {
            tvDataKey = newDataKey;
            tvRawCatalog.clear();
            tvVisibleCatalog.clear();
            tvInitialVisiblePage.clear();
            tvCatalogReady = false;
            tvDataGeneration++;
        }
        final int dataGeneration = tvDataGeneration;

        List<CatalogItem> cached = PukankiApi.getCachedVisibleTvCatalog(this, section, genres, sortMode, favoritesOnly, favIds);
        if (cached != null && !cached.isEmpty()) {
            tvVisibleCatalog.clear();
            tvVisibleCatalog.addAll(cached);
            tvCatalogReady = true;
            loading.setVisibility(View.GONE);
            applyTvLocalPage(tvVisibleCatalog, currentQuery());
            warmTvRawOnly(genres, favIds, dataGeneration);
            return;
        }

        List<CatalogItem> rawCached = PukankiApi.getCachedRawTvCatalog(this, section, genres, sortMode, favoritesOnly, favIds);
        if (rawCached != null && !rawCached.isEmpty()) {
            synchronized (tvRawCatalog) { tvRawCatalog.clear(); tvRawCatalog.addAll(rawCached); }
        }

        tvCatalogReady = false;
        if (adapter.getCount() == 0) {
            loading.setVisibility(View.VISIBLE);
            status.setText(R.string.loading);
        } else {
            loading.setVisibility(View.GONE);
        }
        prev.setEnabled(false);
        next.setEnabled(false);

        // First paint validates only a small page instead of blocking on every poster in the catalogue.
        // If raw JSON is already cached, even this step avoids a catalogue network request.
        io.execute(() -> {
            try {
                CatalogPage first;
                if (rawCached != null && !rawCached.isEmpty()) {
                    int rawTotal = rawCached.size();
                    int rawPages = Math.max(1, (rawTotal + PAGE_SIZE - 1) / PAGE_SIZE);
                    int safePage = Math.max(1, Math.min(page, rawPages));
                    int start = Math.min(rawTotal, (safePage - 1) * PAGE_SIZE);
                    int end = Math.min(rawTotal, start + PAGE_SIZE);
                    List<CatalogItem> visible = PukankiApi.validateTvPostersFast(this,
                            new ArrayList<>(rawCached.subList(start, end)));
                    first = new CatalogPage(visible, rawTotal, safePage, rawPages);
                } else {
                    first = PukankiApi.fetchTvFastPage(this, section, page, PAGE_SIZE, genres, sortMode, favoritesOnly, favIds);
                }
                if (dataGeneration != tvDataGeneration || isFinishing()) return;
                runOnUiThread(() -> {
                    if (dataGeneration != tvDataGeneration) return;
                    tvInitialVisiblePage.clear();
                    tvInitialVisiblePage.addAll(first.items);
                    loading.setVisibility(View.GONE);
                    if (currentQuery().isEmpty()) applyPage(first);
                    else applyTvLocalPage(first.items, currentQuery());
                });
            } catch (Exception e) {
                if (dataGeneration != tvDataGeneration || isFinishing()) return;
                runOnUiThread(() -> { if (adapter.getCount() == 0) showLoadFailure(e); });
            }
        });
        warmTvCatalogue(genres, favIds, dataGeneration);
    }

    private void warmTvRawOnly(List<String> genres, String favIds, int generation) {
        if (!tvRawCatalog.isEmpty()) return;
        io.execute(() -> {
            try {
                List<CatalogItem> raw = PukankiApi.fetchTvRawCatalog(this, section, genres, sortMode, favoritesOnly, favIds);
                if (generation != tvDataGeneration) return;
                synchronized (tvRawCatalog) { tvRawCatalog.clear(); tvRawCatalog.addAll(raw); }
                main.post(liveSearch);
            } catch (Exception ignored) {}
        });
    }

    private void warmTvCatalogue(List<String> genres, String favIds, int generation) {
        if (tvWarmGeneration == generation) return;
        tvWarmGeneration = generation;
        io.execute(() -> {
            try {
                List<CatalogItem> raw = PukankiApi.fetchTvRawCatalog(this, section, genres, sortMode, favoritesOnly, favIds);
                if (generation != tvDataGeneration || isFinishing()) return;
                synchronized (tvRawCatalog) { tvRawCatalog.clear(); tvRawCatalog.addAll(raw); }

                main.post(liveSearch);

                List<CatalogItem> visible = "series".equals(section)
                        ? PukankiApi.validateTvSeriesPosters(this, raw)
                        : PukankiApi.validateTvPosters(this, raw);
                if (generation != tvDataGeneration || isFinishing()) return;
                PukankiApi.cacheVisibleTvCatalog(this, section, genres, sortMode, favoritesOnly, favIds, visible);
                runOnUiThread(() -> {
                    tvVisibleCatalog.clear();
                    tvVisibleCatalog.addAll(visible);
                    tvCatalogReady = true;
                    loading.setVisibility(View.GONE);
                    applyTvLocalPage(tvVisibleCatalog, currentQuery());
                });
            } catch (Exception ignored) {
                // Keep the already usable first page on screen. Poster/network enrichment must never
                // put the TV catalogue back into an indefinite Loading state.
            } finally {
                // Generation changes naturally supersede older warm-up work.
            }
        });
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
        status.setText(getString(R.string.catalog_failed, safeMessage(e)));
        prev.setEnabled(page > 1);
        next.setEnabled(page < totalPages);
    }

    private void applyPage(CatalogPage result) {
        page = Math.max(1, result.page);
        totalPages = Math.max(1, result.totalPages);
        total = result.total;
        adapter.setItems(result.items);
        loading.setVisibility(View.GONE);
        status.setText(getString(R.string.catalog_count, total));
        pageStatus.setText(getString(R.string.page_of, page, totalPages));
        prev.setEnabled(page > 1);
        next.setEnabled(page < totalPages);
        updateFilterIndicator();
        if (focusFirstAfterPageChange && !config.isPhoneMode() && !result.items.isEmpty()) {
            focusFirstAfterPageChange = false;
            restorePosition = -1;
            grid.setSelection(0);
            grid.post(() -> {
                grid.setSelection(0);
                grid.requestFocus();
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
        restorePosition = grid.getFirstVisiblePosition();
        Intent i = new Intent(this, TitleDetailActivity.class);
        i.putExtra("id", item.id); i.putExtra("type", section); i.putExtra("title", item.title);
        i.putExtra("originalTitle", item.originalTitle); i.putExtra("year", item.year); i.putExtra("endYear", item.endYear);
        i.putExtra("runtime", item.runtime); i.putExtra("genres", item.genres); i.putExtra("rating", item.rating);
        i.putExtra("votes", item.votes); i.putExtra("seasons", item.seasons); i.putExtra("episodeCount", item.episodeCount);
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
        bg.setCornerRadius(dp(10));
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

    private final class CatalogAdapter extends BaseAdapter {
        private final Context context;
        private final List<CatalogItem> items = new ArrayList<>();
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
                box.setPadding(dp(5), dp(5), dp(5), dp(7));
                box.setBackground(panelBackground(0xFF11161C, 0xFF242C35, 1));
                ImageView poster = new ImageView(context);
                poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
                poster.setBackgroundColor(0xFF11161C);
                // Phone keeps the 1.0.7 card. TV is deliberately poster + name + year only.
                box.addView(poster, new LinearLayout.LayoutParams(-1, dp(phone ? 188 : 174)));
                TextView title = text("", phone ? 13 : 14, Color.WHITE, true);
                title.setMaxLines(2);
                LinearLayout.LayoutParams t = new LinearLayout.LayoutParams(-1, dp(phone ? 44 : 42));
                t.setMargins(dp(2), dp(4), dp(2), 0);
                box.addView(title, t);
                TextView meta = text("", phone ? 11 : 12, 0xFF9CA6B1, false);
                meta.setGravity(Gravity.TOP);
                meta.setLineSpacing(0f, 1.05f);
                meta.setMaxLines(1);
                box.addView(meta, new LinearLayout.LayoutParams(-1, dp(phone ? 23 : 24)));
                box.setLayoutParams(new AbsListView.LayoutParams(-1, dp(phone ? 272 : 254)));
                h = new Holder(poster, title, meta); box.setTag(h); convertView = box;
            } else h = (Holder) convertView.getTag();
            CatalogItem item = getItem(position);
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
                PosterLoader.load(CatalogActivity.this, item.id, h.poster);
            }
            return convertView;
        }
    }

    private static final class Holder {
        final ImageView poster; final TextView title; final TextView meta;
        Holder(ImageView poster, TextView title, TextView meta) { this.poster = poster; this.title = title; this.meta = meta; }
    }
}
