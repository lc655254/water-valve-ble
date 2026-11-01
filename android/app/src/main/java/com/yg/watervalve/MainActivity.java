package com.yg.watervalve;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements ValveManager.Listener {
    private ValveManager manager;
    private TextView sessionStatus, balance, deviceStatus, waterStatus, logView, headerStatus;
    private TextView statLastMain, statLastSub, statTotalMain, statTotalSub;
    private EditText phone, code;
    private Button btnSendCode, btnLogin, btnScan, btnQuery, btnWater, btnCopyLog;
    private TextView btnCheckUpdate;
    private LinearLayout deviceList, header, bottomBar;
    private ScrollView logScroll;
    private View statusBarScrim, root;
    private final StringBuilder logBuf = new StringBuilder();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private boolean running;
    private boolean connected;
    private ProgressDialog updateProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        manager = ValveManager.get(this);

        sessionStatus = findViewById(R.id.sessionStatus);
        balance = findViewById(R.id.balance);
        deviceStatus = findViewById(R.id.deviceStatus);
        waterStatus = findViewById(R.id.waterStatus);
        headerStatus = findViewById(R.id.headerStatus);
        statLastMain = findViewById(R.id.statLastMain);
        statLastSub = findViewById(R.id.statLastSub);
        statTotalMain = findViewById(R.id.statTotalMain);
        statTotalSub = findViewById(R.id.statTotalSub);
        logView = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        phone = findViewById(R.id.phone);
        code = findViewById(R.id.code);
        btnSendCode = findViewById(R.id.btnSendCode);
        btnLogin = findViewById(R.id.btnLogin);
        btnScan = findViewById(R.id.btnScan);
        btnQuery = findViewById(R.id.btnQuery);
        btnWater = findViewById(R.id.btnWater);
        btnCopyLog = findViewById(R.id.btnCopyLog);
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        deviceList = findViewById(R.id.deviceList);
        header = findViewById(R.id.header);
        bottomBar = findViewById(R.id.bottomBar);
        statusBarScrim = findViewById(R.id.statusBarScrim);
        root = findViewById(R.id.root);

        setupWindow();
        manager.setListener(this);

        phone.setText(manager.getPhone());
        running = manager.isRunning();
        connected = manager.isConnected();
        renderSession(manager.getSession());
        renderDevice();
        renderStats(manager.getStatsText());
        btnWater.setText(running ? "关水" : "开水");
        btnWater.setBackgroundResource(running
                ? R.drawable.bg_button_danger : R.drawable.bg_button_primary);
        waterStatus.setText(running ? "正在放水" : (connected ? "水表已连接" : "状态未知"));
        updateHeader();
        if (running) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (manager.hasOpenSession() && !manager.isConnected()) {
            ValveService.startRecover(this);
            manager.recoverIfOpen(false);
        }

        btnSendCode.setOnClickListener(v -> {
            String p = phone.getText().toString().trim();
            if (p.length() != 11) {
                toast("请输入 11 位手机号");
                return;
            }
            manager.savePhone(p);
            manager.sendSms(p);
        });
        btnLogin.setOnClickListener(v -> {
            String p = phone.getText().toString().trim();
            String c = code.getText().toString().trim();
            if (p.length() != 11 || c.isEmpty()) {
                toast("请输入手机号和验证码");
                return;
            }
            manager.savePhone(p);
            manager.login(p, c);
        });
        btnScan.setOnClickListener(v -> manager.scan());
        btnQuery.setOnClickListener(v -> manager.query());
        btnWater.setOnClickListener(v -> {
            if (manager.isRunning()) manager.close("用户关水");
            else manager.open();
        });
        btnCopyLog.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("water-valve-log",
                        logView.getText().toString()));
                toast("日志已复制");
            }
        });
        btnCheckUpdate.setText("v" + Updater.currentVersion(this) + " · 检查更新");
        btnCheckUpdate.setOnClickListener(v -> checkUpdate(false));

        requestPerms();
        appendLog("安卓版启动，版本 0.3.0");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing()) checkUpdate(true);
        }, 4000);
    }

    private void checkUpdate(boolean silent) {
        if (manager.isRunning() || manager.hasOpenSession()) {
            if (!silent) toast("放水中，先关水再检查更新");
            return;
        }
        btnCheckUpdate.setText("检查中…");
        Updater.check(this, (info, error) -> {
            btnCheckUpdate.setText("v" + Updater.currentVersion(this) + " · 检查更新");
            if (error != null) {
                if (!silent) toast("检查更新失败：" + error);
                return;
            }
            if (info == null || !info.hasUpdate) {
                if (!silent) toast("已是最新版本 v" + Updater.currentVersion(this));
                return;
            }
            showUpdateDialog(info);
        });
    }

    private void showUpdateDialog(Updater.Info info) {
        if (manager.isRunning() || manager.hasOpenSession()) {
            toast("放水中，先关水再更新");
            return;
        }
        String notes = info.notes == null ? "" : info.notes.trim();
        if (notes.length() > 600) notes = notes.substring(0, 600) + "…";
        String msg = "当前版本 v" + Updater.currentVersion(this)
                + "\n新版本 v" + info.version + " · " + mb(info.size)
                + (notes.isEmpty() ? "" : "\n\n" + notes);
        new AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage(msg)
                .setPositiveButton("下载并安装", (d, w) -> {
                    if (!Updater.canInstall(this)) {
                        Updater.openInstallSettings(this);
                        toast("请允许安装未知应用后再试");
                        return;
                    }
                    downloadUpdate(info);
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void downloadUpdate(Updater.Info info) {
        if (manager.isRunning() || manager.hasOpenSession()) {
            toast("放水中，先关水再下载更新");
            return;
        }
        if (updateProgress != null) updateProgress.dismiss();
        updateProgress = new ProgressDialog(this);
        updateProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        updateProgress.setMax(100);
        updateProgress.setCancelable(false);
        updateProgress.setMessage("下载中…");
        updateProgress.show();
        Updater.download(this, info,
                (pct, bytes, total) -> runOnUiThread(() -> {
                    if (updateProgress == null) return;
                    updateProgress.setProgress(pct);
                    updateProgress.setMessage("下载中… " + pct + "%");
                }),
                (file, error) -> runOnUiThread(() -> {
                    if (updateProgress != null) {
                        updateProgress.dismiss();
                        updateProgress = null;
                    }
                    if (error != null) {
                        new AlertDialog.Builder(this)
                                .setTitle("更新下载失败")
                                .setMessage(error)
                                .setPositiveButton("知道了", null)
                                .show();
                        return;
                    }
                    installUpdate(file);
                }));
    }

    private void installUpdate(File file) {
        if (manager.isRunning() || manager.hasOpenSession()) {
            toast("放水中，先关水再安装更新");
            return;
        }
        try {
            Updater.install(this, file);
            toast("已交给系统安装，请点“更新”");
        } catch (SecurityException e) {
            Updater.openInstallSettings(this);
            toast("请允许安装未知应用后再试");
        } catch (Exception e) {
            toast("安装失败：" + e.getMessage());
        }
    }

    private String mb(long bytes) {
        return String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0);
    }

    private void setupWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(getColor(R.color.bg));
        if (Build.VERSION.SDK_INT >= 30) {
            Api30.setup(w);
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        final int headerTop = header.getPaddingTop();
        final int bottomPad = bottomBar.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                top = Api30.top(insets);
                bottom = Api30.bottom(insets);
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            ViewGroup.LayoutParams lp = statusBarScrim.getLayoutParams();
            if (lp.height != top) {
                lp.height = top;
                statusBarScrim.setLayoutParams(lp);
            }
            header.setPadding(header.getPaddingLeft(), headerTop + top,
                    header.getPaddingRight(), header.getPaddingBottom());
            bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(),
                    bottomBar.getPaddingRight(), bottomPad + bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void requestPerms() {
        List<String> ps = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            addIfMissing(ps, Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(ps, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            addIfMissing(ps, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(ps, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!ps.isEmpty()) requestPermissions(ps.toArray(new String[0]), 1);
    }

    private void addIfMissing(List<String> ps, String p) {
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ps.add(p);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        int denied = 0;
        for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) denied++;
        if (denied > 0) {
            appendLog("有 " + denied + " 项权限未授予，扫描/通知可能不可用");
            toast("请授予蓝牙和通知权限");
        } else {
            appendLog("权限已就绪");
        }
    }

    private void renderSession(Cloud.Session s) {
        if (s == null) {
            sessionStatus.setText("未登录（手机号 + 短信验证码）");
            balance.setText("");
            return;
        }
        String who = s.userName == null || s.userName.isEmpty()
                || "null".equalsIgnoreCase(s.userName) ? s.telephone : s.userName;
        sessionStatus.setText("已登录：" + who + " · 项目 " + s.projectId);
        String bal = Cloud.fmtMoney(s.balance);
        balance.setText(bal.isEmpty() ? "" : "¥" + bal);
    }

    private void renderDevice() {
        String name = manager.getDeviceName();
        String m = manager.getMac();
        if ((name == null || name.isEmpty()) && (m == null || m.isEmpty())) {
            deviceStatus.setText("未连接设备");
        } else {
            deviceStatus.setText((name == null || name.isEmpty() ? "水表" : name)
                    + " · " + (m == null ? "" : m));
        }
    }

    private void renderStats(String text) {
        statLastMain.setText("—");
        statLastSub.setText("还没有记录");
        statTotalMain.setText("—");
        statTotalSub.setText("还没有记录");
        if (text == null || text.startsWith("账本：还没有")) return;
        Matcher m = Pattern.compile("本次：(\\d+) 秒 · ([\\d.]+) L · 省 ¥([\\d.]+)")
                .matcher(text);
        if (m.find()) {
            statLastMain.setText(m.group(2) + " L");
            statLastSub.setText(m.group(1) + " 秒 · 省 ¥" + m.group(3));
        }
        Matcher t = Pattern.compile("累计：(\\d+) 次 · ([\\d.]+) L · 省 ¥([\\d.]+)")
                .matcher(text);
        if (t.find()) {
            statTotalMain.setText(t.group(2) + " L");
            statTotalSub.setText(t.group(1) + " 次 · 省 ¥" + t.group(3));
        }
    }

    private void updateHeader() {
        if (running) setHeader("● 放水中", getColor(R.color.amber));
        else if (connected) setHeader("● 已连接", getColor(R.color.green_light));
        else setHeader("● 未连接", getColor(R.color.white_70));
    }

    private void setHeader(String text, int color) {
        headerStatus.setText(text);
        headerStatus.setTextColor(color);
    }

    @Override
    public void onLog(String line) {
        appendLog(line);
    }

    private void appendLog(String line) {
        runOnUiThread(() -> {
            String t = timeFmt.format(new Date());
            logBuf.append(t).append("  ").append(line).append('\n');
            if (logBuf.length() > 30000) logBuf.delete(0, logBuf.length() - 20000);
            logView.setText(logBuf);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onBusy(boolean busy, String label) {
        btnSendCode.setEnabled(!busy);
        btnLogin.setEnabled(!busy);
        btnScan.setEnabled(!busy);
        btnQuery.setEnabled(!busy);
        btnWater.setEnabled(!busy);
        if (busy) {
            btnWater.setText(label == null || label.isEmpty() ? "处理中…" : label + "…");
            btnWater.setBackgroundResource(R.drawable.bg_button_primary);
        } else {
            btnWater.setText(running ? "关水" : "开水");
            btnWater.setBackgroundResource(running
                    ? R.drawable.bg_button_danger : R.drawable.bg_button_primary);
        }
    }

    @Override
    public void onWater(boolean running) {
        this.running = running;
        btnWater.setText(running ? "关水" : "开水");
        btnWater.setBackgroundResource(running
                ? R.drawable.bg_button_danger : R.drawable.bg_button_primary);
        waterStatus.setText(running ? "正在放水 · 通知栏可关水" : (connected ? "水表已连接" : "空闲"));
        updateHeader();
        if (running) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onSession(Cloud.Session session) {
        renderSession(session);
    }

    @Override
    public void onDevices(List<ValveManager.ScanItem> devices) {
        deviceList.removeAllViews();
        for (ValveManager.ScanItem item : devices) {
            TextView tv = new TextView(this);
            String name = item.name == null || item.name.isEmpty() ? "未命名设备" : item.name;
            String addr;
            try {
                addr = item.device.getAddress();
            } catch (SecurityException e) {
                addr = "";
            }
            tv.setText(name + "\n" + addr + "  " + item.rssi + " dBm  ·  点此连接");
            tv.setTextSize(13);
            tv.setTextColor(getColor(R.color.ink));
            tv.setLineSpacing(dp(3), 1f);
            tv.setPadding(dp(14), dp(12), dp(14), dp(12));
            tv.setGravity(Gravity.START);
            tv.setBackgroundResource(R.drawable.bg_device_item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> manager.connect(item));
            deviceList.addView(tv);
        }
        if (devices.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("没有扫描到水表，靠近水表再试");
            tv.setTextSize(12);
            tv.setTextColor(getColor(R.color.muted));
            tv.setPadding(dp(8), dp(10), dp(8), dp(6));
            deviceList.addView(tv);
        }
    }

    @Override
    public void onQuery(Protocol.Query q) {
        connected = true;
        waterStatus.setText("设备：" + q.statusText() + " · onCharge=" + q.onCharge);
        updateHeader();
        renderDevice();
    }

    @Override
    public void onStats(String text) {
        renderStats(text);
    }

    @Override
    public void onConnection(boolean connected) {
        this.connected = connected;
        if (!connected && !running) waterStatus.setText("未连接水表");
        updateHeader();
    }

    @Override
    public void onBackPressed() {
        if (manager.isRunning()) {
            manager.close("返回退出");
            toast("已通知关水，稍候可在通知栏确认");
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        manager.setListener(null);
        if (updateProgress != null) {
            updateProgress.dismiss();
            updateProgress = null;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** API 30+ 才加载，避免旧系统解析新类。 */
    private static final class Api30 {
        static void setup(Window w) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                c.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        }

        static int top(WindowInsets i) {
            return i.getInsets(WindowInsets.Type.systemBars()).top;
        }

        static int bottom(WindowInsets i) {
            return i.getInsets(WindowInsets.Type.systemBars()).bottom;
        }
    }
}
