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
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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
import android.widget.ImageView;
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
    private TextView btnXiaolian;
    private TextView btnNotice;
    private Announcement.Info currentNotice;
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

    // 独立登录页
    private ScrollView loginGate;
    private EditText gatePhone, gateCode;
    private TextView gateSend, gateHint, gateSkip, banner;
    private Button gateLogin;
    private boolean gateSkipped;
    private long gateCodeSentAt;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    // 连接过程反馈
    private AlertDialog connectDialog;
    private ValveManager.ScanItem connectingItem;
    private String lastOnCharge = "";

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
        btnXiaolian = findViewById(R.id.btnXiaolian);
        btnNotice = findViewById(R.id.btnNotice);
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
        loginGate = findViewById(R.id.loginGate);
        gatePhone = findViewById(R.id.gatePhone);
        gateCode = findViewById(R.id.gateCode);
        gateSend = findViewById(R.id.gateSend);
        gateLogin = findViewById(R.id.gateLogin);
        gateHint = findViewById(R.id.gateHint);
        gateSkip = findViewById(R.id.gateSkip);
        banner = findViewById(R.id.banner);
        setupLoginGate();

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
        renderWaterStatus();
        // 没登录又没在放水 → 直接盖独立登录页（放水中要先保证能关水，不拦）
        if (manager.getSession() == null && !manager.hasOpenSession()) {
            showLoginGate(true);
        }
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
            if (manager.isRunning()) {
                manager.close("用户关水");
                return;
            }
            if (manager.getSession() == null) {
                toast("先登录才能开水");
                showLoginGate(true);
                return;
            }
            if (!manager.isConnected()) {
                toast("还没连接水表：先点「扫描水表」");
                pulseView(btnScan);
                return;
            }
            manager.open();
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
            // 统一走独立登录页：切账号也是重新登录
            gateHint.setText("换号登录：填手机号 → 获取验证码 → 自动登录");
            showLoginGate(true);
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
        btnXiaolian.setOnClickListener(v -> {
            tap(v);
            showXiaolianDialog();
        });
        btnNotice.setOnClickListener(v -> {
            tap(v);
            if (currentNotice != null) showAnnouncementDialog(currentNotice);
        });
        updateXiaolianButton();
        requestPerms();
        appendLog("安卓版启动，版本 " + Updater.currentVersion(this));
        String crash = CrashLog.last(this);
        if (!crash.isEmpty()) {
            appendLog("上次崩溃日志：\n" + crash.substring(0, Math.min(1500, crash.length())));
            CrashLog.clear(this);
        }
        setupInteractions();
        new Handler(Looper.getMainLooper()).postDelayed(this::checkAnnouncement, 2500);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing()) checkUpdate(true);
        }, 4000);
    }

    private void updateXiaolianButton() {
        if (btnXiaolian == null) return;
        // 0.6.1 改为趣智校园 /device/info/mac，不再需要笑联登录。
        btnXiaolian.setVisibility(View.GONE);
    }

    private void showXiaolianDialog() {
        if (isFinishing() || isDestroyed()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(16));
        box.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText("同步官方宿舍名");
        title.setTextSize(20);
        title.setTextColor(getColor(R.color.ink));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("使用智慧笑联账号登录，按 MAC 查询学校设备库。只保存 token，不保存密码。");
        hint.setTextSize(11);
        hint.setTextColor(getColor(R.color.muted));
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(0, dp(6), 0, dp(12));
        box.addView(hint);

        EditText phoneInput = new EditText(this);
        phoneInput.setHint("笑联手机号");
        phoneInput.setText(manager.getPhone());
        phoneInput.setTextSize(14);
        phoneInput.setSingleLine(true);
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneInput.setTextColor(getColor(R.color.ink));
        phoneInput.setHintTextColor(getColor(R.color.muted));
        phoneInput.setBackgroundResource(R.drawable.bg_input);
        phoneInput.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        box.addView(phoneInput, plp);

        EditText pwdInput = new EditText(this);
        pwdInput.setHint("笑联登录密码");
        pwdInput.setTextSize(14);
        pwdInput.setSingleLine(true);
        pwdInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwdInput.setTextColor(getColor(R.color.ink));
        pwdInput.setHintTextColor(getColor(R.color.muted));
        pwdInput.setBackgroundResource(R.drawable.bg_input);
        pwdInput.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        wlp.topMargin = dp(8);
        box.addView(pwdInput, wlp);

        TextView error = new TextView(this);
        error.setTextSize(11);
        error.setTextColor(getColor(R.color.danger));
        error.setPadding(0, dp(8), 0, 0);
        error.setVisibility(View.GONE);
        box.addView(error);

        Button login = new Button(this);
        login.setText("登录并同步");
        login.setAllCaps(false);
        login.setTextSize(14);
        login.setTextColor(Color.WHITE);
        login.setBackgroundResource(R.drawable.bg_button_primary);
        login.setStateListAnimator(null);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        blp.topMargin = dp(12);
        box.addView(login, blp);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(12);
        cancel.setTextColor(getColor(R.color.primary));
        cancel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        clp.topMargin = dp(2);
        box.addView(cancel, clp);

        TextView clear = null;
        if (Xiaolian.hasToken(this)) {
            clear = new TextView(this);
            clear.setText("清除已同步名称");
            clear.setTextSize(11);
            clear.setTextColor(getColor(R.color.muted));
            clear.setGravity(Gravity.CENTER);
            box.addView(clear, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(box);
        dialog.setCanceledOnTouchOutside(true);
        cancel.setOnClickListener(v -> dialog.dismiss());
        login.setOnClickListener(v -> {
            String p = phoneInput.getText().toString().trim();
            String pw = pwdInput.getText().toString();
            if (p.length() != 11 || pw.isEmpty()) {
                error.setText("请输入 11 位手机号和笑联密码");
                error.setVisibility(View.VISIBLE);
                return;
            }
            tap(v);
            login.setEnabled(false);
            login.setText("登录中…");
            error.setVisibility(View.GONE);
            new Thread(() -> {
                Xiaolian.LoginResult r = Xiaolian.login(MainActivity.this, p, pw);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (r.ok) {
                        updateXiaolianButton();
                        dialog.dismiss();
                        toast(r.message);
                        manager.refreshOfficialNames();
                    } else {
                        login.setEnabled(true);
                        login.setText("登录并同步");
                        error.setText(r.message);
                        error.setVisibility(View.VISIBLE);
                    }
                });
            }, "xiaolian-login").start();
        });
        if (clear != null) {
            final TextView clearRef = clear;
            clearRef.setOnClickListener(v -> {
                Xiaolian.clear(this);
                manager.clearOfficialCache();
                updateXiaolianButton();
                clearRef.setVisibility(View.GONE);
                toast("已清除官方名称");
            });
        }
        dialog.show();
        Window dw = dialog.getWindow();
        if (dw != null) {
            dw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dw.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dw.setDimAmount(0.35f);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            dw.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        box.setAlpha(0f);
        box.setScaleX(0.96f);
        box.setScaleY(0.96f);
        box.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void checkAnnouncement() {
        if (isFinishing() || isDestroyed()) return;
        Announcement.check(this, (info, error) -> {
            if (isFinishing() || isDestroyed()) return;
            if (info == null) {
                currentNotice = null;
                btnNotice.setVisibility(View.GONE);
                return;
            }
            currentNotice = info;
            btnNotice.setVisibility(View.VISIBLE);
            if (info.once && Announcement.seen(this, info.id)) return;
            showAnnouncementDialog(info);
        });
    }

    private void showAnnouncementDialog(Announcement.Info info) {
        if (info == null || isFinishing() || isDestroyed()) return;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(14));
        box.setBackgroundResource(R.drawable.bg_dialog);

        TextView label = new TextView(this);
        label.setText("NOTICE");
        label.setTextSize(10);
        label.setLetterSpacing(0.18f);
        label.setTextColor(getColor(R.color.primary));
        box.addView(label);

        TextView title = new TextView(this);
        title.setText(info.title);
        title.setTextSize(22);
        title.setTextColor(getColor(R.color.ink));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(4), 0, dp(8));
        box.addView(title);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);

        TextView body = new TextView(this);
        body.setText(info.body);
        body.setTextSize(13);
        body.setTextColor(getColor(R.color.ink));
        body.setLineSpacing(dp(4), 1f);
        body.setTextIsSelectable(true);
        content.addView(body);

        if (info.link != null && !info.link.isEmpty()) {
            TextView link = new TextView(this);
            link.setText(info.linkText);
            link.setTextSize(12);
            link.setTextColor(getColor(R.color.primary));
            link.setPadding(0, dp(10), 0, dp(2));
            link.setOnClickListener(v -> {
                try {
                    String url = info.link.startsWith("http") ? info.link : "https://" + info.link;
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    toast("没有可用浏览器");
                }
            });
            content.addView(link);
        }

        for (String url : info.images) {
            TextView ph = new TextView(this);
            ph.setText("图片加载中…");
            ph.setTextSize(12);
            ph.setTextColor(getColor(R.color.muted));
            ph.setPadding(0, dp(12), 0, dp(4));
            content.addView(ph);

            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setMaxHeight(dp(360));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackgroundResource(R.drawable.bg_dialog_notes);
            image.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            content.addView(image, lp);
            Announcement.loadImage(this, url, image, ph);
        }

        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button close = new Button(this);
        close.setText(info.button);
        close.setAllCaps(false);
        close.setTextSize(14);
        close.setTextColor(Color.WHITE);
        close.setBackgroundResource(R.drawable.bg_button_primary);
        close.setStateListAnimator(null);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        blp.topMargin = dp(12);
        box.addView(close, blp);

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(box);
        dialog.setCanceledOnTouchOutside(true);
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            if (info.once) Announcement.markSeen(this, info.id);
        });
        dialog.show();
        Window dw = dialog.getWindow();
        if (dw != null) {
            dw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dw.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dw.setDimAmount(0.35f);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            dw.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        box.post(() -> {
            int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.60f);
            if (scroll.getHeight() > max) {
                ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                lp.height = max;
                scroll.setLayoutParams(lp);
            }
        });
        box.setAlpha(0f);
        box.setScaleX(0.96f);
        box.setScaleY(0.96f);
        box.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
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
                appendLog("检查更新失败：" + error);
                if (!silent) toast("检查更新失败：" + error);
                return;
            }
            if (info == null || !info.hasUpdate) {
                appendLog("检查更新：当前版本已是最新");
                if (!silent) showLatestDialog();
                return;
            }
            appendLog("检查更新：发现新版本 v" + info.version);
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
        notesView.setText("当前版本已是最新。");
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

    // ================= 独立登录页 =================

    private void setupLoginGate() {
        gatePhone.setText(manager.getPhone());
        gateSend.setOnClickListener(v -> {
            tap(v);
            sendGateCode();
        });
        gateLogin.setOnClickListener(v -> {
            tap(v);
            doGateLogin();
        });
        gateSkip.setOnClickListener(v -> {
            tap(v);
            gateSkipped = true;
            hideLoginGate();
            toast("没登录不能开水；需要时点「登录」");
        });
        // 收到 6 位验证码自动提交：避免「码收到了但忘了点登录」
        gateCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String p = gatePhone.getText().toString().trim();
                if (s.length() == 6 && p.length() == 11 && !manager.isBusy()) {
                    doGateLogin();
                }
            }
        });
        gatePhone.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 11) gateHint.setText("点「获取验证码」，收到后会自动登录");
            }
        });
    }

    private void showLoginGate(boolean show) {
        if (loginGate == null) return;
        if (!show) {
            hideLoginGate();
            return;
        }
        gateSkipped = false;
        loginGate.setVisibility(View.VISIBLE);
        loginGate.setAlpha(0f);
        loginGate.animate().alpha(1f).setDuration(180).start();
        if (gatePhone.getText().toString().trim().isEmpty()) {
            gatePhone.setText(manager.getPhone());
        }
        if (gateHint.getText().toString().isEmpty()) {
            gateHint.setText("没登录时只能看状态，开不了阀");
        }
        gatePhone.requestFocus();
    }

    private void hideLoginGate() {
        if (loginGate == null || loginGate.getVisibility() != View.VISIBLE) return;
        loginGate.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            loginGate.setVisibility(View.GONE);
            loginGate.setAlpha(1f);
        }).start();
    }

    private void sendGateCode() {
        String p = gatePhone.getText().toString().trim();
        if (p.length() != 11) {
            gateHint.setText("手机号要 11 位");
            return;
        }
        manager.savePhone(p);
        manager.sendSms(p);
        gateCodeSentAt = System.currentTimeMillis();
        gateHint.setText("验证码已发送，收到后会自动登录");
        gateSend.setEnabled(false);
        gateSend.setText("60s");
        countdownGate();
        gateCode.requestFocus();
    }

    private void countdownGate() {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                long left = 60 - (System.currentTimeMillis() - gateCodeSentAt) / 1000;
                if (left <= 0) {
                    gateSend.setEnabled(true);
                    gateSend.setText("重新获取");
                    return;
                }
                gateSend.setText(left + "s");
                uiHandler.postDelayed(this, 1000);
            }
        });
    }

    private void doGateLogin() {
        String p = gatePhone.getText().toString().trim();
        String c = gateCode.getText().toString().trim();
        if (p.length() != 11) {
            gateHint.setText("手机号要 11 位");
            return;
        }
        if (c.isEmpty()) {
            gateHint.setText("先填验证码");
            return;
        }
        gateHint.setText("正在登录…");
        manager.savePhone(p);
        manager.login(p, c);
    }

    @Override
    public void onLogin(boolean ok, String msg) {
        if (ok) {
            gateHint.setText("登录成功");
            hideLoginGate();
            toast("✓ 登录成功");
            renderWaterStatus();
        } else {
            gateHint.setText("登录失败：" + msg);
            gateCode.setText("");
            gateCode.requestFocus();
        }
    }

    // ================= 连接反馈 / 状态提示 =================

    private void connectTo(ValveManager.ScanItem item) {
        if (item == null) return;
        String name = item.name == null || item.name.isEmpty() ? "水表" : item.name;
        connectingItem = item;
        if (connectDialog != null && connectDialog.isShowing()) connectDialog.dismiss();
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在连接 " + name + " …\n靠近水表，别让官方 App 抢连接");
        pd.setCancelable(true);
        pd.setCanceledOnTouchOutside(false);
        pd.show();
        connectDialog = pd;
        manager.connect(item);
        final long t0 = System.currentTimeMillis();
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (connectDialog != pd) return;
                if (!pd.isShowing()) return;
                if (manager.isConnected()) {
                    pd.dismiss();
                    connectDialog = null;
                    connected = true;
                    renderWaterStatus();
                    toast("✓ 已连接 " + name);
                    manager.query();
                    return;
                }
                if (System.currentTimeMillis() - t0 > 25000) {
                    pd.dismiss();
                    connectDialog = null;
                    showConnectFailed(item);
                    return;
                }
                uiHandler.postDelayed(this, 350);
            }
        });
    }

    private void showConnectFailed(ValveManager.ScanItem item) {
        String name = item == null || item.name == null || item.name.isEmpty()
                ? "水表" : item.name;
        new AlertDialog.Builder(this)
                .setTitle("连不上 " + name)
                .setMessage(explainConnectError(manager.getLastConnectError()))
                .setPositiveButton("重试", (d, w) -> {
                    d.dismiss();
                    connectTo(item);
                })
                .setNeutralButton("排查", (d, w) -> {
                    d.dismiss();
                    showTroubleshoot();
                })
                .setNegativeButton("知道了", (d, w) -> d.dismiss())
                .show();
    }

    private String explainConnectError(String err) {
        String e = err == null ? "" : err.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        if (e.contains("133")) {
            sb.append("系统蓝牙报 133（GATT_ERROR）：这台水表大概率已经被别的设备占着。\n\n")
                    .append("① 关掉手机上官方水控 App —— 它连着表时，别的设备连不上\n")
                    .append("② 系统蓝牙设置里找到 KLCXKJ-Water，点「断开 / 取消配对」\n")
                    .append("③ 手机靠近水表 3 米内再试\n");
        } else if (e.contains("超时") || e.contains("timeout")) {
            sb.append("连接超时：水表广播稀疏或信号太弱。\n\n")
                    .append("① 靠近到 3 米内再扫\n")
                    .append("② 关掉官方 App，别让它抢连接\n")
                    .append("③ 水表正在放水 / 刷卡时会停止广播，等一会儿再扫\n");
        } else if (e.contains("ff02") || e.contains("ff01") || e.contains("特征")) {
            sb.append("连上了但没找到 FF02/FF01 特征：可能连到了别的设备或固件不同。\n\n")
                    .append("① 确认选的是 KLCXKJ-Water\n")
                    .append("② 断开后重新扫描再连\n");
        } else {
            sb.append("连接失败");
            if (err != null && !err.isEmpty()) sb.append("：").append(err);
            sb.append("\n\n常见原因：① 已被官方 App 占用 ② 信号太弱 ③ 水表休眠。\n");
        }
        sb.append("\n连上以后如果一直停在「结算中」，点开水会先自动补结算。");
        return sb.toString();
    }

    private void showTroubleshoot() {
        new AlertDialog.Builder(this)
                .setTitle("连不上水表？按顺序试")
                .setMessage("① 关掉官方水控 App（趣智校园 / 水控助手）\n"
                        + "② 系统蓝牙里找到 KLCXKJ-Water，取消配对 / 断开\n"
                        + "③ 手机贴近水表 3 米内，重新扫描\n"
                        + "④ 水表在放水 / 刷卡时会停止广播，等它闲下来\n"
                        + "⑤ 还不行就给水表断电重启（拔电池 / 关空开）\n\n"
                        + "连上后如果状态是「结算中」，说明上次用水没结算完，"
                        + "点开水会自动补结算，不用手动处理。")
                .setPositiveButton("知道了", (d, w) -> d.dismiss())
                .show();
    }

    private void pulseView(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(0.45f);
        v.animate().alpha(1f).setDuration(320).start();
    }

    private void renderWaterStatus() {
        if (waterStatus == null) return;
        String text;
        int color;
        if (running) {
            text = connected ? "放水中 · 点下面按钮关水"
                    : "放水中 · 连接已断开，正在自动恢复…";
            color = connected ? R.color.primary : R.color.danger;
        } else if (!connected) {
            text = "未连接水表 · 先扫描并连接";
            color = R.color.muted;
        } else if ("03".equals(lastOnCharge)) {
            text = "已连接 · 结算中（点开水会自动补结算）";
            color = R.color.amber;
        } else if ("01".equals(lastOnCharge)) {
            text = "已连接 · 正在放水";
            color = R.color.primary;
        } else {
            text = "已连接 · 空闲，可以开水";
            color = R.color.success;
        }
        waterStatus.setText(text);
        waterStatus.setTextColor(getColor(color));
        waterStatus.animate().cancel();
        waterStatus.setAlpha(0.45f);
        waterStatus.animate().alpha(1f).setDuration(240).start();
        updateHeader();
        updateBanner();
    }

    private void updateBanner() {
        if (banner == null) return;
        String msg = "";
        if (!connected && manager.hasOpenSession()) {
            msg = "⚠ 连接断开，水可能还在流 —— 正在自动重连并补结算";
        } else if (!connected && running) {
            msg = "⚠ 连接断开，正在自动恢复…";
        }
        if (msg.isEmpty()) {
            banner.setVisibility(View.GONE);
        } else {
            banner.setText(msg);
            banner.setVisibility(View.VISIBLE);
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
        if (gateLogin != null) {
            gateLogin.setEnabled(!busy);
            gateLogin.setText(busy && label != null && label.contains("登录") ? "登录中…" : "登录");
        }
        if (busy && label != null && label.contains("登录") && gateHint != null) {
            gateHint.setText("正在登录…");
        }
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
        renderWaterStatus();
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
            String title;
            String detail;
            if (item.bound) {
                title = "我的水表 · " + name;
                detail = addr + "   " + item.rssi + " dBm";
                tv.setTextColor(getColor(R.color.primary));
                tv.setBackgroundResource(R.drawable.bg_device_item_bound);
            } else if (item.official) {
                title = name;
                detail = "附近水表 · " + addr + "   " + item.rssi + " dBm";
                tv.setTextColor(getColor(R.color.ink));
                tv.setBackgroundResource(R.drawable.bg_device_item);
            } else {
                title = item.cloudChecked ? "附近水表（未绑定）" : "附近水表";
                detail = name + " · " + addr + "   " + item.rssi + " dBm";
                tv.setTextColor(getColor(R.color.ink));
                tv.setBackgroundResource(R.drawable.bg_device_item);
            }
            tv.setText(title + "\n" + detail);
            tv.setTextSize(13);
            tv.setLineSpacing(dp(3), 1f);
            tv.setPadding(dp(14), dp(12), dp(14), dp(12));
            tv.setGravity(Gravity.START);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> {
                tap(v);
                if (!item.bound && item.cloudChecked) {
                    toast("这是附近水表，开阀前请确认不是别人的表");
                }
                connectTo(item);
            });
            pressScale(tv);
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
        lastOnCharge = q.onCharge == null ? "" : q.onCharge;
        renderWaterStatus();
        renderDevice();
        if ("03".equals(lastOnCharge)) {
            toast("水表在结算中：点「开水」会自动先补结算");
        }
    }

    @Override
    public void onStats(String text) {
        renderStats(text);
    }

    @Override
    public void onConnection(boolean connected) {
        this.connected = connected;
        if (!connected) {
            lastOnCharge = "";
            // 用户点了「连接」但失败 → 关掉进度条并给可操作的提示
            if (connectDialog != null && connectDialog.isShowing()) {
                connectDialog.dismiss();
                connectDialog = null;
                if (connectingItem != null) {
                    showConnectFailed(connectingItem);
                }
            }
        }
        renderDevice();
        renderWaterStatus();
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
                btnCopyLog, btnToggleLogin, btnToggleLog, btnCheckUpdate, btnXiaolian};
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
