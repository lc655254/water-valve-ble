package com.yg.watervalve;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 调试日志：把请求、蓝牙帧、结算过程全部落到文件里。
 *
 * 设计要点：
 * - 日志写在应用私有目录 files/debug/，关掉软件、重启手机都还在，只有卸载/清数据才会没；
 * - 单文件 2MB 自动换新文件，最多留 15 个（约 30MB），老文件自动淘汰；
 * - 每行立即 flush，闪退时也不丢最后几行；
 * - 关闭调试时不写文件，但内存里始终留最近 400 行，方便随时「复制最近日志」。
 */
public final class DebugLog {
    public static final String PREF = "water_valve";
    public static final String KEY = "debug_mode";

    private static final String DIR_NAME = "debug";
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int KEEP_FILES = 15;
    private static final int MEM_LINES = 400;
    private static final int EXPORT_LIMIT = 8 * 1024 * 1024;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "debug-log");
        t.setDaemon(true);
        return t;
    });

    private static Context app;
    private static File dir;
    private static volatile boolean on;
    private static OutputStream out;
    private static File cur;
    private static long curBytes;
    private static long written;

    private DebugLog() {}

    // ── 初始化 / 开关 ────────────────────────────────────────

    public static void init(Context context) {
        app = context.getApplicationContext();
        dir = new File(app.getFilesDir(), DIR_NAME);
        try {
            if (!dir.exists()) dir.mkdirs();
        } catch (Throwable ignored) {
        }
        on = prefs().getBoolean(KEY, false);
        installCrashHook();
        if (on) {
            line("boot", "进程启动 pid=" + Process.myPid() + "，调试日志已开启");
            header("进程启动");
        }
    }

    public static boolean isOn() {
        return on;
    }

    public static void setOn(Context context, boolean value) {
        if (app == null) init(context);
        on = value;
        prefs().edit().putBoolean(KEY, value).apply();
        if (value) {
            line("boot", "调试模式开启，开始记录请求 / 蓝牙帧 / 结算过程");
            header("调试开启");
        } else {
            line("boot", "调试模式关闭，停止写文件（已保存的日志保留）");
            IO.execute(DebugLog::closeQuietly);
        }
    }

    private static SharedPreferences prefs() {
        return app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static void installCrashHook() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                line("crash", "线程 " + t.getName() + " 崩溃：" + Log.getStackTraceString(e));
                flushSync();
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    // ── 写日志 ──────────────────────────────────────────────

    public static void line(String tag, String msg) {
        String text = msg == null ? "" : msg;
        String s = stamp() + " [" + tag + "] " + text;
        remember(s);
        if (!on) return;
        write(s);
    }

    /** 十六进制转储，蓝牙收发的原始字节用这个记。 */
    public static void hex(String tag, String label, byte[] data) {
        if (data == null) {
            line(tag, label + " <null>");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format(Locale.ROOT, "%02X ", b & 0xFF));
        line(tag, label + " (" + data.length + "B) " + sb.toString().trim());
    }

    /** 记录一次 HTTP 往返：请求、响应、耗时、异常。 */
    public static void http(String method, String url, String reqBody, int code,
                            String respBody, long ms, String note, Throwable err) {
        if (!on) return;
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url);
        sb.append(" | ").append(ms).append("ms");
        if (code > 0) sb.append(" | HTTP ").append(code);
        if (note != null && !note.isEmpty()) sb.append(" | ").append(note);
        line("http", sb.toString());
        if (reqBody != null && !reqBody.isEmpty()) line("http", "  → " + clamp(reqBody, 8000));
        if (respBody != null && !respBody.isEmpty()) line("http", "  ← " + clamp(respBody, 8000));
        if (err != null) line("http", "  ✗ " + err.getClass().getSimpleName() + ": " + err.getMessage()
                + "\n" + Log.getStackTraceString(err));
    }

    public static void exc(String tag, Throwable t) {
        if (t == null) return;
        line(tag, t.getClass().getSimpleName() + ": " + t.getMessage()
                + "\n" + Log.getStackTraceString(t));
    }

    /** 环境与账号信息，开启调试和每次启动都记一份，方便对照。 */
    public static void header(String why) {
        if (!on) return;
        StringBuilder sb = new StringBuilder();
        sb.append("==== ").append(why).append(" ====");
        sb.append("\n时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date()));
        sb.append("\n应用: ").append(appName()).append(" / versionName=").append(versionName())
                .append(" versionCode=").append(versionCode());
        sb.append("\n系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")");
        sb.append("\n机型: ").append(Build.BRAND).append(' ').append(Build.MODEL)
                .append(" / ").append(Build.DEVICE).append(" / ").append(Arrays.toString(Build.SUPPORTED_ABIS));
        sb.append("\n语言: ").append(Locale.getDefault()).append(" / 网络: ").append(netType());
        SharedPreferences p = prefs();
        String theme = app.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .getString("theme", "");
        sb.append("\n设置: theme=").append(theme)
                .append(" zero_rate=").append(p.getBoolean("zero_rate", false))
                .append(" debug=").append(on);
        String sess = p.getString("session", "");
        if (sess != null && !sess.isEmpty()) {
            Cloud.Session s = Cloud.Session.fromJson(sess);
            if (s != null) {
                sb.append("\n账号: ").append(s.telephone).append(" / userId=").append(s.userId)
                        .append(" / accountId=").append(s.accountId)
                        .append(" / projectId=").append(s.projectId)
                        .append(" / 余额=").append(s.balance);
            }
        }
        String crash = p.getString("last_crash", "");
        if (crash != null && !crash.isEmpty()) {
            sb.append("\n上次崩溃: ").append(clamp(crash, 4000));
        }
        line("boot", sb.toString());
    }

    private static String appName() {
        try {
            return app.getPackageManager().getApplicationLabel(app.getApplicationInfo()).toString();
        } catch (Throwable e) {
            return "?";
        }
    }

    private static String versionName() {
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable e) {
            return "?";
        }
    }

    private static long versionCode() {
        try {
            PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 当前网络类型：WiFi / 移动网络 / 无，结算异常时能看出是不是网络问题。 */
    public static String netType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "?";
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) return "无网络";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "移动网络";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "有线";
            return "其他";
        } catch (Throwable e) {
            return "?";
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA).format(new Date());
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…(共 " + s.length() + " 字符)";
    }

    private static void remember(String s) {
        synchronized (RECENT) {
            RECENT.addLast(s);
            while (RECENT.size() > MEM_LINES) RECENT.removeFirst();
        }
    }

    // ── 文件写入 ────────────────────────────────────────────

    private static void write(final String s) {
        final byte[] bytes = (s + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            IO.execute(() -> {
                synchronized (LOCK) {
                    try {
                        if (dir == null) return;
                        if (out == null) openLocked();
                        if (curBytes + bytes.length > MAX_FILE_BYTES) {
                            closeLocked();
                            openLocked();
                        }
                        out.write(bytes);
                        out.flush();
                        curBytes += bytes.length;
                        written++;
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void openLocked() throws Exception {
        if (!dir.exists()) dir.mkdirs();
        String name = "debug-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA)
                .format(new Date()) + ".log";
        File f = new File(dir, name);
        int i = 2;
        while (f.exists()) {
            f = new File(dir, name.replace(".log", "-" + i + ".log"));
            i++;
        }
        out = new BufferedOutputStream(new FileOutputStream(f, true), 16 * 1024);
        cur = f;
        curBytes = f.length();
        prune();
    }

    private static void closeLocked() {
        try {
            if (out != null) out.flush();
            if (out != null) out.close();
        } catch (Throwable ignored) {
        }
        out = null;
        cur = null;
        curBytes = 0;
    }

    private static void closeQuietly() {
        synchronized (LOCK) {
            closeLocked();
        }
    }

    private static void prune() {
        try {
            File[] files = dir.listFiles((d, n) -> n.startsWith("debug-") && n.endsWith(".log"));
            if (files == null || files.length <= KEEP_FILES) return;
            List<File> list = new ArrayList<>(Arrays.asList(files));
            list.sort(Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < list.size() - KEEP_FILES; i++) {
                //noinspection ResultOfMethodCallIgnored
                list.get(i).delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 崩溃前同步落盘，别等异步队列。 */
    public static void flushSync() {
        synchronized (LOCK) {
            try {
                if (out != null) out.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    // ── 导出 / 统计 / 清空 ──────────────────────────────────

    /** 全部日志合并成一段文本（给「导出日志」用）。 */
    public static String bundle(Context context) {
        if (app == null) init(context);
        StringBuilder sb = new StringBuilder();
        sb.append("# 蓝牙水库调试日志\n");
        sb.append("# 导出时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date())).append('\n');
        sb.append("# 说明: 每行格式为「时间 [模块] 内容」，http 为云端请求，ble 为蓝牙帧\n");
        File[] files = files();
        if (files.length == 0) {
            sb.append("\n(还没有日志文件：请先在设置里打开调试模式)\n");
            return sb.toString();
        }
        for (File f : files) {
            sb.append("\n===== ").append(f.getName()).append(" (").append(f.length()).append("B) =====\n");
            if (sb.length() > EXPORT_LIMIT) {
                sb.append("(日志过大，后面的文件省略)\n");
                break;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) {
                    sb.append(l).append('\n');
                    if (sb.length() > EXPORT_LIMIT) break;
                }
            } catch (Throwable e) {
                sb.append("(读取失败: ").append(e).append(")\n");
            }
        }
        return sb.toString();
    }

    /** 最近 400 行，直接贴聊天窗口用。 */
    public static String recentText() {
        StringBuilder sb = new StringBuilder();
        synchronized (RECENT) {
            for (String s : RECENT) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static File[] files() {
        if (dir == null) return new File[0];
        File[] files = dir.listFiles((d, n) -> n.startsWith("debug-") && n.endsWith(".log"));
        if (files == null) return new File[0];
        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort(Comparator.comparing(File::getName));
        return list.toArray(new File[0]);
    }

    /** 「12 个文件 · 3.4 MB」这种状态串；行数只算本次进程写入的，避免每次扫全部文件。 */
    public static String stats() {
        File[] files = files();
        long bytes = 0;
        for (File f : files) bytes += f.length();
        if (files.length == 0) return "还没有日志";
        return files.length + " 个文件 · " + String.format(Locale.CHINA, "%.1f", bytes / 1048576.0)
                + " MB · 本次已写 " + written + " 行";
    }

    public static void clear(Context context) {
        if (app == null) init(context);
        IO.execute(() -> {
            synchronized (LOCK) {
                closeLocked();
                for (File f : files()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                written = 0;
            }
            synchronized (RECENT) {
                RECENT.clear();
            }
            line("boot", "日志已清空");
        });
    }
}
