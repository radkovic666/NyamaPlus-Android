package fun.nyama.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small self-update helper for the privately distributed Nyama+ APK.
 * Android always remains in control of installation: the app downloads and
 * validates the candidate APK, then hands it to the system package installer.
 */
public final class UpdateManager {
    public static final String UPDATE_URL = "https://nyama.fun/nyamaplus.apk";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean busy;
    private File pendingApk;
    private boolean waitingForInstallPermission;
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;

    public UpdateManager(Activity activity) {
        this.activity = activity;
    }

    public void destroy() {
        io.shutdownNow();
        dismissProgress();
    }

    public void checkForUpdate() {
        if (busy) return;
        busy = true;
        showProgress();
        io.execute(() -> {
            try {
                File updateDir = new File(activity.getCacheDir(), "updates");
                if (!updateDir.exists() && !updateDir.mkdirs()) {
                    throw new IllegalStateException("Cannot create update directory");
                }
                File partial = new File(updateDir, "nyamaplus.apk.part");
                File apk = new File(updateDir, "nyamaplus.apk");
                if (partial.exists()) partial.delete();

                HttpURLConnection connection = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setUseCaches(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(45000);
                connection.setRequestProperty("User-Agent", DeviceIdentity.userAgent(activity));
                connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
                connection.setRequestProperty("Pragma", "no-cache");
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream,*/*");
                connection.connect();
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);

                long total = connection.getContentLengthLong();
                long max = 300L * 1024L * 1024L;
                if (total > max) throw new IllegalStateException("Update is unexpectedly large");

                long read = 0;
                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        read += n;
                        if (read > max) throw new IllegalStateException("Update is unexpectedly large");
                        out.write(buffer, 0, n);
                    }
                    out.flush();
                } finally {
                    connection.disconnect();
                }

                if (read < 1024) throw new IllegalStateException("Downloaded APK is empty");
                if (apk.exists() && !apk.delete()) throw new IllegalStateException("Cannot replace old update file");
                if (!partial.renameTo(apk)) throw new IllegalStateException("Cannot finalize downloaded APK");

                PackageManager pm = activity.getPackageManager();
                PackageInfo candidate = getArchiveInfo(pm, apk);
                if (candidate == null || candidate.applicationInfo == null) {
                    apk.delete();
                    throw new IllegalStateException("Downloaded file is not a valid APK");
                }
                if (!activity.getPackageName().equals(candidate.packageName)) {
                    apk.delete();
                    throw new IllegalStateException("APK package name does not match Nyama+");
                }

                long candidateCode = longVersion(candidate);
                PackageInfo installed = getInstalledInfo(pm, activity.getPackageName());
                long installedCode = longVersion(installed);
                String candidateName = candidate.versionName == null ? Long.toString(candidateCode) : candidate.versionName;
                String installedName = installed.versionName == null ? Long.toString(installedCode) : installed.versionName;

                if (candidateCode <= installedCode) {
                    apk.delete();
                    main.post(() -> {
                        finishBusy();
                        new AlertDialog.Builder(activity)
                                .setTitle(activity.getString(R.string.update_up_to_date_title))
                                .setMessage(activity.getString(R.string.update_up_to_date_message, installedName))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    });
                    return;
                }

                pendingApk = apk;
                main.post(() -> {
                    finishBusy();
                    new AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.update_available_title))
                            .setMessage(activity.getString(R.string.update_available_message, installedName, candidateName))
                            .setPositiveButton(R.string.install_update, (d, which) -> requestInstall())
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                });
            } catch (Exception e) {
                main.post(() -> {
                    finishBusy();
                    Toast.makeText(activity, activity.getString(R.string.update_failed, safeMessage(e)), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    public void onResume() {
        if (!waitingForInstallPermission || pendingApk == null) return;
        if (Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls()) {
            waitingForInstallPermission = false;
            launchInstaller();
        }
    }

    private void requestInstall() {
        if (pendingApk == null || !pendingApk.exists()) {
            Toast.makeText(activity, R.string.update_file_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            waitingForInstallPermission = true;
            try {
                Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                suppressHomeExitForExternalFlow();
                activity.startActivity(permission);
                Toast.makeText(activity, R.string.allow_updates_from_nyama_tv, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                waitingForInstallPermission = false;
                launchInstaller();
            }
            return;
        }
        launchInstaller();
    }

    private void launchInstaller() {
        File apk = pendingApk;
        if (apk == null || !apk.exists()) return;
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".updates", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.setClipData(ClipData.newRawUri("Nyama+ update", uri));
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            suppressHomeExitForExternalFlow();
            activity.startActivity(install);
        } catch (Exception e) {
            Toast.makeText(activity, activity.getString(R.string.update_install_failed, safeMessage(e)), Toast.LENGTH_LONG).show();
        }
    }


    private void suppressHomeExitForExternalFlow() {
        if (activity instanceof SettingsActivity) {
            ((SettingsActivity) activity).suppressNextUserLeaveHintOnce();
        }
    }

    private void showProgress() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        progressText = new TextView(activity);
        progressText.setText(R.string.update_downloading);
        progressText.setTextSize(17);
        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setMax(100);
        box.addView(progressText, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, Math.round(18 * activity.getResources().getDisplayMetrics().density));
        bar.topMargin = pad / 2;
        box.addView(progressBar, bar);
        progressDialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.check_for_updates)
                .setView(box)
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void finishBusy() {
        busy = false;
        dismissProgress();
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            try { progressDialog.dismiss(); } catch (Exception ignored) {}
        }
        progressDialog = null;
        progressBar = null;
        progressText = null;
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo getArchiveInfo(PackageManager pm, File apk) {
        if (Build.VERSION.SDK_INT >= 33) {
            return pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.PackageInfoFlags.of(0));
        }
        return pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo getInstalledInfo(PackageManager pm, String packageName) throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= 33) {
            return pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0));
        }
        return pm.getPackageInfo(packageName, 0);
    }

    @SuppressWarnings("deprecation")
    private static long longVersion(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
        return info.versionCode;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
