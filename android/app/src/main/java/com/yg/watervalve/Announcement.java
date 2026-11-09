package com.yg.watervalve;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** GitHub 托管的公告：docs/announcement.json + docs/announcement/ 图片。 */
public final class Announcement {
    private static final String RAW =
            "https://raw.githubusercontent.com/lc655254/water-valve-ble/main/docs/announcement.json";
    private static final String PREF = "water_valve";
    private static final String K_SEEN = "announcement_seen";
    private static final String K_IMG_FP = "announcement_img_fp";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService IMG = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Announcement() {}

    public interface Callback {
        void done(Info info, String error);
    }

    public static final class Info {
        public String id = "";
        public String title = "公告";
        public String body = "";
        public String button = "我知道了";
        public String link = "";
        public String linkText = "查看详情";
        public boolean once = true;
        public final List<String> images = new ArrayList<>();
    }

    public static void check(Context context, Callback cb) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            String text = fetch(RAW);
            String error = null;
            if (text == null || text.trim().isEmpty()) {
                text = readCache(app);
                error = text == null ? "公告读取失败" : null;
            }
            Info info = null;
            if (text != null) {
                try {
                    info = parse(app, new JSONObject(text));
                    writeCache(app, text);
                    syncImageCache(app, info);
                    prefetchImages(app, info);
                } catch (Exception e) {
                    error = e.getClass().getSimpleName();
                }
            }
            final Info fi = info;
            final String fe = error;
            MAIN.post(() -> cb.done(fi, fe));
        });
    }

    public static boolean seen(Context context, String id) {
        if (id == null || id.isEmpty()) return false;
        return prefs(context).getString(K_SEEN, "").equals(id);
    }

    public static void markSeen(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        prefs(context).edit().putString(K_SEEN, id).apply();
    }

    private static Info parse(Context c, JSONObject o) throws Exception {
        if (o.optBoolean("enabled", false) == false) return null;
        int version = versionCode(c);
        int min = o.optInt("minVersionCode", 0);
        int max = o.optInt("maxVersionCode", Integer.MAX_VALUE);
        if (version < min || version > max) return null;
        Info info = new Info();
        info.id = o.optString("id", "").trim();
        if (info.id.isEmpty()) return null;
        info.title = o.optString("title", "公告").trim();
        info.body = o.optString("body", "");
        info.button = o.optString("button", "我知道了").trim();
        if (info.button.isEmpty()) info.button = "我知道了";
        info.link = o.optString("link", "").trim();
        info.linkText = o.optString("linkText", "查看详情").trim();
        info.once = o.optBoolean("once", true);
        JSONArray arr = o.optJSONArray("images");
        if (arr != null) {
            for (int i = 0; i < arr.length() && info.images.size() < 4; i++) {
                String u = arr.optString(i, "").trim();
                if (u.startsWith("https://")) info.images.add(u);
            }
        }
        return info;
    }

    private static int versionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String fetch(String url) {
        // 带时间戳绕过 GitHub raw / gh-proxy 的 CDN 缓存
        String fresh = url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis();
        for (String u : rawCandidates(fresh)) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(6000);
                c.setRequestProperty("User-Agent", "WaterValve-Android");
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code != 200) {
                    DebugLog.line("http", "GET " + u + " | HTTP " + code + " | 公告镜像");
                    c.disconnect();
                    continue;
                }
                String s = readAll(c.getInputStream());
                DebugLog.line("http", "GET " + u + " | HTTP 200 | 公告 " + (s == null ? 0 : s.length()) + " 字符");
                c.disconnect();
                if (s != null && !s.trim().isEmpty()) return s;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 同一条 raw 链接的多条线路：jsDelivr 在国内通常最快，其次 GitHub 代理，最后直连。
     * raw.githubusercontent.com/u/r/branch/path → cdn.jsdelivr.net/gh/u/r@branch/path
     */
    private static List<String> rawCandidates(String url) {
        List<String> out = new ArrayList<>();
        String prefix = "https://raw.githubusercontent.com/";
        if (url.startsWith(prefix)) {
            String[] parts = url.substring(prefix.length()).split("/", 4);
            if (parts.length == 4) {
                for (String host : new String[]{"cdn.jsdelivr.net", "fastly.jsdelivr.net",
                        "gcore.jsdelivr.net"}) {
                    out.add("https://" + host + "/gh/" + parts[0] + "/" + parts[1]
                            + "@" + parts[2] + "/" + parts[3]);
                }
            }
        }
        out.add("https://gh-proxy.com/" + url);
        out.add("https://ghfast.top/" + url);
        out.add("https://ghproxy.net/" + url);
        out.add(url);
        return out;
    }

    public static void loadImage(Context context, String url, ImageView view) {
        loadImage(context, url, view, null);
    }

    public static void loadImage(Context context, String url, ImageView view, View placeholder) {
        if (url == null || url.isEmpty() || view == null) return;
        final Context app = context.getApplicationContext();
        IMG.execute(() -> {
            File f = imageCacheFile(app, url);
            byte[] data = readFile(f);
            if (data == null || data.length == 0) {
                data = fetchImageBytes(url);
                if (data != null && data.length > 0) writeFile(f, data);
            }
            Bitmap bmp = decodeImage(data);
            if (bmp == null && f.exists()) {
                // 本地缓存损坏：删掉再拉一次
                f.delete();
                data = fetchImageBytes(url);
                if (data != null && data.length > 0) writeFile(f, data);
                bmp = decodeImage(data);
            }
            final Bitmap b = bmp;
            MAIN.post(() -> {
                if (b != null) {
                    view.setImageBitmap(b);
                    view.setVisibility(View.VISIBLE);
                    if (placeholder != null) placeholder.setVisibility(View.GONE);
                } else {
                    view.setVisibility(View.GONE);
                    if (placeholder != null) {
                        ((TextView) placeholder).setText("图片没加载出来，退出重进再试");
                    }
                }
            });
        });
    }

    private static void syncImageCache(Context c, Info info) {
        if (info == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(info.id).append('\n').append(info.title).append('\n').append(info.body);
        for (String u : info.images) sb.append('\n').append(u);
        String fp = sb.toString();
        SharedPreferences p = prefs(c);
        if (!fp.equals(p.getString(K_IMG_FP, ""))) {
            deleteRecursive(new File(c.getCacheDir(), "ann_img"));
            p.edit().putString(K_IMG_FP, fp).apply();
        }
    }

    private static File imageCacheFile(Context c, String url) {
        File dir = new File(c.getCacheDir(), "ann_img");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, sha256(url) + ".img");
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** 多线路并发抢第一份：谁先回来用谁，单条线路超时 4s 不会拖死整张图。 */
    private static byte[] fetchImageBytes(String url) {
        List<String> cands = rawCandidates(url);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, cands.size()));
        CompletionService<byte[]> cs = new ExecutorCompletionService<>(pool);
        for (String u : cands) cs.submit(() -> fetchImageOne(u));
        byte[] best = null;
        long deadline = System.currentTimeMillis() + 12000;
        try {
            for (int i = 0; i < cands.size(); i++) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                Future<byte[]> f = cs.poll(left, TimeUnit.MILLISECONDS);
                if (f == null) break;
                try {
                    byte[] d = f.get();
                    if (d != null && d.length > 0) {
                        best = d;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return best;
    }

    private static byte[] fetchImageOne(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "WaterValve-Android");
            c.setRequestProperty("Accept", "image/*");
            int code = c.getResponseCode();
            if (code != 200) {
                DebugLog.line("http", "GET " + url + " | HTTP " + code + " | 公告图");
                return null;
            }
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                data = readBytes(in);
            }
            DebugLog.line("http", "GET " + url + " | HTTP 200 | 公告图 "
                    + (data == null ? 0 : data.length) + "B");
            if (data == null || data.length == 0 || data.length > 8 * 1024 * 1024) return null;
            return data;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 公告一解析出来就在后台把图拉进缓存，等用户看到弹窗时基本已经就位。 */
    private static void prefetchImages(Context c, Info info) {
        if (info == null) return;
        for (String u : info.images) {
            IMG.execute(() -> {
                File f = imageCacheFile(c, u);
                if (f.exists() && f.length() > 0) return;
                byte[] d = fetchImageBytes(u);
                if (d != null && d.length > 0) writeFile(f, d);
            });
        }
    }

    private static Bitmap decodeImage(byte[] data) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 1200) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    private static byte[] readFile(File f) {
        if (f == null || !f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return readBytes(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(File f, byte[] data) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        f.delete();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return null;
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static File cacheFile(Context c) {
        return new File(c.getCacheDir(), "announcement.json");
    }

    private static void writeCache(Context c, String text) {
        try (FileOutputStream out = new FileOutputStream(cacheFile(c))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String readCache(Context c) {
        File f = cacheFile(c);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
