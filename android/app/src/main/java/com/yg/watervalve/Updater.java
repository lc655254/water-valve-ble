package com.yg.watervalve;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/** GitHub Release 检查、镜像下载、SHA256 校验、系统安装器。 */
public final class Updater {
    public interface CheckCallback { void done(Info info, String error); }
    public interface ProgressCallback { void progress(int percent, long bytes, long total); }
    public interface DoneCallback { void done(File file, String error); }

    public static final class Info {
        public String version = "";
        public String url = "";
        public String digest = "";
        public String notes = "";
        public long size;
        public boolean hasUpdate;
    }

    private static final String API =
            "https://api.github.com/repos/lc655254/water-valve-ble/releases/latest";
    private static final Pattern APK_NAME =
            Pattern.compile("(?i)WaterValveBLE_Android_v([0-9.]+)\\.apk");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Updater() {}

    public static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    public static void check(Context context, CheckCallback cb) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            Info info = null;
            String error = null;
            try {
                JSONObject release = getJson(API);
                JSONArray assets = release.optJSONArray("assets");
                Info best = null;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a == null) continue;
                        String name = a.optString("name", "");
                        Matcher m = APK_NAME.matcher(name);
                        if (!m.find()) continue;
                        String v = m.group(1);
                        if (best == null || compare(v, best.version) > 0) {
                            Info x = new Info();
                            x.version = v;
                            x.url = a.optString("browser_download_url", "");
                            x.digest = a.optString("digest", "");
                            x.size = a.optLong("size", 0);
                            x.notes = release.optString("body", "");
                            best = x;
                        }
                    }
                }
                if (best == null) {
                    error = "Release 里没有 Android APK";
                } else {
                    best.hasUpdate = compare(best.version, currentVersion(app)) > 0;
                    info = best;
                }
            } catch (Exception e) {
                error = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage());
            }
            final Info fi = info;
            final String fe = error;
            MAIN.post(() -> cb.done(fi, fe));
        });
    }

    public static void download(Context context, Info info, ProgressCallback progress,
                                DoneCallback done) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            File out = new File(app.getCacheDir(), "WaterValve-" + info.version + ".apk");
            if (out.exists() && !out.delete()) {
                MAIN.post(() -> done.done(null, "无法清理旧缓存"));
                return;
            }
            String[] mirrors = {
                    info.url,
                    "https://gh-proxy.com/" + info.url,
                    "https://ghproxy.net/" + info.url,
                    "https://ghfast.top/" + info.url
            };
            String last = "下载失败";
            for (String url : mirrors) {
                if (url == null || url.isEmpty()) continue;
                try {
                    downloadOne(url, out, info, progress);
                    verify(out, info);
                    File result = out;
                    MAIN.post(() -> done.done(result, null));
                    return;
                } catch (Exception e) {
                    last = e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " " + e.getMessage());
                    out.delete();
                    MAIN.post(() -> progress.progress(0, 0, info.size));
                }
            }
            final String fe = last;
            MAIN.post(() -> done.done(null, fe));
        });
    }

    private static void downloadOne(String url, File out, Info info,
                                    ProgressCallback progress) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "WaterValve-Android");
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);
        long total = c.getContentLengthLong();
        if (total <= 0) total = info.size;
        try (InputStream in = c.getInputStream();
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[16384];
            long read = 0;
            int lastPct = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                read += n;
                if (total > 0) {
                    int pct = (int) Math.min(100, read * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        final int fp = pct;
                        final long fr = read, ft = total;
                        if (progress != null) MAIN.post(() -> progress.progress(fp, fr, ft));
                    }
                }
            }
            os.flush();
        } finally {
            c.disconnect();
        }
    }

    private static void verify(File f, Info info) throws Exception {
        if (info.size > 0 && f.length() != info.size) {
            throw new IOException("大小不一致 " + f.length() + "/" + info.size);
        }
        String d = info.digest == null ? "" : info.digest.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("sha256:")) d = d.substring(7);
        if (!d.isEmpty()) {
            String actual = sha256(f);
            if (!actual.equalsIgnoreCase(d)) throw new IOException("SHA256 不匹配");
        }
    }

    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            return c.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    public static void openInstallSettings(Activity a) {
        if (Build.VERSION.SDK_INT >= 26) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
        }
    }

    public static void install(Context c, File apk) throws Exception {
        if (!canInstall(c)) throw new SecurityException("请先允许安装未知应用");
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream os = s.openWrite("base.apk", 0, apk.length());
                 FileInputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
                s.fsync(os);
            }
            Intent intent = new Intent(c, InstallReceiver.class)
                    .setAction(InstallReceiver.ACTION_STATUS);
            PendingIntent pending = PendingIntent.getBroadcast(c, 100, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            s.commit(pending.getIntentSender());
        }
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "WaterValve-Android");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("GitHub HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
        return new JSONObject(sb.toString());
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder b = new StringBuilder();
        for (byte x : md.digest()) b.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
        return b.toString();
    }

    public static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? parseInt(x[i]) : 0;
            int yi = i < y.length ? parseInt(y[i]) : 0;
            if (xi != yi) return xi > yi ? 1 : -1;
        }
        return 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
