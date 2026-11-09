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

import java.io.ByteArrayOutputStream;
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
                            x.notes = androidNotes(release.optString("body", ""), currentVersion(app));
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

    /**
     * 下载 APK：所有镜像**同时**开抢，谁先下完并通过校验就用谁。
     * 以前是一个镜像排队等 10 秒连接超时再换下一个，镜像一慢就像卡住了。
     */
    public static void download(Context context, Info info, ProgressCallback progress,
                                DoneCallback done) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            final File out = new File(app.getCacheDir(), "WaterValve-" + info.version + ".apk");
            if (out.exists() && !out.delete()) {
                MAIN.post(() -> done.done(null, "无法清理旧缓存"));
                return;
            }
            String[] all = {
                    "https://gh-proxy.com/" + info.url,
                    "https://ghfast.top/" + info.url,
                    "https://ghproxy.net/" + info.url,
                    info.url
            };
            java.util.List<String> list = new java.util.ArrayList<>();
            for (String u : all) {
                if (u != null && !u.trim().isEmpty()) list.add(u);
            }
            if (list.isEmpty()) {
                MAIN.post(() -> done.done(null, "没有可用的下载地址"));
                return;
            }
            if (progress != null) MAIN.post(() -> progress.progress(-1, 0, info.size));

            final java.util.concurrent.atomic.AtomicLong best =
                    new java.util.concurrent.atomic.AtomicLong(0);
            final java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(list.size());
            final java.util.concurrent.CompletionService<File> cs =
                    new java.util.concurrent.ExecutorCompletionService<>(pool);
            final java.util.List<java.util.concurrent.Future<File>> futures =
                    new java.util.ArrayList<>();

            for (int i = 0; i < list.size(); i++) {
                final String url = list.get(i);
                final File tmp = new File(app.getCacheDir(),
                        "WaterValve-" + info.version + "-" + i + ".part");
                futures.add(cs.submit(() -> {
                    try {
                        if (tmp.exists() && !tmp.delete()) {
                            throw new IOException("无法清理临时文件");
                        }
                        downloadOne(url, tmp, info, (pct, read, total) ->
                                best.accumulateAndGet(read, Math::max));
                        verify(tmp, info);
                        return tmp;
                    } catch (Exception e) {
                        tmp.delete();
                        throw e;
                    }
                }));
            }

            // 进度取「读得最多的那条线」，看起来就是最快镜像的进度
            final long total = info.size;
            final boolean[] running = {true};
            Thread reporter = new Thread(() -> {
                while (running[0]) {
                    long r = best.get();
                    if (progress != null && total > 0 && r > 0) {
                        int pct = (int) Math.min(100, r * 100 / total);
                        final long fr = r;
                        MAIN.post(() -> progress.progress(pct, fr, total));
                    }
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            reporter.setDaemon(true);
            reporter.start();

            String last = "下载失败";
            int remaining = list.size();
            File ok = null;
            while (remaining > 0) {
                java.util.concurrent.Future<File> f;
                try {
                    f = cs.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (f == null) continue;
                remaining--;
                try {
                    ok = f.get();
                    break;
                } catch (Exception e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    last = c.getClass().getSimpleName()
                            + (c.getMessage() == null ? "" : " " + c.getMessage());
                }
            }

            running[0] = false;
            reporter.interrupt();
            for (java.util.concurrent.Future<File> f : futures) f.cancel(true);
            pool.shutdownNow();

            if (ok != null && ok.exists() && ok.renameTo(out)) {
                final File result = out;
                if (progress != null) MAIN.post(() -> progress.progress(100, total, total));
                MAIN.post(() -> done.done(result, null));
                return;
            }
            final String fe = last;
            MAIN.post(() -> done.done(null, fe));
        });
    }

    private static void downloadOne(String url, File out, Info info,
                                    ProgressCallback progress) throws Exception {
        HttpURLConnection c = null;
        String current = url;
        for (int hop = 0; ; hop++) {
            if (hop > 6) throw new IOException("重定向次数过多");
            c = (HttpURLConnection) new URL(current).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "WaterValve-Android");
            c.setRequestProperty("Accept", "application/octet-stream");
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("重定向缺少 Location");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (code >= 400) throw new IOException("HTTP " + code);
            break;
        }
        long total = c.getContentLengthLong();
        if (total <= 0) total = info.size;
        final long ft = total;
        if (progress != null) MAIN.post(() -> progress.progress(-1, 0, ft));
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
                        final long fr = read;
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
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                piFlags |= 0x02000000; // FLAG_MUTABLE
            }
            PendingIntent pending = PendingIntent.getBroadcast(c, 100, intent, piFlags);
            s.commit(pending.getIntentSender());
        }
    }

    /**
     * 从 release body 里收集「比当前安装版本新」的所有 Android 小节，按版本倒序拼起来。
     *
     * 例：手机上是 0.7.3，最新 0.8.3 → 0.8.3 / 0.8.2 / … / 0.7.6 全部列出来，
     * 而不是只显示 0.8.3 那几条。每段前面带一行「## v0.8.3」，弹窗里渲染成小节标题。
     */
    private static String androidNotes(String body, String current) {
        if (body == null) return "";
        String[] lines = body.replace("\r", "").split("\n");
        java.util.ArrayList<String> vers = new java.util.ArrayList<>();
        java.util.ArrayList<StringBuilder> texts = new java.util.ArrayList<>();
        StringBuilder cur = null;
        for (String raw : lines) {
            String t = raw.trim();
            if (t.startsWith("#")) {
                String bare = t.toLowerCase(Locale.ROOT).replaceAll("^#+\\s*", "");
                if (bare.startsWith("android")) {
                    cur = new StringBuilder();
                    vers.add(firstVersion(t));
                    texts.add(cur);
                } else {
                    cur = null;
                }
                continue;
            }
            if (cur == null || t.isEmpty() || t.startsWith("```")) continue;
            if (cur.length() > 1400) continue;
            // 缩进的二级条目保留层级，别和一级条目挤成一样
            boolean sub = raw.startsWith("  ") || raw.startsWith("\t");
            cur.append(t.replaceFirst("^[•·\\-*+]\\s*", sub ? "     · " : "• ")).append('\n');
        }

        // 按版本号倒序
        Integer[] order = new Integer[vers.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final java.util.List<String> vv = vers;
        java.util.Arrays.sort(order, (a, b) -> cmpVersion(vv.get(b), vv.get(a)));

        String now = current == null ? "" : current.trim();
        StringBuilder out = new StringBuilder();
        for (int i : order) {
            String v = vers.get(i);
            String text = texts.get(i).toString().trim();
            if (text.isEmpty()) continue;
            // 已经装过的版本不再列（拿不到当前版本时就全列）
            if (!now.isEmpty() && !v.isEmpty() && cmpVersion(v, now) <= 0) continue;
            if (out.length() > 0 && out.length() + text.length() > 2600) break;
            if (!v.isEmpty()) out.append("## v").append(v).append('\n');
            out.append(text).append('\n');
        }
        String s = out.toString().trim();
        if (!s.isEmpty()) return s;

        // 没有可比对的小节（例如说明里没写版本）：退回正文前 360 字
        s = body.trim();
        return s.length() > 360 ? s.substring(0, 360) + "…" : s;
    }

    private static String firstVersion(String s) {
        Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+){1,3})").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static int cmpVersion(String a, String b) {
        String[] x = a == null || a.isEmpty() ? new String[0] : a.split("\\.");
        String[] y = b == null || b.isEmpty() ? new String[0] : b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? intOf(x[i]) : 0;
            int yi = i < y.length ? intOf(y[i]) : 0;
            if (xi != yi) return xi > yi ? 1 : -1;
        }
        return 0;
    }

    private static int intOf(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        // 必须整段解码；逐块 new String 会把跨 8192 边界的 UTF-8 汉字拆成两个 �
        return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
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
