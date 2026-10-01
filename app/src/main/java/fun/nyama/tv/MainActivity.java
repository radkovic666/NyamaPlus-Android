package fun.nyama.tv;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;


import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@OptIn(markerClass = UnstableApi.class)
public final class MainActivity extends Activity implements ChannelBrowserOverlay.Listener, EpgGuideOverlay.Listener {
    public static final String EXTRA_EMBEDDED_IN_NYAMA_PLUS = "nyama_plus_embedded";
    private static WeakReference<MainActivity> activeInstance = new WeakReference<>(null);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> refreshTask;
    // A new MainActivity instance represents a fresh app start. The cached playlist may
    // be used immediately for fast startup, but exactly one forced network refresh is
    // requested before this instance settles into the normal 30-minute refresh cycle.
    private boolean startupPlaylistRefreshPending = true;

    private PlayerView playerView;
    private ExoPlayer player;
    private DefaultHttpDataSource.Factory httpFactory;

    private Config config;
    private int accent;
    private String uiSignatureAtCreate;
    private List<Channel> channels = Collections.emptyList();
    private int currentIndex = -1;
    private long playGeneration = 0;

    private LinearLayout hud;
    private ImageView hudLogo;
    private TextView hudNumber;
    private TextView hudName;
    private TextView hudGroup;
    private TextView hudNow;
    private TextView hudNext;
    private ProgressBar hudProgress;
    private TextView status;
    private ProgressBar buffering;
    private OutlineTextView cornerClock;
    private boolean compactUi;
    private LinearLayout programmeInfo;
    private TextView programmeInfoTitle;
    private TextView programmeInfoTime;
    private TextView programmeInfoDescription;
    private ChannelBrowserOverlay browser;
    private EpgGuideOverlay epgGuide;
    private LinearLayout exitPrompt;
    private boolean exitArmed = false;
    private boolean backOverlayConsumed = false;
    private boolean suppressNextUserLeaveHint = false;
    private static final long EXIT_CONFIRM_WINDOW_MS = 2000L;

    private final StringBuilder numberBuffer = new StringBuilder();
    private final Runnable clearNumber = this::commitNumber;
    private final Runnable hideHud = () -> hud.setVisibility(View.GONE);
    private final Runnable hideStatus = () -> status.setVisibility(View.GONE);
    private final Runnable hideProgrammeInfo = () -> programmeInfo.setVisibility(View.GONE);
    private final Runnable disarmExit = this::cancelExitPrompt;
    private final Runnable usageCheckpoint = new Runnable() {
        @Override public void run() {
            DataUsageTracker.checkpoint(MainActivity.this);
            main.postDelayed(this, 60_000L);
        }
    };

    private final Runnable updateClock = new Runnable() {
        @Override public void run() {
            refreshCornerClock();
            main.postDelayed(this, 30_000L);
        }
    };

    private boolean upHeld = false;
    private boolean upLongTriggered = false;
    private final Runnable longUpAction = () -> {
        // Favorite is intentionally available only from clean full-screen playback.
        // Channel/category menus and other overlays must never repurpose long-UP.
        if (!upHeld || !isPlainPlaybackView()) return;
        upLongTriggered = true;
        toggleCurrentFavorite();
    };

