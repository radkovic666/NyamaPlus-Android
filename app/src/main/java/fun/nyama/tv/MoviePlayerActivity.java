package fun.nyama.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
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
 * Both Android TV and phone mode use the same StreamIMDB embed. TV input is intercepted before
 * the WebView so remote mappings remain deterministic while phone mode keeps its touch behavior.
 */
public final class MoviePlayerActivity extends Activity {
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
    private final Runnable clearCloseArm = () -> closeArmed = false;
    private final Runnable hideControls = () -> setControlsVisible(false, false);

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = new Config(this);
        tvMode = !config.isPhoneMode();
        id = safe(getIntent().getStringExtra("id"));
        type = "series".equals(getIntent().getStringExtra("type")) ? "series" : "movies";
        title = safe(getIntent().getStringExtra("title"));
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

        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        fullscreenContainer.setVisibility(View.GONE);
        root.addView(fullscreenContainer, new FrameLayout.LayoutParams(-1, -1));


        // The Nyama+ button bar is kept only for phone mode. Android TV now uses the
        // embedded player's own surface directly with the remote, without an extra menu.
        if (!tvMode) {
            remoteControls = new LinearLayout(this);
            remoteControls.setOrientation(LinearLayout.HORIZONTAL);
            remoteControls.setGravity(Gravity.CENTER);
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
        s.setUserAgentString((ua == null ? "" : ua) + " NyamaPlus/1.0.17");
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

        view.setWebViewClient(new WebViewClient() {
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

            @Override public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                hardenEmbedPage();
                if (tvMode) {
                    suppressTvEdgeFocus();
                    installTvPlaybackAssist();
                    main.postDelayed(MoviePlayerActivity.this::installTvPlaybackAssist, 900L);
                    main.postDelayed(MoviePlayerActivity.this::installTvPlaybackAssist, 2200L);
                }
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                return false;
            }

            @Override public void onCloseWindow(WebView window) {
                // Ignore third-party attempts to close/replace the Nyama+ player Activity.
            }

            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                if (tvMode && android.os.Build.VERSION.SDK_INT >= 26) {
                    view.setDefaultFocusHighlightEnabled(false);
                }
                fullscreenContainer.removeAllViews();
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(-1, -1));
                fullscreenContainer.setVisibility(View.VISIBLE);
                setControlsVisible(false, false);
                immersive();
            }

