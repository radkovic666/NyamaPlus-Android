package fun.nyama.tv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Non-interactive TV artwork layer. Hosts place their existing foreground views above this layer. */
final class TvCinematicBackground extends FrameLayout {
    static final long CROSSFADE_MS = 350L;
    private final TvArtworkLoader loader;
    private final TvArtworkLoader fallbackLoader;
    private final ImageView[] images = new ImageView[2];
    private int visible;
    private boolean fading;
    private boolean released;
    private String fallbackUrl = "";
    private boolean fallbackPortrait;
    private boolean fallbackLoading;
    private boolean fallbackRequestPending;
    private boolean deferredShow;
    private String deferredUrl;
    private boolean deferredPortrait;
    private String requested = "";
    private String displayed = "";
    private Bitmap fallbackBitmap;
    private Bitmap pendingBitmap;
    private String pendingKey;
    private long generation;

    TvCinematicBackground(Context context) {
        this(context, new TvArtworkLoader(context), new TvArtworkLoader(context));
    }

    TvCinematicBackground(Context context, TvArtworkLoader loader, TvArtworkLoader fallbackLoader) {
        super(context);
        this.loader = loader;
        this.fallbackLoader = fallbackLoader;
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        setBackgroundColor(Color.rgb(13, 17, 24));
        for (int i = 0; i < images.length; i++) {
            images[i] = new ImageView(context);
            images[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            images[i].setFocusable(false);
            images[i].setClickable(false);
            addView(images[i], new LayoutParams(-1, -1));
        }
        View leftScrim = new View(context);
        leftScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xd90a1019, 0x8c0a1019, 0x580a1019}));
        leftScrim.setFocusable(false);
        addView(leftScrim, new LayoutParams(-1, -1));
        View bottomScrim = new View(context);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x300a1019, 0x780a1019, 0xe80a1019}));
        bottomScrim.setFocusable(false);
        addView(bottomScrim, new LayoutParams(-1, -1));
    }

    void setFallback(String url, boolean portrait) {
        fallbackUrl = TvArtworkLoader.safeUrl(url);
        fallbackPortrait = portrait;
        fallbackBitmap = null;
        fallbackRequestPending = true;
        if (!isAttachedToWindow()) return;
        loadFallback();
    }

    private void loadFallback() {
        fallbackRequestPending = false;
        fallbackLoading = !fallbackUrl.isEmpty();
        String expected = key(fallbackUrl, fallbackPortrait);
        fallbackLoader.load(fallbackUrl, fallbackPortrait, bitmap -> {
            if (released || !expected.equals(key(fallbackUrl, fallbackPortrait))) return;
            fallbackBitmap = bitmap;
            fallbackLoading = false;
            if (expected.equals(requested)) {
                if (bitmap != null) display(bitmap, expected);
                else displayNeutral();
            }
        });
    }

    void showFallback() { show(fallbackUrl, fallbackPortrait); }

    void show(String url, boolean portrait) {
        if (released) return;
        String validated = TvArtworkLoader.safeUrl(url);
        if (validated.isEmpty() && !fallbackUrl.isEmpty()) {
            validated = fallbackUrl;
            portrait = fallbackPortrait;
        }
        String next = key(validated, portrait);
        if (!isAttachedToWindow()) {
            deferredShow = true;
            deferredUrl = validated;
            deferredPortrait = portrait;
            requested = next;
            return;
        }
        if (next.equals(requested)) return;
        requested = next;
        pendingBitmap = null;
        pendingKey = null;
        long request = ++generation;
        if (next.equals(key(fallbackUrl, fallbackPortrait))) {
            loader.cancelPending();
            if (fallbackBitmap != null) display(fallbackBitmap, next);
            else if (!fallbackLoading) displayNeutral();
            return;
        }
        loader.load(validated, portrait, bitmap -> {
            if (released || request != generation) return;
            if (bitmap != null) display(bitmap, next);
            else if (!next.equals(key(fallbackUrl, fallbackPortrait))) showFallback();
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (released) return;
        if (fallbackRequestPending) loadFallback();
        if (deferredShow) {
            deferredShow = false;
            requested = "";
            show(deferredUrl, deferredPortrait);
        }
    }

    String currentRequestedUrl() {
        int split = requested.indexOf('|');
        return split < 0 ? "" : requested.substring(split + 1);
    }

    private void displayNeutral() {
        // Failed/missing series posters use the normal themed surface, never an unrelated episode.
        Bitmap neutral = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565);
        neutral.eraseColor(Color.rgb(13, 17, 24));
        display(neutral, "neutral|" + fallbackUrl);
    }

    private static String key(String url, boolean portrait) { return (portrait ? "poster|" : "still|") + url; }

    private void display(Bitmap bitmap, String key) {
        if (released || bitmap == null || key.equals(displayed)) return;
        if (fading) {
            pendingBitmap = bitmap;
            pendingKey = key;
            return;
        }
        int next = 1 - visible;
        ImageView incoming = images[next];
        ImageView outgoing = images[visible];
        incoming.setImageBitmap(bitmap);
        incoming.setAlpha(0f);
        incoming.setVisibility(VISIBLE);
        // Only the image is reordered: gradient scrims and the host's controls remain above it.
        removeView(incoming);
        addView(incoming, 1, new LayoutParams(-1, -1));
        displayed = key;
        fading = true;
        incoming.animate().alpha(1f).setDuration(CROSSFADE_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        if (released) return;
                        outgoing.setImageDrawable(null);
                        outgoing.setVisibility(INVISIBLE);
                        visible = next;
                        fading = false;
                        Bitmap bitmap = pendingBitmap;
                        String pending = pendingKey;
                        pendingBitmap = null;
                        pendingKey = null;
                        if (bitmap != null && pending != null && (pending.equals(requested) || (pending.equals("neutral|" + fallbackUrl)
                                && requested.equals(key(fallbackUrl, fallbackPortrait))))) display(bitmap, pending);
                    }
                }).start();
    }

    void release() {
        if (released) return;
        released = true;
        generation++;
        loader.release();
        fallbackLoader.release();
        fallbackBitmap = null;
        pendingBitmap = null;
        pendingKey = null;
        for (ImageView image : images) {
            image.animate().setListener(null);
            image.animate().cancel();
            image.setImageDrawable(null);
        }
    }
}
