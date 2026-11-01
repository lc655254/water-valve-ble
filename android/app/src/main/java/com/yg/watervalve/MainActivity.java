package com.yg.watervalve;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
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
    private TextView heroTitle, heroSub, btnToggleLogin, btnToggleLog;
    private TextView statLastMain, statLastSub, statTotalMain, statTotalSub;
    private EditText phone, code;
    private Button btnSendCode, btnLogin, btnScan, btnQuery, btnWater;
    private TextView btnCopyLog;
    private TextView btnCheckUpdate;
    private LinearLayout deviceList, header, bottomBar, loginFields, logBody;
    private ScrollView logScroll;
    private View statusBarScrim, root;
    private ScrollView scroll;
    private View content, heroBlock, accountCard, deviceCard, statsCard, logCard;
    private final StringBuilder logBuf = new StringBuilder();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private boolean running;
    private boolean connected;
    private boolean logExpanded;
    private ProgressDialog updateProgress;
    private Updater.Info pendingUpdate;
    private String lastHeroTitle = "";

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
        heroTitle = findViewById(R.id.heroTitle);
        heroSub = findViewById(R.id.heroSub);
        btnToggleLogin = findViewById(R.id.btnToggleLogin);
        btnToggleLog = findViewById(R.id.btnToggleLog);
        loginFields = findViewById(R.id.loginFields);
        logBody = findViewById(R.id.logBody);
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
        scroll = findViewById(R.id.scroll);
        content = findViewById(R.id.content);
        heroBlock = findViewById(R.id.heroBlock);
        accountCard = findViewById(R.id.accountCard);
        deviceCard = findViewById(R.id.deviceCard);
        statsCard = findViewById(R.id.statsCard);
        logCard = findViewById(R.id.logCard);

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
                ? R.drawable.bg_button_ink : R.drawable.bg_button_primary);
        waterStatus.setText(running ? "正在放水" : (connected ? "水表已连接" : "状态未知"));
        waterStatus.setTextColor(getColor(running ? R.color.primary : R.color.muted));
        updateHeader();
        if (running) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (manager.hasOpenSession() && !manager.isConnected()) {
            try {
                ValveService.startRecover(this);
            } catch (Exception ignored) {
            }
            try {
                manager.recoverIfOpen(false);
            } catch (Exception ignored) {
            }
        }

        btnSendCode.setOnClickListener(v -> {
            tap(v);
            String p = phone.getText().toString().trim();
            if (p.length() != 11) {
                toast("请输入 11 位手机号");
                return;
            }
            manager.savePhone(p);
            manager.sendSms(p);
        });
        btnLogin.setOnClickListener(v -> {
            tap(v);
            String p = phone.getText().toString().trim();
            String c = code.getText().toString().trim();
            if (p.length() != 11 || c.isEmpty()) {
                toast("请输入手机号和验证码");
                return;
            }
            manager.savePhone(p);
            manager.login(p, c);
        });
        btnScan.setOnClickListener(v -> {
            tap(v);
            btnScan.setText("扫描中…");
            manager.scan();
            btnScan.postDelayed(() -> {
                if (!manager.isBusy()) btnScan.setText("扫描水表");
            }, 16000);
        });
        btnQuery.setOnClickListener(v -> {
            tap(v);
            manager.query();
        });
        btnWater.setOnClickListener(v -> {
            tap(v);
            if (manager.isRunning()) manager.close("用户关水");
            else manager.open();
        });
        btnCopyLog.setOnClickListener(v -> {
            tap(v);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("water-valve-log",
                        logView.getText().toString()));
                toast("日志已复制");
            }
        });
        btnToggleLogin.setOnClickListener(v -> {
            tap(v);
            boolean show = loginFields.getVisibility() != View.VISIBLE;
            animateVisibility(loginFields, show);
            btnToggleLogin.setText(show ? "收起" : "切换账号");
        });
        btnToggleLog.setOnClickListener(v -> {
            tap(v);
            boolean show = !logExpanded;
            logExpanded = show;
            animateVisibility(logBody, show);
            btnToggleLog.setText(show ? "收起" : "展开");
            if (show) {
                logBody.postDelayed(() -> logScroll.fullScroll(View.FOCUS_DOWN), 260);
            }
        });
        btnCheckUpdate.setText("v" + Updater.currentVersion(this) + " · 检查更新");
        btnCheckUpdate.setOnClickListener(v -> {
            tap(v);
            if (pendingUpdate != null) {
                Updater.Info info = pendingUpdate;
                pendingUpdate = null;
                showUpdateDialog(info);
            } else {
                checkUpdate(false);
            }
        });

        requestPerms();
        appendLog("安卓版启动，版本 0.5.2");
        String crash = CrashLog.last(this);
        if (!crash.isEmpty()) {
            appendLog("上次崩溃日志：\n" + crash.substring(0, Math.min(1500, crash.length())));
            CrashLog.clear(this);
        }
        setupInteractions();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing()) checkUpdate(true);
        }, 4000);
    }

    private void checkUpdate(boolean silent) {
        if (isFinishing() || isDestroyed()) return;
        if (manager.isRunning() || manager.hasOpenSession()) {
            if (!silent) toast("放水中，先关水再检查更新");
            return;
        }
        btnCheckUpdate.setText("检查中…");
        Updater.check(this, (info, error) -> {
            if (isFinishing() || isDestroyed()) return;
            btnCheckUpdate.setText("v" + Updater.currentVersion(this) + " · 检查更新");
            if (error != null) {
                if (!silent) toast("检查更新失败：" + error);
                return;
            }
            if (info == null || !info.hasUpdate) {
                if (!silent) showLatestDialog();
                return;
            }
            if (silent) {
                pendingUpdate = info;
                btnCheckUpdate.setText("发现 v" + info.version + " · 点此更新");
                return;
            }
            showUpdateDialog(info);
        });
    }

    private void showLatestDialog() {
        if (isFinishing() || isDestroyed()) return;
        View v = getLayoutInflater().inflate(R.layout.dialog_update, null);
        TextView version = v.findViewById(R.id.dialogVersion);
        TextView meta = v.findViewById(R.id.dialogMeta);
        TextView notesView = v.findViewById(R.id.dialogNotes);
        Button install = v.findViewById(R.id.dialogInstall);
        TextView later = v.findViewById(R.id.dialogLater);

        version.setText("v" + Updater.currentVersion(this));
        meta.setText("已是最新版本");
        notesView.setText("当前版本已是最新。\n\n保留极简界面、断线重连和 30 分钟关阀兜底。");
        install.setVisibility(View.GONE);
        later.setText("关闭");

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(v);
        dialog.setCanceledOnTouchOutside(true);
        later.setOnClickListener(x -> dialog.dismiss());
        dialog.show();
        Window dw = dialog.getWindow();
        if (dw != null) {
            dw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dw.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dw.setDimAmount(0.35f);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            dw.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        v.setAlpha(0f);
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void showUpdateDialog(Updater.Info info) {
        if (isFinishing() || isDestroyed()) return;
        if (manager.isRunning() || manager.hasOpenSession()) {
            toast("放水中，先关水再更新");
            return;
        }
        View v = getLayoutInflater().inflate(R.layout.dialog_update, null);
        TextView version = v.findViewById(R.id.dialogVersion);
        TextView meta = v.findViewById(R.id.dialogMeta);
        TextView notesView = v.findViewById(R.id.dialogNotes);
        Button install = v.findViewById(R.id.dialogInstall);
        TextView later = v.findViewById(R.id.dialogLater);

        version.setText("v" + info.version);
        meta.setText(mb(info.size) + " · 当前 v" + Updater.currentVersion(this));
        String notes = info.notes == null ? "" : info.notes.trim();
        notesView.setText(notes.isEmpty() ? "包含界面与稳定性更新。" : notes);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(v);
        dialog.setCanceledOnTouchOutside(true);
        install.setOnClickListener(x -> {
            dialog.dismiss();
            if (!Updater.canInstall(this)) {
                Updater.openInstallSettings(this);
                toast("请允许安装未知应用后再试");
                return;
            }
            downloadUpdate(info);
        });
        later.setOnClickListener(x -> dialog.dismiss());
        dialog.show();
        Window dw = dialog.getWindow();
        if (dw != null) {
            dw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dw.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dw.setDimAmount(0.35f);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            dw.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        v.setAlpha(0f);
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void downloadUpdate(Updater.Info info) {
        if (isFinishing() || isDestroyed()) return;
        if (manager.isRunning() || manager.hasOpenSession()) {
            toast("放水中，先关水再下载更新");
            return;
        }
        if (updateProgress != null) updateProgress.dismiss();
        updateProgress = new ProgressDialog(this);
        updateProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        updateProgress.setMax(100);
        updateProgress.setIndeterminate(true);
        updateProgress.setCancelable(false);
        updateProgress.setMessage("正在连接下载源…");
        updateProgress.show();
        Updater.download(this, info,
                (pct, bytes, total) -> runOnUiThread(() -> {
                    if (updateProgress == null) return;
                    if (pct < 0) {
                        if (!updateProgress.isIndeterminate()) {
                            updateProgress.setIndeterminate(true);
                        }
                        updateProgress.setMessage("正在连接下载源…");
                    } else {
                        if (updateProgress.isIndeterminate()) {
                            updateProgress.setIndeterminate(false);
                        }
                        updateProgress.setProgress(pct);
                        updateProgress.setMessage("下载中… " + pct + "%");
                    }
                }),
                (file, error) -> runOnUiThread(() -> {
                    if (updateProgress != null) {
                        updateProgress.dismiss();
                        updateProgress = null;
                    }
                    if (isFinishing() || isDestroyed()) return;
                    if (error != null) {
                        new AlertDialog.Builder(this)
                                .setTitle("更新下载失败")
                                .setMessage(error + "\n\n可改用浏览器打开镜像下载。")
                                .setPositiveButton("浏览器下载",
                                        (d, w) -> openDownloadInBrowser(info))
                                .setNegativeButton("知道了", null)
                                .show();
                        return;
                    }
                    installUpdate(file);
                }));
    }

    private void openDownloadInBrowser(Updater.Info info) {
        try {
            String url = "https://gh-proxy.com/" + info.url;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("没有可用浏览器");
        }
    }

    private void installUpdate(File file) {
        if (isFinishing() || isDestroyed()) return;
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
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        final int headerTop = header.getPaddingTop();
        final int bottomPad = bottomBar.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
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
        applySessionLayout(s != null);
        if (s == null) {
            sessionStatus.setText("未登录");
            balance.setText("手机号 + 短信验证码");
            balance.setTextColor(getColor(R.color.muted));
            return;
        }
        String who = s.userName == null || s.userName.isEmpty()
                || "null".equalsIgnoreCase(s.userName) ? s.telephone : s.userName;
        sessionStatus.setText(who);
        String bal = Cloud.fmtMoney(s.balance);
        balance.setText(bal.isEmpty() ? "" : "余额 ¥" + bal);
        balance.setTextColor(getColor(R.color.primary));
    }

    private void applySessionLayout(boolean logged) {
        if (btnToggleLogin == null) return;
        if (logged) {
            loginFields.setVisibility(View.GONE);
            btnToggleLogin.setVisibility(View.VISIBLE);
            btnToggleLogin.setText("切换账号");
        } else {
            loginFields.setVisibility(View.VISIBLE);
            btnToggleLogin.setVisibility(View.GONE);
        }
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
        if (running) {
            applyHero("放水中",
                    connected ? "正在出水 · 通知栏可关水" : "连接已断开，正在自动恢复…",
                    connected ? "进行中" : "恢复中", getColor(R.color.primary));
        } else if (connected) {
            String name = manager.getDeviceName();
            String mac = manager.getMac();
            String sub = (name == null || name.isEmpty()) ? "水表已连接" : name;
            if (mac != null && !mac.isEmpty()) sub += " · " + mac;
            applyHero("已连接", sub, "在线", getColor(R.color.success));
        } else {
            applyHero("未连接", "靠近水表，手机直接控制", "离线", getColor(R.color.muted));
        }
    }

    private void applyHero(String title, String sub, String chip, int color) {
        boolean changed = !title.equals(lastHeroTitle);
        if (changed && !lastHeroTitle.isEmpty()) {
            heroTitle.animate().cancel();
            heroTitle.animate().alpha(0f).translationY(-dp(4)).setDuration(90)
                    .withEndAction(() -> {
                        heroTitle.setText(title);
                        heroTitle.setTranslationY(dp(4));
                        heroTitle.animate().alpha(1f).translationY(0)
                                .setDuration(190)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                    }).start();
        } else {
            heroTitle.setText(title);
        }
        lastHeroTitle = title;
        if (!sub.equals(heroSub.getText().toString())) {
            heroSub.animate().cancel();
            heroSub.animate().alpha(0f).setDuration(80)
                    .withEndAction(() -> {
                        heroSub.setText(sub);
                        heroSub.animate().alpha(1f).setDuration(180).start();
                    }).start();
        }
        headerStatus.setText(chip);
        headerStatus.setTextColor(color);
        headerStatus.animate().cancel();
        headerStatus.setScaleX(0.94f);
        headerStatus.setScaleY(0.94f);
        headerStatus.animate().scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
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
            if (label != null) {
                if (label.contains("发送")) btnSendCode.setText("发送中…");
                if (label.contains("登录")) btnLogin.setText("登录中…");
                if (label.contains("连接")) btnScan.setText("连接中…");
                if (label.contains("查询")) btnQuery.setText("查询中…");
            }
            boolean waterOp = label != null
                    && (label.contains("开水") || label.contains("关") || label.contains("关水"));
            btnWater.setText(waterOp
                    ? (label + "…")
                    : (running ? "关水" : "开水"));
            btnWater.setBackgroundResource(label != null && label.contains("关")
                    ? R.drawable.bg_button_ink : R.drawable.bg_button_primary);
        } else {
            btnSendCode.setText("发码");
            btnLogin.setText("登录");
            btnScan.setText("扫描水表");
            btnQuery.setText("查询状态");
            btnWater.setText(running ? "关水" : "开水");
            btnWater.setBackgroundResource(running
                    ? R.drawable.bg_button_ink : R.drawable.bg_button_primary);
        }
    }

    @Override
    public void onWater(boolean running) {
        this.running = running;
        btnWater.setText(running ? "关水" : "开水");
        btnWater.setBackgroundResource(running
                ? R.drawable.bg_button_ink : R.drawable.bg_button_primary);
        btnWater.animate().cancel();
        btnWater.setScaleX(1f);
        btnWater.setScaleY(1f);
        btnWater.animate().scaleX(1.015f).scaleY(1.015f).setDuration(110)
                .withEndAction(() -> btnWater.animate()
                        .scaleX(1f).scaleY(1f).setDuration(140).start())
                .start();
        waterStatus.setText(running ? "正在出水 · 通知栏可关水"
                : (connected ? "水表已连接" : "空闲"));
        waterStatus.setTextColor(getColor(running ? R.color.primary : R.color.muted));
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
        for (int i = 0; i < devices.size(); i++) {
            ValveManager.ScanItem item = devices.get(i);
            TextView tv = new TextView(this);
            String name = item.name == null || item.name.isEmpty()
                    ? "KLCXKJ 水表" : item.name;
            String addr;
            try {
                addr = item.device.getAddress();
            } catch (SecurityException e) {
                addr = "";
            }
            tv.setText(name + "\n" + addr + "   " + item.rssi + " dBm");
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
            tv.setOnClickListener(v -> {
                tap(v);
                manager.connect(item);
            });
            tv.setAlpha(0f);
            tv.setTranslationY(dp(8));
            deviceList.addView(tv);
            tv.animate().alpha(1f).translationY(0f)
                    .setStartDelay(i * 35L).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        if (!devices.isEmpty()) {
            btnScan.setText("扫描水表");
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
        waterStatus.setText(q.statusText());
        waterStatus.setTextColor(getColor("00".equals(q.onCharge)
                ? R.color.success : R.color.primary));
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
        if (!connected && !running) {
            waterStatus.setText("未连接水表");
            waterStatus.setTextColor(getColor(R.color.muted));
        }
        renderDevice();
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

    private void setupInteractions() {
        View[] press = {btnWater, btnScan, btnQuery, btnLogin, btnSendCode,
                btnCopyLog, btnToggleLogin, btnToggleLog, btnCheckUpdate};
        for (View v : press) pressScale(v);
        scroll.setOnScrollChangeListener((v, sx, sy, oldX, oldY) -> {
            float t = Math.min(1f, sy / (float) dp(140));
            bottomBar.setElevation(dp(4) + t * dp(10));
        });
        animateIn();
    }

    private void animateIn() {
        View[] items = {header, heroBlock, accountCard, deviceCard, statsCard, logCard};
        for (int i = 0; i < items.length; i++) {
            View v = items[i];
            if (v == null) continue;
            v.setAlpha(0f);
            v.setTranslationY(dp(14));
            v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(50L * i)
                    .setDuration(340)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
        btnCheckUpdate.setAlpha(0f);
        btnCheckUpdate.animate().alpha(1f).setDuration(300).setStartDelay(320).start();
        bottomBar.setAlpha(0f);
        bottomBar.setTranslationY(dp(18));
        bottomBar.animate().alpha(1f).translationY(0f)
                .setStartDelay(180).setDuration(320)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void pressScale(View v) {
        if (v == null) return;
        v.setOnTouchListener((view, event) -> {
            if (!view.isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.975f).scaleY(0.975f)
                            .setDuration(80).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    break;
            }
            return false;
        });
    }

    private void animateVisibility(View v, boolean show) {
        if (v == null) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        int parentWidth = v.getWidth();
        if (parentWidth <= 0 && v.getParent() instanceof View) {
            parentWidth = ((View) v.getParent()).getWidth();
        }
        v.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, parentWidth),
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int target = v.getMeasuredHeight();
        int start = show ? 0 : v.getHeight();
        if (show) {
            v.setVisibility(View.VISIBLE);
            v.setAlpha(0f);
            lp.height = 0;
            v.setLayoutParams(lp);
        }
        if ((show && target <= 0) || (!show && start <= 0)) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            v.setLayoutParams(lp);
            v.setAlpha(1f);
            v.setVisibility(show ? View.VISIBLE : View.GONE);
            return;
        }
        int end = show ? target : 0;
        ValueAnimator anim = ValueAnimator.ofInt(start, end);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            int value = (int) a.getAnimatedValue();
            lp.height = value;
            v.setLayoutParams(lp);
            float frac = show ? value / (float) target : value / (float) start;
            v.setAlpha(Math.max(0f, Math.min(1f, frac)));
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                v.setLayoutParams(lp);
                v.setAlpha(1f);
                if (!show) v.setVisibility(View.GONE);
            }
        });
        anim.start();
    }

    private void tap(View v) {
        if (v != null) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
