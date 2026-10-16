package fun.nyama.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal Movies/Series player wrapper.
 *
 * TV uses a minimal fullscreen surface and documented exact-episode embeds. Phone routes,
 * controls, touch handling and session state retain their existing behavior.
 */
public final class MoviePlayerActivity extends Activity {
    private static final String TAG = "NyamaPlayer";
    private static final String PLAYER_HOST = "streamimdb.ru";
    private static final String PLAYER_BASE = "https://streamimdb.ru/embed/";
    private static final long BACK_CONFIRM_MS = 2500L;
    private static final long BACK_DUPLICATE_GUARD_MS = 250L;
    private static final long CONTROLS_HIDE_MS = 5000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Button> controlButtons = new ArrayList<>();
    private Config config;
    private boolean tvMode;
    private WebView webView;
    private FrameLayout root;
    private FrameLayout fullscreenContainer;
    private long lastAcceptedBackAt;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private LinearLayout remoteControls;
    private Button firstControl;
    private int controlIndex;
    private String id;
    private String type;
    private String title;
    private boolean closeArmed;
    private boolean resumeTvWhenClosing;
    private boolean providerMenuMode;
    private boolean returnToSeries;
    private boolean playbackFailed;
    private boolean leavingPlayback;
    private String loadedPlayerUrl = "";
    private String selectedPlaybackHost = PLAYER_HOST;
    private View playbackError;
    private View tvLoadingSurface;
    private boolean tvForeground;
    private boolean rendererRecoveryUsed;
    private boolean rendererRecoveryPending;
    private long tvSurfaceGeneration;
    private long tvDocumentLoadGeneration = -1;
    private Bitmap tvDefaultVideoPoster;
    private Runnable pendingTvDocumentLoad;
    private TvPlayerProgressBridge episodeProgress;
    private final Runnable clearCloseArm = () -> closeArmed = false;
    private final Runnable hideControls = () -> setControlsVisible(false, false);

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = new Config(this);
        if (!ActivationAccess.require(this)) return;
        tvMode = !config.isPhoneMode();
        id = safe(getIntent().getStringExtra("id"));
        type = "series".equals(getIntent().getStringExtra("type")) ? "series" : "movies";
        title = safe(getIntent().getStringExtra("title"));
        returnToSeries = tvMode && "series".equals(type) && getIntent().getBooleanExtra("return_to_series", false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MainActivity.pausePlaybackForOnDemand();
        setContentView(buildUi());
        loadPlayer();
    }

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        configureWebView(webView);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();

        if (tvMode) {
            TextView loading = new TextView(this);
            loading.setBackgroundColor(Color.BLACK);
            loading.setTextColor(0xFFB8BDC5);
            loading.setTextSize(18);
            loading.setGravity(Gravity.CENTER);
            loading.setText(tvText("Зареждане…", "Loading…"));
            loading.setFocusable(false);
            loading.setClickable(false);
            loading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tvLoadingSurface = loading;
            root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
            tvSurfaceGeneration++;
        }

        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        fullscreenContainer.setVisibility(View.GONE);
        root.addView(fullscreenContainer, new FrameLayout.LayoutParams(-1, -1));


        if (!tvMode) {
            remoteControls = new LinearLayout(this);
            remoteControls.setOrientation(LinearLayout.HORIZONTAL);
            remoteControls.setGravity(Gravity.CENTER_VERTICAL);
            remoteControls.setPadding(dp(14), dp(10), dp(14), dp(10));
            GradientDrawable controlBg = new GradientDrawable();
            controlBg.setColor(0xE6101419);
            controlBg.setCornerRadius(dp(14));
            controlBg.setStroke(dp(1), 0xFF343B45);
            remoteControls.setBackground(controlBg);
            remoteControls.setVisibility(View.GONE);
            firstControl = addControl(getString(R.string.player_play_pause), this::togglePlayPause);
            addControl("−10s", () -> seekBy(-10));
            addControl("+10s", () -> seekBy(10));
            addControl(getString(R.string.player_subtitles), this::openSubtitles);
            addControl(getString(R.string.player_quality), this::openQuality);
            addControl(getString(R.string.player_fullscreen), this::requestPlayerFullscreen);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            cp.setMargins(dp(18), dp(18), dp(18), dp(26));
            root.addView(remoteControls, cp);
        }