            @Override public void onHideCustomView() { hideCustomView(); }
        });
    }

    private boolean isAllowedMainFrame(Uri uri) {
        if (uri == null) return false;
        String scheme = safe(uri.getScheme()).toLowerCase(java.util.Locale.ROOT);
        if (!("https".equals(scheme) || "http".equals(scheme))) return false;
        String host = safe(uri.getHost()).toLowerCase(java.util.Locale.ROOT);
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

    /**
     * TV-only playback assistance. It does not draw Nyama+ controls over the provider player.
     * Instead it asks any accessible HTML5 video to start and prefers a Bulgarian subtitle track.
     * The helper also watches late-created video/track elements, which are common in embed players.
     */
    private void installTvPlaybackAssist() {
        if (!tvMode || webView == null) return;
        String js = "(function(){try{" +
                "function isBg(v){v=(v||'').toString().toLowerCase();return /(^|[-_ ])bg($|[-_ ])|bul|bulgar|бълг/.test(v);}" +
                "function vis(e){if(!e)return false;var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}" +
                "function bgText(e){return (e.textContent||'')+' '+(e.getAttribute&&((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.getAttribute('data-language')||'')+' '+(e.getAttribute('data-lang')||'')));}" +
                "function chooseBg(d){var q=d.querySelectorAll('[role=menuitem],[role=option],button,li,[data-language],[data-lang]');" +
                "for(var z=0;z<q.length;z++){var e=q[z];if(vis(e)&&isBg(bgText(e))){try{e.click();return true;}catch(x){}}}return false;}" +
                "function applyDoc(d){if(!d)return;var hasBg=false;" +
                "var vids=d.querySelectorAll('video');for(var i=0;i<vids.length;i++){var v=vids[i];" +
                "try{v.autoplay=true;v.setAttribute('autoplay','autoplay');var tr=v.querySelectorAll('track');" +
                "for(var j=0;j<tr.length;j++){var t=tr[j];if(isBg(t.srclang)||isBg(t.label)){t.default=true;t.setAttribute('default','default');hasBg=true;}}" +
                "if(v.textTracks&&v.textTracks.length){var found=-1;for(var k=0;k<v.textTracks.length;k++){var x=v.textTracks[k];" +
                "if(isBg(x.language)||isBg(x.label))found=k;}if(found>=0){hasBg=true;for(var k2=0;k2<v.textTracks.length;k2++)v.textTracks[k2].mode=(k2===found?'showing':'disabled');}}" +
                "var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(e){}}" +
                "var sels=d.querySelectorAll('select');for(var s=0;s<sels.length;s++){try{var el=sels[s],opts=el.options||[];" +
                "for(var o=0;o<opts.length;o++){var op=opts[o];if(isBg(op.value)||isBg(op.text)){el.selectedIndex=o;hasBg=true;" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));break;}}}catch(e){}}" +
                "if(!hasBg&&!d.__nyamaBgCustomTried){if(chooseBg(d)){d.__nyamaBgCustomTried=true;}else{" +
                "var c=d.querySelectorAll('button,[role=button],[aria-label],[title]');for(var m=0;m<c.length;m++){var b=c[m],tx=bgText(b);" +
                "if(vis(b)&&/(subtitle|caption|\\bcc\\b|субт)/i.test(tx)){d.__nyamaBgCustomTried=true;try{b.click();}catch(x){}" +
                "setTimeout(function(){chooseBg(d);},160);setTimeout(function(){chooseBg(d);},520);break;}}}}" +
                "var fr=d.querySelectorAll('iframe');for(var f=0;f<fr.length;f++){try{if(fr[f].contentDocument)applyDoc(fr[f].contentDocument);}catch(e){}}}" +
                "applyDoc(document);" +
                "if(!window.__nyamaBgObserver&&document.documentElement){window.__nyamaBgObserver=new MutationObserver(function(){applyDoc(document);});" +
                "window.__nyamaBgObserver.observe(document.documentElement,{childList:true,subtree:true,attributes:true});}" +
                "if(!window.__nyamaBgTimer){var n=0;window.__nyamaBgTimer=setInterval(function(){applyDoc(document);if(++n>12){clearInterval(window.__nyamaBgTimer);window.__nyamaBgTimer=null;}},750);}" +
                "return '1';}catch(e){return '0';}})();";
        webView.evaluateJavascript(js, null);
    }

    private void loadPlayer() {
        String path = "series".equals(type) ? "tv" : "movie";
        // /tv/{id} retains StreamIMDB's provider-owned season and episode selector.
        webView.loadUrl(PLAYER_BASE + path + "/" + Uri.encode(id));
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

    private void moveNativeControl(int delta) {
        if (controlButtons.isEmpty()) return;
        int focused = -1;
        for (int i = 0; i < controlButtons.size(); i++) {
            if (controlButtons.get(i).hasFocus()) { focused = i; break; }
        }
        if (focused >= 0) controlIndex = focused;
        controlIndex = (controlIndex + delta + controlButtons.size()) % controlButtons.size();
        Button target = controlButtons.get(controlIndex);
        target.requestFocus();
        scheduleControlsHide();
    }

    private void activateNativeControl() {
        if (controlButtons.isEmpty()) return;
        int focused = -1;
        for (int i = 0; i < controlButtons.size(); i++) {
            if (controlButtons.get(i).hasFocus()) { focused = i; break; }
        }
        if (focused >= 0) controlIndex = focused;
        controlButtons.get(Math.max(0, Math.min(controlIndex, controlButtons.size() - 1))).performClick();
    }





    /**
     * Android-TV Movies/Series control without an external Nyama+ control bar.
     * The selected provider stays in the existing WebView. We first control an accessible HTML5
     * video (including same-origin nested frames); otherwise the provider WebView receives the
     * native media/DPAD key as fallback.
     */
    private void toggleTvPlayback(int fallbackKeyCode) {
        if (!tvMode || webView == null) return;
        String js = "(function(){try{" +
                "function docs(d,a){a.push(d);var f=d.querySelectorAll('iframe');for(var i=0;i<f.length;i++){try{if(f[i].contentDocument)docs(f[i].contentDocument,a);}catch(e){}}return a;}" +
                "var ds=docs(document,[]),best=null,area=-1;for(var j=0;j<ds.length;j++){var vs=ds[j].querySelectorAll('video');for(var k=0;k<vs.length;k++){var v=vs[k],r=v.getBoundingClientRect(),a=Math.max(0,r.width)*Math.max(0,r.height);if(a>area){area=a;best=v;}}}" +
                "if(!best)return '0';if(best.paused||best.ended){var p=best.play();if(p&&p.catch)p.catch(function(){});}else{best.pause();}return '1';" +
                "}catch(e){return '0';}})();";
        webView.evaluateJavascript(js, result -> {
            if (result == null || !result.contains("1")) {
                // A cross-origin frame hides its <video> from injected JavaScript. Focus the actual
                // player frame, then deliver the same physical OK/ENTER key that Android reported.
                sendNativeKeyToPlayer(fallbackKeyCode);
            }
        });
    }

    private void seekTvPlayback(int seconds) {
        if (!tvMode || webView == null) return;
        String js = "(function(){try{" +
                "function docs(d,a){a.push(d);var f=d.querySelectorAll('iframe');for(var i=0;i<f.length;i++){try{if(f[i].contentDocument)docs(f[i].contentDocument,a);}catch(e){}}return a;}" +
                "var ds=docs(document,[]),best=null,area=-1;for(var j=0;j<ds.length;j++){var vs=ds[j].querySelectorAll('video');for(var k=0;k<vs.length;k++){var v=vs[k],r=v.getBoundingClientRect(),a=Math.max(0,r.width)*Math.max(0,r.height);if(a>area){area=a;best=v;}}}" +
                "if(!best)return '0';var d=isFinite(best.duration)?best.duration:1e15;best.currentTime=Math.max(0,Math.min(d,(best.currentTime||0)+(" + seconds + ")));return '1';" +
                "}catch(e){return '0';}})();";
        webView.evaluateJavascript(js, result -> {
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
        target.requestFocus();
        Runnable dispatch = () -> {
            long now = android.os.SystemClock.uptimeMillis();
            target.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            target.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        };
        if (target != webView) {
            dispatch.run();
            return;
        }
        // Calling focus() on a cross-origin iframe element is permitted even though reading its
        // document is not. This makes the provider, rather than an unrelated outer-page control,
        // receive the real Android DPAD/ENTER fallback event.
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
        if (webView == null) return;

        if (closeArmed) {
            clearBackArm();
            resumeTvWhenClosing = true;
            finish();
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
                // TV Movies/Series intentionally use only OK for play/pause and LEFT/RIGHT for seek.
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (repeat == 0) seekTvPlayback(-10); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (repeat == 0) seekTvPlayback(10); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (repeat == 0) toggleTvPlayback(keyCode); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (repeat == 0) toggleTvPlayback(keyCode); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                sendNativeKeyToPlayer(KeyEvent.KEYCODE_MEDIA_PLAY); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                sendNativeKeyToPlayer(KeyEvent.KEYCODE_MEDIA_PAUSE); return true;
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
        if (!tvMode || event == null || !isTvRemoteKey(event.getKeyCode())) {
            return super.dispatchKeyEvent(event);
        }
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
            main.postDelayed(hideControls, CONTROLS_HIDE_MS);
        } else if (webView != null) {
            webView.requestFocus();
        }
    }

    private void scheduleControlsHide() {
        main.removeCallbacks(hideControls);
        if (remoteControls != null && remoteControls.getVisibility() == View.VISIBLE) {
            main.postDelayed(hideControls, CONTROLS_HIDE_MS);
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
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (tvMode) { handleTvBackPressed(); return; }
        if (remoteControls != null && remoteControls.getVisibility() == View.VISIBLE) { setControlsVisible(false, false); return; }
        if (providerMenuMode) { escapeProviderMenu(); return; }
        if (customView != null) { hideCustomView(); return; }
        tryDismissPlayerOverlayThenMaybeClose();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(clearCloseArm);
        main.removeCallbacks(hideControls);
        hideCustomView();
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            ViewParentSafe.remove(webView);
            webView.destroy();
            webView = null;
        }
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
