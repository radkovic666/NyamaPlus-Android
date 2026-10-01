package fun.nyama.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LogoRepository {
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(12 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount() / 1024; }
    };

    private LogoRepository() {}

    public static void load(Context context, String url, ImageView view) {
        view.setTag(url == null ? "" : url);
        if (url == null || url.trim().isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = MEMORY.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        IO.execute(() -> {
            Bitmap bitmap = loadBitmap(context, url.trim());
            if (bitmap != null) MEMORY.put(url, bitmap);
            MAIN.post(() -> {
                Object tag = view.getTag();
                if (url.equals(tag)) view.setImageBitmap(bitmap);
            });
        });
    }

    private static Bitmap loadBitmap(Context context, String source) {
        File cacheDir = new File(context.getCacheDir(), "logos");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        File file = new File(cacheDir, hash(source) + ".img");
        try {
            if (file.exists() && file.length() > 0) {
                try (InputStream in = new FileInputStream(file)) {
                    Bitmap b = BitmapFactory.decodeStream(in);
                    if (b != null) return b;
                }
            }
            HttpURLConnection c = (HttpURLConnection) new URL(source).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", DeviceIdentity.userAgent(context));
            c.setRequestProperty("Accept", "image/*,*/*;q=0.8");
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 FileOutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } finally {
                c.disconnect();
            }
            try (InputStream in = new FileInputStream(file)) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
    }

    private static String hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