        immersive();
        return root;
    }

    private Button addControl(String label, Runnable action) {
        int accent = UiTheme.accent(this);
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(UiTheme.sp(this, 14));
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setFocusable(true);
        b.setClickable(true);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(14), 0, dp(14), 0);
        applyControlStyle(b, false, accent);
        b.setOnFocusChangeListener((v, focused) -> applyControlStyle(b, focused, accent));
        b.setOnClickListener(v -> {
            action.run();
            scheduleControlsHide();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));
        lp.setMargins(dp(4), 0, dp(4), 0);
        remoteControls.addView(b, lp);
        controlButtons.add(b);
        return b;
    }

    private void applyControlStyle(Button b, boolean focused, int accent) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(focused ? ((accent & 0x00FFFFFF) | 0xDD000000) : 0xFF20262D);
        bg.setStroke(dp(focused ? 2 : 1), focused ? Color.WHITE : 0xFF3A424C);
        b.setBackground(bg);
        b.animate().scaleX(focused ? 1.055f : 1f).scaleY(focused ? 1.055f : 1f).setDuration(90L).start();
    }

    private void immersive() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void configureWebView(WebView view) {
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        String ua = s.getUserAgentString();
        s.setUserAgentString((ua == null ? "" : ua) + " NyamaPlus/1.0.18");
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        view.setBackgroundColor(Color.BLACK);
        if (tvMode) {
            view.setOverScrollMode(View.OVER_SCROLL_NEVER);
            view.setHorizontalScrollBarEnabled(false);
            view.setVerticalScrollBarEnabled(false);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                view.setDefaultFocusHighlightEnabled(false);
            }
        }

        final WebView configuredView = view;
        view.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                if (tvMode && v == webView && isSelectedMainDocument(url))
                    Log.d(TAG, "TV initialization document_started");
            }

            @Override public void onPageCommitVisible(WebView v, String url) {
                super.onPageCommitVisible(v, url);
                if (tvMode && v == webView && isSelectedMainDocument(url)) {
                    Log.d(TAG, "TV initialization document_committed");
                    awaitTvVisualState(v);
                }
            }

            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                if (!tvMode) return super.onRenderProcessGone(v, detail);
                if (v != webView) return true; // An already retired TV surface cannot affect its replacement.
                handleTvRendererLoss(v);
                return true;
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return true;
                if (!request.isForMainFrame()) return false;
                return !isAllowedMainFrame(request.getUrl());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url == null) return true;
                try { return !isAllowedMainFrame(Uri.parse(url)); }
                catch (Exception ignored) { return true; }
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse response) {
                if (tvMode && view == webView && request != null && response != null && isPlaybackDocument(request))
                    showPlaybackError(tvText("Епизодът или видеото не е достъпно от този източник.", "This episode or video is unavailable from this source."));
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (tvMode && view == webView && request != null && isPlaybackDocument(request))
                    showPlaybackError(tvText("Видеото не може да бъде заредено. Проверете връзката и опитайте отново.", "Cannot load this video. Check your connection and try again."));
            }

            @Override public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                super.onReceivedSslError(view, handler, error); // Default certificate rejection is preserved.
                if (tvMode && view == webView && error != null && isPlaybackDocumentUrl(Uri.parse(safe(error.getUrl()))))
                    showPlaybackError(tvText("Не може да се установи защитена връзка с видео източника.", "Cannot establish a secure connection to the video source."));
            }

            @Override public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                if (tvMode) {
                    if (v != webView) return;
                    if (!isSelectedMainDocument(url)) {
                        // A completed same-provider redirect to a restriction/error route is a
                        // different document, not the selected episode. Do not mask it forever.
                        if (url != null && isAllowedMainFrame(Uri.parse(url)))
                            showPlaybackError(tvText("Източникът не отвори избраното видео. Опитайте отново.",
                                    "The source did not open the selected video. Try again."));
                        return;
                    }
                }
                hardenEmbedPage();
                if (tvMode) {
                    // A finished outer wrapper is not evidence that its cross-origin media is ready.
                    Log.d(TAG, "TV initialization document_finished");
                    awaitTvVisualState(v);
                    suppressTvEdgeFocus();
                    installTvPlaybackAssist();
                    if (episodeProgress != null) episodeProgress.installListener(url);
                    main.postDelayed(() -> { if (v == webView) installTvPlaybackAssist(); }, 900L);
                    main.postDelayed(() -> { if (v == webView) installTvPlaybackAssist(); }, 2200L);
                }
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override public Bitmap getDefaultVideoPoster() {
                if (!tvMode) return super.getDefaultVideoPoster();
                if (tvDefaultVideoPoster == null) {
                    tvDefaultVideoPoster = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                    tvDefaultVideoPoster.eraseColor(Color.BLACK);
                }
                return tvDefaultVideoPoster;
            }

            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                return false;
            }

            @Override public void onCloseWindow(WebView window) {
                // Ignore third-party attempts to close/replace the Nyama+ player Activity.
            }

            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (tvMode && configuredView != webView) { callback.onCustomViewHidden(); return; }
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                view.setFocusable(true);
                view.setFocusableInTouchMode(true);
                if (tvMode && android.os.Build.VERSION.SDK_INT >= 26) {
                    view.setDefaultFocusHighlightEnabled(false);
                }
                fullscreenContainer.removeAllViews();
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(-1, -1));
                fullscreenContainer.setVisibility(View.VISIBLE);
                if (tvMode) view.post(() -> markTvSurfaceVisible(configuredView));
                view.requestFocus();
                setControlsVisible(false, false);
                immersive();
            }

            @Override public void onHideCustomView() {
                if (!tvMode || configuredView == webView) hideCustomView();
            }
        });
    }

    private boolean isAllowedMainFrame(Uri uri) {
        if (uri == null) return false;
        String scheme = safe(uri.getScheme()).toLowerCase(java.util.Locale.ROOT);
        if (!("https".equals(scheme) || "http".equals(scheme))) return false;
        String host = safe(uri.getHost()).toLowerCase(java.util.Locale.ROOT);
        if (tvMode) return "https".equals(scheme)
                && (selectedPlaybackHost.equals(host) || host.endsWith("." + selectedPlaybackHost));
        return PLAYER_HOST.equals(host) || host.endsWith("." + PLAYER_HOST);
    }

    private void hardenEmbedPage() {
        if (webView == null) return;
        String js = "(function(){try{" +
                "window.open=function(){return null;};" +
                "document.addEventListener('click',function(e){" +
                "var a=e.target&&e.target.closest?e.target.closest('a'):null;" +
                "if(!a||!a.href)return;" +
                "try{var u=new URL(a.href,location.href);" +
                "if(u.host&&u.host!==location.host&&(a.target==='_blank'||(a.rel||'').indexOf('noopener')>=0)){e.preventDefault();e.stopImmediatePropagation();}}catch(x){}" +
                "},true);" +
                "}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    private void suppressTvEdgeFocus() {
        if (!tvMode || webView == null) return;
        String js = "(function(){try{" +
                "if(!document.getElementById('__nyama_tv_edge_fix')){var st=document.createElement('style');st.id='__nyama_tv_edge_fix';st.textContent='html,body,iframe,video{outline:none!important;}';(document.head||document.documentElement).appendChild(st);}" +
                "return '1';}catch(e){return '0';}})();";
        webView.evaluateJavascript(js, null);
    }

    /** Prefer an actually available app-language HTML5 track once, without touching protected frames. */
    private void installTvPlaybackAssist() {
        if (!tvMode || webView == null) return;
        String js = "(function(){try{" + accessibleVideoScript() +
                "if(!best||!best.textTracks)return '0';" +
                "if(best.__nyamaSubtitlePreferenceApplied)return '1';" +
                "var found=-1;for(var i=0;i<best.textTracks.length;i++){var t=best.textTracks[i];" +
                "if(new RegExp('^(" + (Config.LANG_EN.equals(config.language()) ? "en|eng" : "bg|bul") + ")(-|$)','i').test(t.language||''))found=i;}" +
                "if(found<0)return '0';best.__nyamaSubtitlePreferenceApplied=true;" +
                "for(var j=0;j<best.textTracks.length;j++)best.textTracks[j].mode=(j===found?'showing':'disabled');" +
                "return '1';}catch(e){return '0';}})();";
        // Never repeatedly call play(), click provider menus or override a customer's pause.
        webView.evaluateJavascript(js, null);
    }

    private String accessibleVideoScript() {
        return "function docs(d,a){a.push(d);var fs=d.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){" +
                "try{if(fs[i].contentDocument)docs(fs[i].contentDocument,a);}catch(e){}}return a;}" +
                "var ds=docs(document,[]),best=null,area=-1;for(var j=0;j<ds.length;j++){var vs=ds[j].querySelectorAll('video');" +
                "for(var k=0;k<vs.length;k++){var v=vs[k],r=v.getBoundingClientRect(),a=Math.max(0,r.width)*Math.max(0,r.height);" +
                "if(a>area){area=a;best=v;}}}";
    }

    private void loadPlayer() {
        String path = "series".equals(type) ? "tv" : "movie";
        if (!tvMode) {
            webView.loadUrl(PLAYER_BASE + path + "/" + Uri.encode(id));
            return;
        }
        try {
            boolean selection = getIntent().hasExtra("season") || getIntent().hasExtra("episode") || returnToSeries;
            if ("series".equals(type) && selection) {
                loadedPlayerUrl = TvPlaybackSource.episode(id, safe(getIntent().getStringExtra("series_imdb_id")),
                        getIntent().getIntExtra("season", -1), getIntent().getIntExtra("episode", -1), config.language(), UiTheme.accent(this));
            } else if ("movies".equals(type)) {
                loadedPlayerUrl = TvPlaybackSource.movie(id, config.language(), UiTheme.accent(this));
            } else {
                // No accurate episode metadata: preserve the existing series route, never invent S1E1.
                loadedPlayerUrl = PLAYER_BASE + "tv/" + Uri.encode(id);
            }
            selectedPlaybackHost = Uri.parse(loadedPlayerUrl).getHost();
            startSelectedTvDocument();
        } catch (IllegalArgumentException invalidIdentity) {
            showPlaybackError(tvText("Избраният епизод или идентификатор не е валиден. Изберете епизода отново.",
                    "The selected episode or title identity is invalid. Select the episode again."));
        }
    }

    /** Load only after the new TV surface is attached and sized; phone timing stays unchanged. */
    private void startSelectedTvDocument() {
        final WebView target = webView;
        final long generation = tvSurfaceGeneration;
        final String selectedUrl = loadedPlayerUrl;
        if (target == null || selectedUrl.isEmpty()) return;
        if ("series".equals(type) && TvPlaybackSource.HOST.equals(selectedPlaybackHost)) {
            episodeProgress = TvPlayerProgressBridge.attach(target, new TvEpisodeProgressStore(this), id,
                    getIntent().getIntExtra("season", -1), getIntent().getIntExtra("episode", -1));
        }
        Runnable load = () -> {
            if (target != webView || generation != tvSurfaceGeneration || tvDocumentLoadGeneration == generation
                    || playbackFailed || leavingPlayback) return;
            if (!tvForeground || !target.isAttachedToWindow() || target.getWidth() <= 0 || target.getHeight() <= 0) return;
            pendingTvDocumentLoad = null;
            tvDocumentLoadGeneration = generation;
            // pauseTimers() is global: a replacement within the same resumed Activity otherwise
            // never receives another Activity.onResume(), leaving provider scripts suspended.
            target.onResume();
            target.resumeTimers();
            Log.d(TAG, "TV initialization document_load recovery=" + (rendererRecoveryUsed ? 1 : 0));
            target.loadUrl(selectedUrl);
        };
        pendingTvDocumentLoad = load;
        if (target.isAttachedToWindow() && target.getWidth() > 0 && target.getHeight() > 0) {
            target.post(load);
        } else {
            target.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (target != webView || generation != tvSurfaceGeneration) {
                        target.removeOnLayoutChangeListener(this);
                    } else if (target.isAttachedToWindow() && right > left && bottom > top) {
                        target.removeOnLayoutChangeListener(this);
                        target.post(load);
                    }
                }
            });
        }
    }

    private boolean isSelectedMainDocument(String url) {
        if (url == null || loadedPlayerUrl.isEmpty()) return false;
        Uri actual = Uri.parse(url);
        Uri expected = Uri.parse(loadedPlayerUrl);
        return "https".equals(actual.getScheme()) && actual.getUserInfo() == null
                && actual.getPort() == -1 && selectedPlaybackHost.equals(actual.getHost())
                && expected.getPath().equals(actual.getPath());
    }

    private void awaitTvVisualState(WebView target) {
        if (target != webView || playbackFailed || leavingPlayback) return;
        final long generation = tvSurfaceGeneration;
        // This checks WebView render submission, never pixel brightness, elapsed loading time or
        // a cross-origin document's DOM. It does not claim the provider's media has started.
        try {
            target.postVisualStateCallback(generation, new WebView.VisualStateCallback() {
                @Override public void onComplete(long requestId) {
                    if (requestId == tvSurfaceGeneration) markTvSurfaceVisible(target);
                }
            });
        } catch (RuntimeException | LinkageError unsupportedRenderCallback) {
            // Some old vendor WebViews cannot provide this acknowledgement. Their valid
            // document-commit/finish signal still exposes the provider instead of a permanent mask.
            Log.w(TAG, "TV initialization visual_callback_unavailable");
            markTvSurfaceVisible(target);
        }
    }

    private void markTvSurfaceVisible(WebView target) {
        if (target != webView || playbackFailed || leavingPlayback) return;
        if (tvLoadingSurface != null && tvLoadingSurface.getVisibility() != View.GONE) {
            tvLoadingSurface.setVisibility(View.GONE);
            Log.d(TAG, "TV initialization surface_presented");
        }
    }

    private void handleTvRendererLoss(WebView failed) {
        Log.w(TAG, "TV initialization renderer_gone recovery=" + (rendererRecoveryUsed ? 1 : 0));
        if (episodeProgress != null) {
            episodeProgress.close();
            episodeProgress = null;
        }
        pendingTvDocumentLoad = null;
        // Android requires discarding a dead renderer. Do not run JavaScript, stopLoading(),
        // about:blank navigation or pauseTimers() against its unusable WebView.
        webView = null;
        // Its fullscreen callback also belongs to the dead renderer; detach without calling it.
        customView = null;
        customViewCallback = null;
        fullscreenContainer.removeAllViews();
        fullscreenContainer.setVisibility(View.GONE);
        ViewParentSafe.remove(failed);
        failed.destroy();
        if (leavingPlayback || isFinishing() || isDestroyed()) return;
        if (rendererRecoveryUsed) {
            showPlaybackError(tvText("Видео плейърът не можа да се стартира. Опитайте отново.",
                    "The video player could not start. Try again."));
            return;
        }
        rendererRecoveryUsed = true;
        rendererRecoveryPending = true;
        if (tvForeground) main.post(this::recoverTvRenderer);
    }

    private void recoverTvRenderer() {
        if (!rendererRecoveryPending || !tvForeground || leavingPlayback || isFinishing() || isDestroyed()) return;
        rendererRecoveryPending = false;
        Log.d(TAG, "TV initialization renderer_recreate");
        setContentView(buildUi());
        startSelectedTvDocument(); // Reuse the immutable selected URL, including episode and subtitles.
    }

    private boolean isPlaybackDocument(WebResourceRequest request) {
        if (request.isForMainFrame()) return true;
        return isPlaybackDocumentUrl(request.getUrl());
    }

    private boolean isPlaybackDocumentUrl(Uri uri) {
        return uri != null && "https".equals(uri.getScheme()) && !loadedPlayerUrl.isEmpty()
                && (selectedPlaybackHost.equals(uri.getHost()) || "nextgencloudfabric.com".equals(uri.getHost()))
                && Uri.parse(loadedPlayerUrl).getPath().equals(uri.getPath());
    }

    private String tvText(String bg, String en) { return Config.LANG_EN.equals(config.language()) ? en : bg; }

    private void showPlaybackError(String explanation) {
        if (!tvMode || isFinishing() || isDestroyed() || leavingPlayback || playbackFailed) return;
        playbackFailed = true;
        rendererRecoveryPending = false;
        Log.w(TAG, "TV initialization failed_with_retry");
        releaseMovieWebView();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(40), dp(32), dp(40), dp(32));
        panel.setBackgroundColor(0xFF080A0D);
        TextView message = new TextView(this);
        message.setText(explanation);
        message.setTextSize(22);
        message.setTextColor(Color.WHITE);
        message.setGravity(Gravity.CENTER);
        panel.addView(message, new LinearLayout.LayoutParams(-1, -2));
        Button retry = new Button(this);
        retry.setText(tvText("Опитай отново", "Try again"));
        retry.setAllCaps(false);
        retry.setOnClickListener(view -> {
            playbackFailed = false;
            playbackError = null;
            setContentView(buildUi());
            if (loadedPlayerUrl.isEmpty()) loadPlayer();
            else startSelectedTvDocument(); // Same immutable title/episode, never a provider fallback.
        });
        panel.addView(retry, new LinearLayout.LayoutParams(dp(400), dp(60)));
        Button back = new Button(this);
        back.setText(returnToSeries ? tvText("Назад към епизодите", "Back to episodes") : tvText("Назад", "Back"));
        back.setAllCaps(false);
        back.setOnClickListener(view -> finishTvPlayback());
        panel.addView(back, new LinearLayout.LayoutParams(dp(400), dp(60)));
        playbackError = panel;
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        retry.requestFocus();
    }

    private void seekBy(int seconds) {
        runVideoScript("var v=document.querySelector('video');if(!v)return '0';" +
                "var d=isFinite(v.duration)?v.duration:1e12;v.currentTime=Math.max(0,Math.min(d,v.currentTime+" + seconds + "));return '1';", false);
    }

    private void togglePlayPause() {
        runVideoScript("var v=document.querySelector('video');if(!v)return '0';if(v.paused){v.play();}else{v.pause();}return '1';", false);
    }

    private void openSubtitles() {
        String js = "var v=document.querySelector('video');" +
                "if(v&&v.textTracks&&v.textTracks.length){var t=v.textTracks;var current=-1;for(var i=0;i<t.length;i++){if(t[i].mode==='showing'){current=i;t[i].mode='disabled';}else t[i].mode='disabled';}" +
                "var next=(current+1)% (t.length+1);if(next<t.length){t[next].mode='showing';return 'track:'+((t[next].label||t[next].language||(''+(next+1))));}return 'track:off';}" +
                "return '0';";
        webView.evaluateJavascript("(function(){try{" + js + "}catch(e){return '0';}})();", result -> {
            String r = result == null ? "" : result.replace("\\\"", "").replace("\"", "");
            if (r.contains("track:")) {
                Toast.makeText(this, getString(R.string.player_subtitles) + ": " + r.substring(r.indexOf("track:") + 6), Toast.LENGTH_SHORT).show();
            } else {
                clickProviderControl("subtitle");
            }
        });
    }

    private void openQuality() {
        clickProviderControl("quality");
    }

    private void requestPlayerFullscreen() {
        String js = "var b=document.querySelector('[aria-label*=\\\"fullscreen\\\" i],[title*=\\\"fullscreen\\\" i],[class*=\\\"fullscreen\\\"]');" +
                "if(b){b.click();return '1';}var v=document.querySelector('video');if(v&&v.requestFullscreen){v.requestFullscreen();return '1';}return '0';";
        runVideoScript(js, true);
    }

    private void clickProviderControl(String kind) {
        final boolean subtitle = "subtitle".equals(kind);
        String words = subtitle ? "subtitle|caption|subtitles|cc" : "quality|resolution|settings|gear";
        String js = "var all=document.querySelectorAll('button,[role=button],[title],[aria-label],[class]');" +
                "var re=/" + words + "/i;for(var i=0;i<all.length;i++){var e=all[i];var txt=(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.textContent||'')+' '+(e.className||'');" +
                "var r=e.getBoundingClientRect();if(re.test(txt)&&r.width>0&&r.height>0){e.click();return '1';}}return '0';";
        webView.evaluateJavascript("(function(){try{" + js + "}catch(e){return '0';}})();", result -> {
            boolean ok = result != null && result.contains("1");
            if (ok) {
                providerMenuMode = true;
                setControlsVisible(false, false);
                main.postDelayed(this::focusFirstProviderControl, 180L);
            } else {
                Toast.makeText(this, R.string.player_control_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void focusFirstProviderControl() {
        if (webView == null) return;
        String js = "(function(){try{var a=[].slice.call(document.querySelectorAll('button,[role=menuitem],[role=option],select,input,[tabindex]')).filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;});if(a.length){a[0].focus();return '1';}return '0';}catch(e){return '0';}})();";
        webView.evaluateJavascript(js, null);
        webView.requestFocus();
    }

    private void moveProviderFocus(int delta) {
        if (webView == null) return;
        String js = "(function(){try{var a=[].slice.call(document.querySelectorAll('button,[role=menuitem],[role=option],select,input,[tabindex]')).filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;});if(!a.length)return '0';var i=a.indexOf(document.activeElement);if(i<0)i=0;else i=(i+" + delta + "+a.length)%a.length;a[i].focus();return '1';}catch(e){return '0';}})();";
        webView.evaluateJavascript(js, null);
    }

    private void activateProviderFocus() {
        if (webView == null) return;
        webView.evaluateJavascript("(function(){try{var e=document.activeElement;if(e&&e.click){e.click();return '1';}return '0';}catch(x){return '0';}})();", null);
    }

    private void escapeProviderMenu() {
        providerMenuMode = false;
        if (webView != null) {
            webView.evaluateJavascript("(function(){try{document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true}));return '1';}catch(e){return '0';}})();", null);
        }
    }

    /**
     * Android-TV Movies/Series control without an external Nyama+ control bar.
     * The selected provider stays in the existing WebView. We first control an accessible HTML5
     * video (including same-origin nested frames); otherwise the provider WebView receives a
     * complete native SPACE press as fallback. Cross-origin shortcut support depends on the
     * provider; a routed key is not proof that playback changed.
     */
    private void toggleTvPlayback() {
        if (!tvMode || webView == null) return;
        final WebView requestedView = webView;
        String js = "(function(){try{" +
                "function docs(d,a){a.push(d);var f=d.querySelectorAll('iframe');for(var i=0;i<f.length;i++){try{if(f[i].contentDocument)docs(f[i].contentDocument,a);}catch(e){}}return a;}" +
                "var ds=docs(document,[]),best=null,area=-1;for(var j=0;j<ds.length;j++){var vs=ds[j].querySelectorAll('video');for(var k=0;k<vs.length;k++){var v=vs[k],r=v.getBoundingClientRect(),a=Math.max(0,r.width)*Math.max(0,r.height);if(a>area){area=a;best=v;}}}" +
                "if(!best)return '0';if(best.paused||best.ended){var p=best.play();if(p&&p.catch)p.catch(function(){});}else{best.pause();}return '1';" +
                "}catch(e){return '0';}})();";
        webView.evaluateJavascript(js, result -> {
            if (requestedView != webView || playbackFailed || leavingPlayback) return;
            if (result != null && result.contains("1")) {
                Log.d(TAG, "play/pause strategy=direct HTML5 video");
            } else {
                // A cross-origin frame hides its <video> from injected JavaScript. SPACE is the
                // existing shortcut fallback; ENTER/DPAD_CENTER is deliberately not reused.
                Log.d(TAG, "play/pause strategy=KEYCODE_SPACE fallback");
                sendNativeKeyToPlayer(KeyEvent.KEYCODE_SPACE);
            }
        });
    }

    private void setTvPlayback(boolean play) {
        if (!tvMode || webView == null) return;
        final WebView requestedView = webView;
        String action = play ? "play" : "pause";
        String js = "(function(){try{var v=document.querySelector('video');if(!v)return '0';v."
                + action + "();return '1';}catch(e){return '0';}})();";
        webView.evaluateJavascript(js, result -> {
            if (requestedView != webView || playbackFailed || leavingPlayback) return;
            if (result != null && result.contains("1")) {
                Log.d(TAG, action + " strategy=direct HTML5 video");
            } else {
                // Cross-origin media cannot expose state or separate play/pause operations. A
                // single SPACE press still gives physical media buttons useful provider behavior.
                Log.d(TAG, action + " strategy=KEYCODE_SPACE cross-origin fallback");
                sendNativeKeyToPlayer(KeyEvent.KEYCODE_SPACE);
            }
        });
    }

    private void seekTvPlayback(int seconds) {
        if (!tvMode || webView == null) return;
        if (episodeProgress != null) episodeProgress.breakContinuity();
        final WebView requestedView = webView;
        String js = "(function(){try{" +
                "function docs(d,a){a.push(d);var f=d.querySelectorAll('iframe');for(var i=0;i<f.length;i++){try{if(f[i].contentDocument)docs(f[i].contentDocument,a);}catch(e){}}return a;}" +
                "var ds=docs(document,[]),best=null,area=-1;for(var j=0;j<ds.length;j++){var vs=ds[j].querySelectorAll('video');for(var k=0;k<vs.length;k++){var v=vs[k],r=v.getBoundingClientRect(),a=Math.max(0,r.width)*Math.max(0,r.height);if(a>area){area=a;best=v;}}}" +
                "if(!best)return '0';var d=isFinite(best.duration)?best.duration:1e15;best.currentTime=Math.max(0,Math.min(d,(best.currentTime||0)+(" + seconds + ")));return '1';" +
                "}catch(e){return '0';}})();";
        webView.evaluateJavascript(js, result -> {
            if (requestedView != webView || playbackFailed || leavingPlayback) return;
            if (result == null || !result.contains("1")) {
                // Let the provider handle its own keyboard shortcut when the media element lives
                // inside an inaccessible/cross-origin frame.
                sendNativeKeyToPlayer(seconds < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
            }
        });
    }

    private void sendNativeKeyToPlayer(int keyCode) {
        View target = customView != null ? customView : webView;
        if (target == null) return;
        target.setFocusable(true);
        target.setFocusableInTouchMode(true);
        target.requestFocus();
        Runnable dispatch = () -> {
            if (leavingPlayback || playbackFailed || target != (customView != null ? customView : webView)) return;
            long now = android.os.SystemClock.uptimeMillis();
            Log.d(TAG, "forwarding " + KeyEvent.keyCodeToString(keyCode) + " down/up to "
                    + (target == customView ? "fullscreen customView" : "WebView"));
            target.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            target.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        };
        if (target != webView) {
            dispatch.run();
            return;
        }
        // Calling focus() on a cross-origin iframe element is permitted even though reading its
        // document is not. This makes the provider, rather than an unrelated outer-page control,
        // receive the real Android fallback key event.
        String focusFrame = "(function(){try{var fs=document.querySelectorAll('iframe'),best=null,area=0;" +
                "for(var i=0;i<fs.length;i++){var r=fs[i].getBoundingClientRect(),a=Math.max(0,r.width)*Math.max(0,r.height);if(a>area){area=a;best=fs[i];}}" +
                "if(best){best.focus();return '1';}if(document.body)document.body.focus();return '0';}catch(e){return '0';}})();";
        webView.evaluateJavascript(focusFrame, ignored -> dispatch.run());
    }

    private void clearBackArm() {
        closeArmed = false;
        main.removeCallbacks(clearCloseArm);
    }

    /** TV BACK is intentionally two distinct physical presses; duplicate callbacks are ignored. */
    private void handleTvBackPressed() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastAcceptedBackAt < BACK_DUPLICATE_GUARD_MS) return;
        lastAcceptedBackAt = now;

        if (customView != null) {
            hideCustomView();
            clearBackArm();
            return;
        }
        if (webView == null && !playbackFailed) return;

        if (closeArmed) {
            clearBackArm();
            finishTvPlayback();
            return;
        }

        // First BACK only closes/escapes provider UI and arms the explicit second press.
        // It never finishes this Activity.
        sendNativeKeyToPlayer(KeyEvent.KEYCODE_ESCAPE);
        closeArmed = true;
        main.removeCallbacks(clearCloseArm);
        main.postDelayed(clearCloseArm, BACK_CONFIRM_MS);
        Toast.makeText(this, R.string.player_back_again, Toast.LENGTH_SHORT).show();
    }

    private boolean handleTvRemoteKeyDown(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int repeat = event.getRepeatCount();

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_INFO:
                // Playback mode has no Nyama+ controls surface or hidden focus targets.
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (repeat == 0) seekTvPlayback(-10); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (repeat == 0) seekTvPlayback(10); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (repeat == 0) toggleTvPlayback(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (repeat == 0) toggleTvPlayback(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (repeat == 0) setTvPlayback(true); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (repeat == 0) setTvPlayback(false); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (repeat == 0) seekTvPlayback(-10); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (repeat == 0) seekTvPlayback(10); return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                handleTvBackPressed(); return true;
            default:
                return false;
        }
    }

    private boolean isTvRemoteKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return true;
            default:
                return false;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (tvMode && playbackFailed && event != null
                && event.getKeyCode() != KeyEvent.KEYCODE_BACK && event.getKeyCode() != KeyEvent.KEYCODE_ESCAPE)
            return super.dispatchKeyEvent(event); // Retry/Return are real focusable error actions, not playback controls.
        if (!tvMode || event == null || !isTvRemoteKey(event.getKeyCode())) {
            return super.dispatchKeyEvent(event);
        }
        Log.d(TAG, "key=" + KeyEvent.keyCodeToString(event.getKeyCode())
                + " keyCode=" + event.getKeyCode()
                + " action=" + (event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP")
                + " repeat=" + event.getRepeatCount()
                + " fullscreen=" + (customView != null)
                + " webViewFocus=" + (webView != null && webView.hasFocus()));
        // Intercept before WebView consumes DPAD. Handle one action per physical press; ACTION_UP is
        // consumed so controls are not activated twice. Holding BACK must never count as the second
        // confirmation press, otherwise Android key-repeat could close playback immediately.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if ((key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) && event.getRepeatCount() > 0) return true;
            return handleTvRemoteKeyDown(event);
        }
        return true;
    }

    private void runVideoScript(String body, boolean showFailure) {
        if (webView == null) return;
        webView.evaluateJavascript("(function(){try{" + body + "}catch(e){return '0';}})();", result -> {
            if (showFailure && (result == null || !result.contains("1"))) {
                Toast.makeText(this, R.string.player_control_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setControlsVisible(boolean visible, boolean focus) {
        if (remoteControls == null) return;
        remoteControls.setVisibility(visible ? View.VISIBLE : View.GONE);
        main.removeCallbacks(hideControls);
        if (visible) {
            providerMenuMode = false;
            if (focus && firstControl != null) {
                int current = -1;
                for (int i = 0; i < controlButtons.size(); i++) if (controlButtons.get(i).hasFocus()) { current = i; break; }
                controlIndex = current >= 0 ? current : 0;
                controlButtons.get(Math.max(0, Math.min(controlIndex, controlButtons.size() - 1))).requestFocus();
            }
            if (!tvMode) main.postDelayed(hideControls, CONTROLS_HIDE_MS);
        } else if (webView != null) {
            webView.requestFocus();
        }
    }

    private void scheduleControlsHide() {
        main.removeCallbacks(hideControls);
        if (remoteControls != null && remoteControls.getVisibility() == View.VISIBLE) {
            if (!tvMode) main.postDelayed(hideControls, CONTROLS_HIDE_MS);
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Android TV is handled in dispatchKeyEvent() before WebView can consume the remote.
        // Phone/mobile keeps the exact 1.0.7 key behavior.
        if (tvMode) return super.onKeyDown(keyCode, event);

        if (providerMenuMode) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    moveProviderFocus(-1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    moveProviderFocus(1); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    activateProviderFocus(); return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    escapeProviderMenu(); return true;
            }
        }

        if (remoteControls != null && remoteControls.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                setControlsVisible(false, false);
                return true;
            }
            scheduleControlsHide();
            return super.onKeyDown(keyCode, event);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-10); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(10); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayPause(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                runVideoScript("var v=document.querySelector('video');if(v){v.play();return '1';}return '0';", false); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                runVideoScript("var v=document.querySelector('video');if(v){v.pause();return '1';}return '0';", false); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_INFO:
                setControlsVisible(true, true); return true;
            case KeyEvent.KEYCODE_CAPTIONS:
                openSubtitles(); return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        fullscreenContainer.removeAllViews();
        fullscreenContainer.setVisibility(View.GONE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customView = null;
        customViewCallback = null;
        if (webView != null) {
            webView.setFocusable(true);
            webView.setFocusableInTouchMode(true);
            webView.requestFocus();
        }
        immersive();
    }

    private void tryDismissPlayerOverlayThenMaybeClose() {
        if (webView == null) { resumeTvWhenClosing = true; finish(); return; }
        if (webView.canGoBack()) {
            webView.goBack();
            hardenEmbedPage();
            return;
        }

        String js = "(function(){try{" +
                "var s=['button[aria-label*=\\\"close\\\" i]','button[title*=\\\"close\\\" i]','[class*=\\\"popup\\\"] [class*=\\\"close\\\"]','[class*=\\\"modal\\\"] [class*=\\\"close\\\"]','[id*=\\\"popup\\\"] [class*=\\\"close\\\"]'];" +
                "for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);if(e){var r=e.getBoundingClientRect();if(r.width>0&&r.height>0){e.click();return '1';}}}" +
                "return '0';}catch(x){return '0';}})();";
        webView.evaluateJavascript(js, result -> {
            boolean dismissed = result != null && (result.contains("1") || result.contains("true"));
            if (dismissed) return;
            if (closeArmed) {
                closeArmed = false;
                main.removeCallbacks(clearCloseArm);
                resumeTvWhenClosing = true;
                finish();
            } else {
                closeArmed = true;
                main.removeCallbacks(clearCloseArm);
                main.postDelayed(clearCloseArm, BACK_CONFIRM_MS);
                Toast.makeText(this, R.string.player_back_again, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override protected void onPause() {
        if (tvMode) tvForeground = false;
        if (episodeProgress != null) episodeProgress.pause();
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing() || !ActivationAccess.require(this)) return;
        if (tvMode) tvForeground = true;
        if (episodeProgress != null) episodeProgress.resume();
        immersive();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
        if (tvMode && rendererRecoveryPending) recoverTvRenderer();
        else if (tvMode && pendingTvDocumentLoad != null && webView != null) webView.post(pendingTvDocumentLoad);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (tvMode) { handleTvBackPressed(); return; }
        if (remoteControls != null && remoteControls.getVisibility() == View.VISIBLE) { setControlsVisible(false, false); return; }
        if (providerMenuMode) { escapeProviderMenu(); return; }
        if (customView != null) { hideCustomView(); return; }
        tryDismissPlayerOverlayThenMaybeClose();
    }

    private void finishTvPlayback() {
        leavingPlayback = true;
        if (returnToSeries) {
            clearBackArm();
            releaseMovieWebView();
            resumeTvWhenClosing = false;
            // Browsing artwork remains visual-only: restore the existing live stream only after
            // VOD has been destroyed, without opening or recreating MainActivity.
            MainActivity.resumePlaybackAfterOnDemand();
            finish(); // Existing series detail Activity and its selected episode remain in place.
        } else returnToLiveTv();
    }

    private void returnToLiveTv() {
        clearBackArm();
        // Destroy movie media before requesting Live TV, so the two audio sources cannot overlap.
        releaseMovieWebView();
        resumeTvWhenClosing = false;
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_RETURN_FROM_ON_DEMAND, true));
        finish();
    }

    private void releaseMovieWebView() {
        if (tvMode) pendingTvDocumentLoad = null;
        if (episodeProgress != null) {
            episodeProgress.close();
            episodeProgress = null;
        }
        hideCustomView();
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.onPause();
            webView.pauseTimers();
            webView.clearHistory();
            ViewParentSafe.remove(webView);
            webView.destroy();
            webView = null;
        }
    }

    @Override protected void onDestroy() {
        leavingPlayback = true;
        main.removeCallbacks(clearCloseArm);
        main.removeCallbacks(hideControls);
        releaseMovieWebView();
        if (resumeTvWhenClosing) MainActivity.resumePlaybackAfterOnDemand();
        super.onDestroy();
    }

    private static final class ViewParentSafe {
        static void remove(View view) {
            if (view == null || !(view.getParent() instanceof ViewGroup)) return;
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private static String safe(String v) { return v == null ? "" : v.trim(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
