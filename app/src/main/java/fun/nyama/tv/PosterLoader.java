package fun.nyama.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PosterLoader {
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private PosterLoader() {}

    public static void load(Context context, String imdbId, ImageView target) {
        if (imdbId == null || imdbId.isEmpty() || target == null) return;
        String key = new Config(context).pukankiBaseUrl() + "|" + imdbId;
        target.setTag(key);
        Bitmap cached = MEMORY.get(key);
        if (cached != null) { target.setImageBitmap(cached); return; }

        File dir = new File(context.getCacheDir(), "pukanki_posters");
        File file = new File(dir, imdbId.replaceAll("[^A-Za-z0-9._-]", "_") + ".jpg");
        IO.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (file.exists() && file.length() > 512) {
                    try (FileInputStream in = new FileInputStream(file)) { bitmap = BitmapFactory.decodeStream(in); }
                }
                if (bitmap == null) {
                    if (!dir.exists()) dir.mkdirs();
                    HttpURLConnection c = (HttpURLConnection) new URL(PukankiApi.posterUrl(context, imdbId)).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(10000);
                    c.setUseCaches(true);
                    c.setRequestProperty("User-Agent", DeviceIdentity.userAgent(context) + " NyamaPlus/1.0.10");
                    int code = c.getResponseCode();
                    if (code >= 200 && code < 300) {
                        File tmp = new File(file.getAbsolutePath() + ".tmp");
                        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[32768]; int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        }
                        if (tmp.length() > 512) {
                            if (file.exists()) file.delete();
                            tmp.renameTo(file);
                            try (FileInputStream in = new FileInputStream(file)) { bitmap = BitmapFactory.decodeStream(in); }
                        } else tmp.delete();
                    }
                    c.disconnect();
                }
            } catch (Exception ignored) {}
            if (bitmap != null) MEMORY.put(key, bitmap);
            Bitmap result = bitmap;
            MAIN.post(() -> {
                Object tag = target.getTag();
                if (key.equals(tag) && result != null) target.setImageBitmap(result);
            });
        });
    }
}