    private boolean okHeld = false;
    private boolean okLongTriggered = false;
    private final Runnable longOkAction = () -> {
        if (!okHeld) return;
        okLongTriggered = true;
        showCurrentChannelEpg();
    };

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        activeInstance = new WeakReference<>(this);
        config = new Config(this);
        AutoStartManager.cancelDelayedLaunches(this);
        if (!config.isPhoneMode()) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        compactUi = config.isPhoneMode();
        accent = UiTheme.accent(this);
        uiSignatureAtCreate = config.uiSignature();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        buildUi();
        initPlayer();
    }

    @Override protected void onResume() {
        super.onResume();
        suppressNextUserLeaveHint = false;
        immersive();

        if (!uiSignatureAtCreate.equals(config.uiSignature())) {
            recreate();
            return;
        }

        if (!config.isConfigured()) {
            suppressUserLeaveHintOnce();
            startActivity(new Intent(this, AdminSettingsActivity.class));
            return;
        }
        boolean forceStartupPlaylistRefresh = startupPlaylistRefreshPending;
        startupPlaylistRefreshPending = false;
        loadAndRefresh(forceStartupPlaylistRefresh);
        startForegroundRefreshLoop();
        main.removeCallbacks(updateClock);
        main.post(updateClock);
        main.removeCallbacks(usageCheckpoint);
        main.post(usageCheckpoint);
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Android does not deliver HOME as a normal KeyEvent. onUserLeaveHint() is
        // the lifecycle signal used when the user deliberately leaves the app (for
        // example HOME / launcher). Stop playback and remove the task immediately so
        // TV audio can never continue in the background. Internal navigation to our
        // own Settings/Admin screens is explicitly exempted.
        if (suppressNextUserLeaveHint) {
            suppressNextUserLeaveHint = false;
            return;
        }
        gracefulExit();
    }

    @Override protected void onPause() {
        super.onPause();
        if (refreshTask != null) refreshTask.cancel(false);
        refreshTask = null;
        main.removeCallbacks(updateClock);
        main.removeCallbacks(usageCheckpoint);
        DataUsageTracker.checkpoint(this);
        main.removeCallbacks(longOkAction);
        main.removeCallbacks(longUpAction);
        cancelExitPrompt();
        okHeld = false;
        upHeld = false;
    }

    @Override protected void onDestroy() {
        MainActivity active = activeInstance.get();
        if (active == this) activeInstance.clear();
        super.onDestroy();
        if (player != null) player.release();
        io.shutdownNow();
        scheduler.shutdownNow();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setKeepScreenOn(true);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        buffering = new ProgressBar(this);
        buffering.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER);
        root.addView(buffering, bp);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(UiTheme.sp(this, 18));
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), dp(12), dp(24), dp(12));
        status.setBackgroundColor(0xC9191C21);
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, dp(56), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp.topMargin = dp(36);
        root.addView(status, sp);

        hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.HORIZONTAL);
        hud.setGravity(Gravity.CENTER_VERTICAL);
        hud.setPadding(dp(compactUi ? 14 : 34), dp(compactUi ? 8 : 18), dp(compactUi ? 16 : 40), dp(compactUi ? 8 : 18));
        hud.setBackgroundColor(0xD8101216);
        hud.setVisibility(View.GONE);

        hudNumber = new TextView(this);
        hudNumber.setTextColor(accent);
        hudNumber.setTextSize(UiTheme.sp(this, compactUi ? 22 : 28));
        hudNumber.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hudNumber.setGravity(Gravity.CENTER);
        hud.addView(hudNumber, new LinearLayout.LayoutParams(dp(compactUi ? 58 : 92), -1));

        hudLogo = new ImageView(this);
        hudLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(compactUi ? 86 : 128), dp(compactUi ? 64 : 92));
        logoParams.setMargins(0, 0, dp(compactUi ? 12 : 24), 0);
        hud.addView(hudLogo, logoParams);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        hudName = hudText(compactUi ? 20 : 25, Color.WHITE, true);
        hudGroup = hudText(compactUi ? 12 : 14, 0xFFC0C5CD, false);
        hudNow = hudText(compactUi ? 15 : 18, Color.WHITE, true);
        hudNext = hudText(compactUi ? 13 : 15, 0xFFC7CBD2, false);
        hudProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        hudProgress.setMax(1000);
        UiTheme.tintProgress(this, hudProgress);
        copy.addView(hudName);
        copy.addView(hudGroup);
        copy.addView(hudNow);
        copy.addView(hudProgress, new LinearLayout.LayoutParams(-1, dp(7)));
        copy.addView(hudNext);
        hud.addView(copy, new LinearLayout.LayoutParams(0, -1, 1f));

        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, dp(compactUi ? 132 : 186), Gravity.BOTTOM);
        root.addView(hud, hp);

        programmeInfo = new LinearLayout(this);
        programmeInfo.setOrientation(LinearLayout.VERTICAL);
        programmeInfo.setPadding(dp(compactUi ? 20 : 42), dp(compactUi ? 12 : 24), dp(compactUi ? 20 : 42), dp(compactUi ? 14 : 28));
        programmeInfo.setBackgroundColor(0xD90D1015);
        programmeInfo.setVisibility(View.GONE);
        TextView infoLabel = hudText(13, accent, true);
        infoLabel.setText(getString(R.string.program_information));
        programmeInfoTitle = hudText(compactUi ? 19 : 24, Color.WHITE, true);
        programmeInfoTime = hudText(compactUi ? 13 : 15, 0xFFC5CAD2, false);
        programmeInfoDescription = new TextView(this);
        programmeInfoDescription.setTextColor(0xFFE1E4E8);
        programmeInfoDescription.setTextSize(UiTheme.sp(this, compactUi ? 14 : 17));
        programmeInfoDescription.setMaxLines(compactUi ? 3 : 5);
        programmeInfoDescription.setEllipsize(android.text.TextUtils.TruncateAt.END);
        programmeInfo.addView(infoLabel);
        programmeInfo.addView(programmeInfoTitle);
        programmeInfo.addView(programmeInfoTime);
        programmeInfo.addView(programmeInfoDescription, new LinearLayout.LayoutParams(-1, 0, 1f));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, dp(compactUi ? 180 : 250), Gravity.BOTTOM);
        root.addView(programmeInfo, ip);

        browser = new ChannelBrowserOverlay(this, this);
        root.addView(browser, new FrameLayout.LayoutParams(-1, -1));

        epgGuide = new EpgGuideOverlay(this, this);
        root.addView(epgGuide, new FrameLayout.LayoutParams(-1, -1));

        cornerClock = new OutlineTextView(this);
        cornerClock.setTextColor(Color.WHITE);
        cornerClock.setTextSize(UiTheme.sp(this, 15) * config.clockScale());
        cornerClock.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        cornerClock.setGravity(Gravity.CENTER);
        cornerClock.setPadding(dp(10), dp(4), dp(10), dp(4));
        cornerClock.setBackgroundColor(Color.TRANSPARENT);
        cornerClock.setStrokeColor(Color.BLACK);
        cornerClock.setStrokeWidth(getResources().getDisplayMetrics().density * 1.6f);
        root.addView(cornerClock, clockLayoutParams());

        exitPrompt = new LinearLayout(this);
        exitPrompt.setOrientation(LinearLayout.VERTICAL);
        exitPrompt.setGravity(Gravity.CENTER);
        exitPrompt.setPadding(dp(compactUi ? 24 : 34), dp(compactUi ? 18 : 24), dp(compactUi ? 24 : 34), dp(compactUi ? 18 : 24));
        exitPrompt.setBackgroundColor(0xE61A1D22);
        exitPrompt.setVisibility(View.GONE);

        TextView exitTitle = new TextView(this);
        exitTitle.setText(getString(R.string.exit_question));
        exitTitle.setTextColor(Color.WHITE);
        exitTitle.setTextSize(UiTheme.sp(this, compactUi ? 20 : 24));
        exitTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        exitTitle.setGravity(Gravity.CENTER);
        exitPrompt.addView(exitTitle, new LinearLayout.LayoutParams(-1, -2));

        TextView exitHint = new TextView(this);
        exitHint.setText(getString(R.string.exit_press_back_again));
        exitHint.setTextColor(0xFFD0D4DA);
        exitHint.setTextSize(UiTheme.sp(this, compactUi ? 14 : 16));
        exitHint.setGravity(Gravity.CENTER);
        exitHint.setPadding(0, dp(8), 0, 0);
        exitPrompt.addView(exitHint, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(
                compactUi ? dp(300) : dp(430), -2, Gravity.CENTER);
        root.addView(exitPrompt, xp);

        // Nyama+ navigation now lives inside the normal channel browser.
        // There is intentionally no floating NY+ handle or separate TV navigation overlay.

        setContentView(root);
        if (config.isPhoneMode()) installPhoneTouchControls();
        refreshCornerClock();
    }

    private TextView hudText(int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setTextSize(UiTheme.sp(this, size));
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void initPlayer() {
        httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(DeviceIdentity.userAgent(this))
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true);
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(this, httpFactory);
        // Keep Media3 lightweight. If the preferred hardware decoder fails to
        // initialize, allow Media3 to try another decoder exposed by the device.
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data))
                .build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int playbackState) {
                buffering.setVisibility(playbackState == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            }

            @Override public void onPlayerError(PlaybackException error) {
                long generation = playGeneration;
                buffering.setVisibility(View.GONE);
                showStatus(getString(R.string.stream_retrying), 3500);
                main.postDelayed(() -> {
                    if (generation == playGeneration
                            && currentIndex >= 0 && currentIndex < channels.size()) {
                        playChannel(currentIndex, false);
                    }
                }, 4000);
            }
        });
    }

    private void loadAndRefresh(boolean forcePlaylistRefresh) {
        io.execute(() -> {
            List<Channel> cached = Collections.emptyList();
            try { cached = PlaylistRepository.loadCached(this); } catch (Exception ignored) {}
            if (!cached.isEmpty()) {
                List<Channel> finalCached = cached;
                main.post(() -> applyChannels(finalCached, false));
            }

            boolean epgParseFailed = false;
            if (EpgRepository.cacheFile(this).exists()) {
                try { EpgRepository.parseRelevant(this); }
                catch (Exception ignored) { epgParseFailed = true; }
            }
            main.post(() -> browser.refreshEpg());

            long age = System.currentTimeMillis() - config.playlistLastRefresh();
            // Always refresh once on a fresh app start so additions/removals on the
            // server are visible immediately. The cached list above remains a safe
            // fallback if the network request fails. Subsequent foreground resumes
            // keep the normal 30-minute freshness rule.
            if (forcePlaylistRefresh || cached.isEmpty() || config.playlistLastRefresh() == 0L
                    || age >= Config.PLAYLIST_REFRESH_MS) {
                refreshPlaylistNow();
            }

            // The XMLTV file is a persistent cache. Do not download it on every app start.
            // Refresh only when missing, unreadable, explicitly changed, or 24 hours old.
            long epgAge = System.currentTimeMillis() - config.epgLastRefresh();
            boolean epgMissing = !EpgRepository.cacheFile(this).exists();
            if (epgMissing || epgParseFailed || config.epgLastRefresh() == 0L || epgAge >= Config.EPG_REFRESH_MS) {
                refreshEpgNow();
            }
        });
    }

    private void startForegroundRefreshLoop() {
        if (refreshTask != null) refreshTask.cancel(false);
        refreshTask = scheduler.scheduleAtFixedRate(this::refreshPlaylistNow,
                Config.PLAYLIST_REFRESH_MS, Config.PLAYLIST_REFRESH_MS, TimeUnit.MILLISECONDS);
    }

    private void refreshPlaylistNow() {
        io.execute(() -> {
            try {
                List<Channel> fresh = PlaylistRepository.refresh(this);
                main.post(() -> applyChannels(fresh, true));
            } catch (Exception e) {
                main.post(() -> showStatus(getString(R.string.playlist_refresh_failed), 3500));
            }
        });
    }

    private void refreshEpgNow() {
        io.execute(() -> {
            try {
                EpgRepository.refresh(this);
                main.post(() -> {
                    browser.refreshEpg();
                    showCurrentInfoBriefly();
                });
            } catch (Exception e) {
                main.post(() -> showStatus(getString(R.string.epg_refresh_failed), 3500));
            }
        });
    }

    private void applyChannels(List<Channel> fresh, boolean fromRefresh) {
        if (fresh == null || fresh.isEmpty()) return;
        String oldKey = currentIndex >= 0 && currentIndex < channels.size() ? channels.get(currentIndex).stableKey() : config.lastChannelKey();
        String oldUrl = currentIndex >= 0 && currentIndex < channels.size() ? channels.get(currentIndex).url : "";
        channels = new ArrayList<>(fresh);

        int newIndex = findByKey(oldKey);
        if (newIndex < 0) newIndex = 0;
        Channel newChannel = channels.get(newIndex);
        boolean sourceChanged = fromRefresh && currentIndex >= 0 && !oldUrl.isEmpty() && !oldUrl.equals(newChannel.url);
        currentIndex = newIndex;

        if (player.getMediaItemCount() == 0 || sourceChanged) {
            playChannel(currentIndex, fromRefresh);
        } else if (fromRefresh) {
            showStatus(getString(R.string.playlist_updated, channels.size()), 2200);
        }
    }

    private int findByKey(String key) {
        if (key == null || key.isEmpty()) return -1;
        for (int i = 0; i < channels.size(); i++) if (key.equals(channels.get(i).stableKey())) return i;
        return -1;
    }

    private void playChannel(int index, boolean refreshed) {
        cancelExitPrompt();
        if (channels.isEmpty()) return;
        if (index < 0) index = channels.size() - 1;
        if (index >= channels.size()) index = 0;
        currentIndex = index;
        Channel channel = channels.get(currentIndex);
        config.setLastChannelKey(channel.stableKey());
        playGeneration++;

        Map<String, String> headers = new HashMap<>(channel.streamHeaders);
        // Per-device User-Agent keeps the original auth_playlist.php device binding unique.
        headers.put("User-Agent", DeviceIdentity.userAgent(this));
        httpFactory.setDefaultRequestProperties(headers);

        player.setMediaItem(MediaItem.fromUri(channel.url));
        player.prepare();
        player.setPlayWhenReady(true);

        showChannelOverlay(channel, refreshed ? getString(R.string.playlist_refreshed) : null);
    }

    private void channelNext() { if (!channels.isEmpty()) playChannel(currentIndex + 1, false); }
    private void channelPrevious() { if (!channels.isEmpty()) playChannel(currentIndex - 1, false); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean back = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE;
        if (back) {
            if (backOverlayConsumed) {
                if (event.getAction() == KeyEvent.ACTION_UP) backOverlayConsumed = false;
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && hideVisibleOverlayForBack()) {
                backOverlayConsumed = true;
                return true;
            }
        }

        if (epgGuide != null && epgGuide.isOpen() && epgGuide.handleKey(event)) return true;
        if (browser != null && browser.isOpen() && browser.handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (exitArmed && keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ESCAPE) {
            cancelExitPrompt();
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showChannelBrowser(false); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (event.getRepeatCount() == 0) {
                    upHeld = true;
                    upLongTriggered = false;
                    main.removeCallbacks(longUpAction);
                    main.postDelayed(longUpAction, 650L);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                channelPrevious(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                channelNext(); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                channelNext(); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                channelPrevious(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (event.getRepeatCount() == 0) {
                    okHeld = true;
                    okLongTriggered = false;
                    main.removeCallbacks(longOkAction);
                    main.postDelayed(longOkAction, 650L);
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                // Open the main opaque TV menu focused on the app sidebar.
                showChannelBrowser(true, true); return true;
            case KeyEvent.KEYCODE_INFO:
                showCurrentInfoBriefly(); return true;
            case KeyEvent.KEYCODE_GUIDE:
                showCurrentChannelEpg(); return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (hideVisibleOverlayForBack()) return true;
                showChannelBrowser(true, true); return true;
            case KeyEvent.KEYCODE_0: case KeyEvent.KEYCODE_1: case KeyEvent.KEYCODE_2: case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4: case KeyEvent.KEYCODE_5: case KeyEvent.KEYCODE_6: case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8: case KeyEvent.KEYCODE_9:
                numberPressed(keyCode - KeyEvent.KEYCODE_0); return true;
            default:
                // Hardware VOLUME_UP / VOLUME_DOWN remain under Android's normal system control.
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            main.removeCallbacks(longUpAction);
            boolean wasHeld = upHeld;
            upHeld = false;
            if (wasHeld && !upLongTriggered) showCurrentProgrammeDescription();
            upLongTriggered = false;
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            main.removeCallbacks(longOkAction);
            boolean wasHeld = okHeld;
            okHeld = false;
            if (wasHeld && !okLongTriggered) showChannelBrowser(false);
            okLongTriggered = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        // Android gesture/navigation BACK may arrive here instead of as a KeyEvent.
        // While watching TV, BACK should reveal the opaque main menu instead of an exit prompt.
        if (hideVisibleOverlayForBack()) return;
        showChannelBrowser(true, true);
    }

    private boolean hideVisibleOverlayForBack() {
        boolean hidden = false;
        main.removeCallbacks(hideHud);
        main.removeCallbacks(hideProgrammeInfo);
        main.removeCallbacks(hideStatus);

        if (epgGuide != null && epgGuide.isOpen()) { epgGuide.hide(); hidden = true; }
        if (browser != null && browser.isOpen()) { browser.hide(); hidden = true; }
        if (programmeInfo != null && programmeInfo.getVisibility() == View.VISIBLE) {
            programmeInfo.setVisibility(View.GONE);
            hidden = true;
        }
        if (hud != null && hud.getVisibility() == View.VISIBLE) {
            hud.setVisibility(View.GONE);
            hidden = true;
        }
        if (status != null && status.getVisibility() == View.VISIBLE) {
            status.setVisibility(View.GONE);
            hidden = true;
        }
        if (hidden) {
            numberBuffer.setLength(0);
            main.removeCallbacks(clearNumber);
            cancelExitPrompt();
            refreshCornerClock();
        }
        return hidden;
    }

    private void requestExitConfirmation() {
        if (exitArmed) {
            gracefulExit();
            return;
        }
        exitArmed = true;
        if (exitPrompt != null) exitPrompt.setVisibility(View.VISIBLE);
        main.removeCallbacks(disarmExit);
        main.postDelayed(disarmExit, EXIT_CONFIRM_WINDOW_MS);
    }

    private void cancelExitPrompt() {
        exitArmed = false;
        main.removeCallbacks(disarmExit);
        if (exitPrompt != null) exitPrompt.setVisibility(View.GONE);
    }

    public static void stopPlaybackAndExitIfRunning() {
        MainActivity activity = activeInstance.get();
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(activity::gracefulExit);
    }

    /** Pause live TV only while an on-demand movie/episode is actually playing. */
    public static void pausePlaybackForOnDemand() {
        MainActivity activity = activeInstance.get();
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            if (activity.player != null) activity.player.setPlayWhenReady(false);
        });
    }

    /** Resume the still-loaded live channel after leaving the on-demand player. */
    public static void resumePlaybackAfterOnDemand() {
        MainActivity activity = activeInstance.get();
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            if (activity.player != null && activity.player.getMediaItemCount() > 0) {
                activity.player.setPlayWhenReady(true);
            }
        });
    }

    private void gracefulExit() {
        exitArmed = false;
        main.removeCallbacks(disarmExit);
        if (exitPrompt != null) exitPrompt.setVisibility(View.GONE);

        if (refreshTask != null) refreshTask.cancel(false);
        refreshTask = null;
        main.removeCallbacks(updateClock);
        main.removeCallbacks(usageCheckpoint);
        main.removeCallbacks(hideHud);
        main.removeCallbacks(hideProgrammeInfo);
        main.removeCallbacks(clearNumber);
        DataUsageTracker.checkpoint(this);

        // Stop audio/video before removing the task so no channel audio can continue
        // after the user has explicitly exited Nyama TV.
        if (player != null) {
            player.setPlayWhenReady(false);
            player.stop();
            player.clearMediaItems();
        }
        if (getIntent().getBooleanExtra(EXTRA_EMBEDDED_IN_NYAMA_PLUS, false)) {
            finish();
        } else {
            finishAndRemoveTask();
        }
    }

    private boolean isPlainPlaybackView() {
        return (browser == null || !browser.isOpen())
                && (epgGuide == null || !epgGuide.isOpen())
                && (programmeInfo == null || programmeInfo.getVisibility() != View.VISIBLE)
                && (hud == null || hud.getVisibility() != View.VISIBLE)
                && (status == null || status.getVisibility() != View.VISIBLE)
                && (exitPrompt == null || exitPrompt.getVisibility() != View.VISIBLE);
    }

    private void toggleCurrentFavorite() {
        if (currentIndex < 0 || currentIndex >= channels.size()) return;
        Channel channel = channels.get(currentIndex);
        boolean favorite = config.toggleFavorite(channel.stableKey());
        showStatus(getString(favorite ? R.string.added_favorite : R.string.removed_favorite, channel.name), 2200);
    }

    private void installPhoneTouchControls() {
        final GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                showChannelBrowser(false);
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                showCurrentChannelEpg();
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > dp(80)) {
                    if (dx < 0) channelNext(); else channelPrevious();
                    return true;
                }
                if (Math.abs(dy) > Math.abs(dx) && dy < -dp(80)) {
                    showCurrentProgrammeDescription();
                    return true;
                }
                return false;
            }
        });
        View.OnTouchListener touch = (v, event) -> detector.onTouchEvent(event);
        playerView.setClickable(true);
        playerView.setOnTouchListener(touch);
    }

    private void numberPressed(int digit) {
        numberBuffer.append(digit);
        main.removeCallbacks(clearNumber);
        showStatus(getString(R.string.channel_number, numberBuffer.toString()), 1800);
        main.postDelayed(clearNumber, 1200);
    }

    private void commitNumber() {
        if (numberBuffer.length() == 0) return;
        try {
            int channelNumber = Integer.parseInt(numberBuffer.toString());
            if (channelNumber >= 1 && channelNumber <= channels.size()) playChannel(channelNumber - 1, false);
            else showStatus(getString(R.string.channel_not_found, channelNumber), 1800);
        } catch (NumberFormatException ignored) {}
        numberBuffer.setLength(0);
    }

    private void showChannelBrowser(boolean focusCategories) {
        showChannelBrowser(focusCategories, false);
    }

    private void showChannelBrowser(boolean focusCategories, boolean focusSidebar) {
        cancelExitPrompt();
        if (channels.isEmpty()) {
            showStatus(getString(R.string.no_channels_loaded), 2000);
            return;
        }
        main.removeCallbacks(hideHud);
        main.removeCallbacks(hideProgrammeInfo);
        hud.setVisibility(View.GONE);
        programmeInfo.setVisibility(View.GONE);
        browser.show(channels, currentIndex, focusCategories, focusSidebar);
        refreshCornerClock();
    }

    private void showCurrentChannelEpg() {
        cancelExitPrompt();
        if (currentIndex < 0 || currentIndex >= channels.size()) return;
        main.removeCallbacks(hideHud);
        main.removeCallbacks(hideProgrammeInfo);
        hud.setVisibility(View.GONE);
        programmeInfo.setVisibility(View.GONE);
        epgGuide.show(channels.get(currentIndex));
        refreshCornerClock();
    }

    private void showCurrentProgrammeDescription() {
        if (currentIndex < 0 || currentIndex >= channels.size()) return;
        Channel channel = channels.get(currentIndex);
        EpgEvent now = EpgRepository.current(channel);
        programmeInfoTitle.setText(now == null || now.title.isEmpty() ? channel.name : now.title);
        if (now != null) {
            programmeInfoTime.setText(channel.name + "   " + time(now.startMs) + " - " + time(now.stopMs));
            programmeInfoDescription.setText(now.description.isEmpty() ? getString(R.string.no_description) : now.description);
        } else {
            programmeInfoTime.setText(channel.name);
            programmeInfoDescription.setText(getString(R.string.no_epg));
        }
        hud.setVisibility(View.GONE);
        programmeInfo.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideProgrammeInfo);
        main.postDelayed(hideProgrammeInfo, 8000L);
    }

    private void stopPlaybackForNavigation() {
        if (player == null) return;
        player.setPlayWhenReady(false);
        player.stop();
        player.clearMediaItems();
    }

    private void openCatalogFromTv(String section) {
        // Keep live TV playing under translucent Movies/Series screens.
        suppressUserLeaveHintOnce();
        startActivity(new Intent(this, CatalogActivity.class)
                .putExtra(CatalogActivity.EXTRA_SECTION, "series".equals(section) ? "series" : "movies"));
    }

    private void suppressUserLeaveHintOnce() {
        suppressNextUserLeaveHint = true;
    }

    private void openSettings() {
        suppressUserLeaveHintOnce();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override public void onPlayChannel(int sourceIndex) { playChannel(sourceIndex, false); }
    @Override public void onRequestMovies() { openCatalogFromTv("movies"); }
    @Override public void onRequestSeries() { openCatalogFromTv("series"); }
    @Override public void onRequestSettings() { openSettings(); }
    @Override public void onClosed() { immersive(); refreshCornerClock(); }
    @Override public void onFavoriteChanged(Channel channel, boolean favorite) {
        showStatus(getString(favorite ? R.string.added_favorite : R.string.removed_favorite, channel.name), 2200);
    }

    private void showCurrentInfoBriefly() {
        if (currentIndex >= 0 && currentIndex < channels.size()) showChannelOverlay(channels.get(currentIndex), null);
    }

    private void showChannelOverlay(Channel channel, String suffix) {
        EpgEvent now = EpgRepository.current(channel);
        EpgEvent next = EpgRepository.next(channel);
        hudNumber.setText(String.valueOf(channel.number));
        hudName.setText(channel.name);
        String group = channel.group.isEmpty() ? getString(R.string.other) : channel.group;
        if (config.isFavorite(channel.stableKey())) group += "   ★ " + getString(R.string.favorite);
        if (suffix != null) group += "   " + suffix;
        hudGroup.setText(group);
        LogoRepository.load(this, channel.logo, hudLogo);

        if (now != null) {
            hudNow.setText(getString(R.string.now) + "  " + time(now.startMs) + "  " + now.title);
            long duration = Math.max(1L, now.stopMs - now.startMs);
            long elapsed = Math.max(0L, Math.min(duration, System.currentTimeMillis() - now.startMs));
            hudProgress.setProgress((int) (elapsed * 1000L / duration));
        } else {
            hudNow.setText(getString(R.string.now) + "  " + getString(R.string.no_epg));
            hudProgress.setProgress(0);
        }
        if (next != null) hudNext.setText(getString(R.string.next) + "  " + time(next.startMs) + "  " + next.title);
        else hudNext.setText(getString(R.string.next) + "  " + getString(R.string.no_upcoming));

        programmeInfo.setVisibility(View.GONE);
        hud.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideHud);
        main.postDelayed(hideHud, 5000);
    }

    private String time(long ms) { return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms)); }

    private void refreshCornerClock() {
        if (cornerClock == null) return;
        boolean overlayOpen = (browser != null && browser.isOpen()) || (epgGuide != null && epgGuide.isOpen());
        if (!config.showClock() || overlayOpen) {
            cornerClock.setVisibility(View.GONE);
            return;
        }
        cornerClock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        cornerClock.setVisibility(View.VISIBLE);
    }

    private FrameLayout.LayoutParams clockLayoutParams() {
        float scale = config.clockScale() * config.textScale();
        int width = dp(Math.round(82f * Math.max(0.85f, scale)));
        int height = dp(Math.round(38f * Math.max(0.85f, scale)));
        String position = config.clockPosition();
        int gravity;
        if (Config.CLOCK_TOP_RIGHT.equals(position)) gravity = Gravity.TOP | Gravity.END;
        else if (Config.CLOCK_BOTTOM_LEFT.equals(position)) gravity = Gravity.BOTTOM | Gravity.START;
        else if (Config.CLOCK_BOTTOM_RIGHT.equals(position)) gravity = Gravity.BOTTOM | Gravity.END;
        else gravity = Gravity.TOP | Gravity.START;

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, gravity);
        int horizontal = dp(18);
        int vertical = dp(16);
        if ((gravity & Gravity.END) == Gravity.END) params.rightMargin = horizontal; else params.leftMargin = horizontal;
        if ((gravity & Gravity.BOTTOM) == Gravity.BOTTOM) params.bottomMargin = vertical; else params.topMargin = vertical;
        return params;
    }

    private void showStatus(String message, long hideAfterMs) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideStatus);
        main.postDelayed(hideStatus, hideAfterMs);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
