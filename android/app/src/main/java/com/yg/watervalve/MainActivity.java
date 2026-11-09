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
import android.content.res.ColorStateList;
import android.content.res.Configuration;
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
import android.text.TextUtils;
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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private LinearLayout deviceList, header, bottomBar, loginFields, logBody, statHistory, accountActions;
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
    private Dialog connectDialog;
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
        statHistory = findViewById(R.id.statHistory);
        accountActions = findViewById(R.id.accountActions);
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
            if (manager.isBusy()) return;          // 按钮不再禁用，这里挡重复点击
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
                if (accountActions != null) accountActions.setVisibility(View.GONE);
                return;
            }
            currentNotice = info;
            btnNotice.setVisibility(View.VISIBLE);
            if (accountActions != null) accountActions.setVisibility(View.VISIBLE);
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
        ScrollView notesScroll = v.findViewById(R.id.dialogNotesScroll);
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
        capNotesHeight(notesScroll, notesView);
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
        ScrollView notesScroll = v.findViewById(R.id.dialogNotesScroll);
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
        capNotesHeight(notesScroll, notesView);
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

    /**
     * 更新说明最多占屏幕 40%，超了就在框里滚动 ——
     * 以前是 maxLines=7 + 省略号，条目一多就看不全。
     */
    private void capNotesHeight(final ScrollView scroll, final TextView notes) {
        if (scroll == null || notes == null) return;
        final int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.40f);
        scroll.post(() -> {
            int w = notes.getWidth();
            if (w <= 0) {
                w = getResources().getDisplayMetrics().widthPixels - dp(48) - dp(28);
            }
            notes.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int want = notes.getMeasuredHeight() + notes.getPaddingTop() + notes.getPaddingBottom();
            int h = Math.min(want, max);
            ViewGroup.LayoutParams lp = scroll.getLayoutParams();
            if (lp.height != h) {
                lp.height = h;
                scroll.setLayoutParams(lp);
            }
            scroll.setVerticalScrollBarEnabled(want > max);
        });
    }

    private void setupWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(getColor(R.color.bg));
        // 深色模式下状态栏/导航栏图标要变白，否则黑图标压在深色背景上看不见
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!night) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);

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
    }

    private void hideLoginGate() {
        if (loginGate == null || loginGate.getVisibility() != View.VISIBLE) return;
        hideIme();
        loginGate.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            loginGate.setVisibility(View.GONE);
            loginGate.setAlpha(1f);
        }).start();
    }

    /** 收起键盘并把焦点从输入框上摘掉：否则点底部按钮时键盘一收，页面会被带着滚一下 */
    private void hideIme() {
        try {
            if (gateCode != null) gateCode.clearFocus();
            if (gatePhone != null) gatePhone.clearFocus();
            if (loginGate != null) {
                loginGate.clearFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(loginGate.getWindowToken(), 0);
                }
            }
        } catch (Exception ignored) {
        }
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
        final String name = item.name == null || item.name.isEmpty() ? "水表" : item.name;
        connectingItem = item;
        if (connectDialog != null && connectDialog.isShowing()) connectDialog.dismiss();

        LinearLayout box = dialogCard();
        box.addView(dialogLabel("CONNECTING"));
        box.addView(dialogTitle("正在连接"));
        box.addView(dialogSub(name));

        final ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(25);
        bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.primary)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(getColor(R.color.outline)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        blp.topMargin = dp(16);
        box.addView(bar, blp);

        final TextView elapse = dialogNote("已等 0 秒 · 最多 25 秒");
        elapse.setPadding(0, dp(9), 0, 0);
        box.addView(elapse);

        TextView note = dialogNote("靠近水表 3 米内；先关掉官方水控 App，别让它抢连接。");
        note.setPadding(0, dp(6), 0, 0);
        box.addView(note);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        TextView cancel = dialogGhost("取消连接");
        cancel.setOnClickListener(v -> {
            tap(v);
            dialog.dismiss();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        clp.topMargin = dp(6);
        box.addView(cancel, clp);

        showStyledDialog(dialog, box, 0.86f);
        connectDialog = dialog;
        manager.connect(item);

        final long t0 = System.currentTimeMillis();
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (connectDialog != dialog) return;
                if (!dialog.isShowing()) return;
                int sec = (int) ((System.currentTimeMillis() - t0) / 1000);
                bar.setProgress(Math.min(sec, 25));
                if (sec < 3) {
                    elapse.setText("已等 " + sec + " 秒 · 最多 25 秒");
                } else if (sec < 12) {
                    elapse.setText("已等 " + sec + " 秒 · 正在找水表服务…");
                } else {
                    elapse.setText("已等 " + sec + " 秒 · 信号弱，再等等…");
                }
                if (manager.isConnected()) {
                    dialog.dismiss();
                    connectDialog = null;
                    connected = true;
                    renderWaterStatus();
                    toast("✓ 已连接 " + name);
                    manager.query();
                    return;
                }
                if (sec >= 25) {
                    dialog.dismiss();
                    connectDialog = null;
                    showConnectFailed(item);
                    return;
                }
                uiHandler.postDelayed(this, 400);
            }
        });
    }

    /** 连接失败：一句话原因 + 最多三步能立刻做的事，按钮「重试 / 看排查 / 知道了」 */
    private void showConnectFailed(ValveManager.ScanItem item) {
        if (isFinishing() || isDestroyed()) return;
        final String name = item == null || item.name == null || item.name.isEmpty()
                ? "水表" : item.name;
        final String err = manager.getLastConnectError();

        LinearLayout box = dialogCard();
        box.addView(dialogLabel("CONNECT FAILED"));
        box.addView(dialogTitle("连不上水表"));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        chp.topMargin = dp(10);
        chips.setLayoutParams(chp);
        chips.addView(dialogChip(name));
        String code = errorCodeLabel(err);
        if (!code.isEmpty()) {
            TextView c = dialogChip(code);
            LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            clp2.leftMargin = dp(8);
            chips.addView(c, clp2);
        }
        box.addView(chips);

        TextView cause = dialogBody(connectCause(err));
        cause.setPadding(0, dp(14), 0, 0);
        box.addView(cause);

        TextView steps = dialogNote(connectSteps(err));
        steps.setPadding(0, dp(8), 0, 0);
        box.addView(steps);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Button retry = dialogPrimary("重试");
        retry.setOnClickListener(v -> {
            tap(v);
            dialog.dismiss();
            connectTo(item);
        });
        box.addView(retry);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView fix = dialogGhost("按提示排查");
        fix.setOnClickListener(v -> {
            tap(v);
            dialog.dismiss();
            showDiagWizard("b1");
        });
        TextView ok = dialogGhost("知道了");
        ok.setOnClickListener(v -> {
            tap(v);
            dialog.dismiss();
        });
        row.addView(fix, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        row.addView(ok, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        rlp.topMargin = dp(2);
        box.addView(row, rlp);

        showStyledDialog(dialog, box, 0.9f);
    }

    /** 一句话说清最可能的原因：扫到了却连不上，基本都是「有人占着这块表」 */
    private String connectCause(String err) {
        String e = err == null ? "" : err.toLowerCase(Locale.ROOT);
        if (e.contains("ff02") || e.contains("ff01") || e.contains("特征")) {
            return "连上了，但没找到 FF02 / FF01 特征：可能连到了别的设备。";
        }
        if (e.contains("133")) {
            return "极大概率有人正连着这块表（官方 App 或别人的手机）—— 水表一次只接受一个连接。";
        }
        if (e.contains("超时") || e.contains("timeout")) {
            return "多半还是被占着；也可能是等待时走远了、信号掉了。";
        }
        return "连不上这块表，最常见的原因就是已经有人连着它了。";
    }

    /** 最多三条能立刻做的动作 */
    private String connectSteps(String err) {
        String e = err == null ? "" : err.toLowerCase(Locale.ROOT);
        if (e.contains("ff02") || e.contains("ff01") || e.contains("特征")) {
            return "① 确认选的是 KLCXKJ-Water\n"
                    + "② 断开后重新扫描再连";
        }
        if (e.contains("133")) {
            return "① 关掉官方水控 App，后台也要划掉\n"
                    + "② 有同学正在用水，等他关水退出\n"
                    + "③ 等 10 秒再点重试";
        }
        if (e.contains("超时") || e.contains("timeout")) {
            return "① 站到水表 1 米内\n"
                    + "② 关掉官方 App，别让它抢连接\n"
                    + "③ 点重试；还不行就按提示一步步排查";
        }
        return "① 关掉官方水控 App\n"
                + "② 系统蓝牙里断开 KLCXKJ-Water\n"
                + "③ 点重试；还不行就按提示一步步排查";
    }

    /** 把错误里的关键码抽成一个短标签，比如「错误 133」「超时」「FF02」 */
    private String errorCodeLabel(String err) {
        if (err == null || err.isEmpty()) return "";
        Matcher m = Pattern.compile("(?i)(133|ff0[12]|timeout|超时)").matcher(err);
        if (!m.find()) return "";
        String g = m.group(1).toLowerCase(Locale.ROOT);
        if ("timeout".equals(g) || "超时".equals(g)) return "超时";
        if (g.startsWith("ff")) return g.toUpperCase(Locale.ROOT);
        return "错误 " + g;
    }

    // ================= 排查向导：按实际情况点下一步 =================

    private static final String ACT_SCAN = "scan";
    private static final String ACT_CONNECT = "connect";
    private static final String ACT_CLOSE = "close";

    private static final class DiagOption {
        final String label;
        final String next;
        final String action;

        DiagOption(String label, String next, String action) {
            this.label = label;
            this.next = next;
            this.action = action;
        }
    }

    private static final class DiagStep {
        final String label;
        final String title;
        final String body;
        final DiagOption[] options;

        DiagStep(String label, String title, String body, DiagOption... options) {
            this.label = label;
            this.title = title;
            this.body = body;
            this.options = options;
        }
    }

    private static DiagOption go(String label, String next) {
        return new DiagOption(label, next, null);
    }

    private static DiagOption act(String label, String action) {
        return new DiagOption(label, null, action);
    }

    /**
     * 排查树：
     * A 系列 = 扫不到水表（广播弱 / 距离远 / 金属遮挡 / 干扰 → 蓝牙没开 → 官方 App 对照 → 报修）
     * B 系列 = 扫到了但连不上（基本是被别人占着 → 旧配对 → 水表忙 → 断电重启 → 报修）
     */
    private Map<String, DiagStep> buildDiagSteps() {
        Map<String, DiagStep> m = new LinkedHashMap<>();

        m.put("a1", new DiagStep("排查 · 1 / 3", "附近扫不到水表",
                "先确认三件事：手机蓝牙开着、人离水表 3 米内、中间别隔着铁皮或配电箱。\n\n你现在是什么情况？",
                go("我就在水表旁边（1 米内）", "a2"),
                go("还隔着几米，或者隔着墙 / 铁皮", "a_far"),
                go("不确定手机蓝牙开没开", "a_bt")));

        m.put("a_far", new DiagStep("排查 · 走近再扫", "先走近，再扫一次",
                "水表广播功率很小，通常 3 米内最稳。\n\n"
                        + "① 走到水表跟前，手机贴近表面板\n"
                        + "② 避开铁皮门、配电箱、消防箱这类金属遮挡\n"
                        + "③ 教室 / 宿舍里蓝牙设备太多也会互相干扰，换个角度再扫",
                act("走近了，重新扫描", ACT_SCAN),
                go("就在旁边，还是扫不到", "a2")));

        m.put("a_bt", new DiagStep("排查 · 蓝牙", "先把手机蓝牙打开",
                "① 下拉控制中心打开蓝牙\n"
                        + "② 刚打开的话等 5 秒再扫\n"
                        + "③ 安卓 12 以上要给本 App「附近的设备」权限，否则一个水表都扫不到",
                act("蓝牙已开，重新扫描", ACT_SCAN),
                go("开了还是扫不到", "a2")));

        m.put("a2", new DiagStep("排查 · 2 / 3", "旁边有金属遮挡或很多蓝牙设备吗？",
                "铁皮柜、配电箱、消防箱会挡住信号；周围蓝牙设备太多也会互相干扰。\n\n"
                        + "把手机贴到水表面板上、换个位置再扫一次，成功率会高很多。",
                act("换个位置重新扫描", ACT_SCAN),
                go("贴上去了还是扫不到", "a3")));

        m.put("a3", new DiagStep("排查 · 3 / 3", "用官方 App 对照一下",
                "打开学校官方水控 App（趣智校园 / 水控助手），用它里面的「搜索水表 / 添加设备」找一下这块表。\n\n"
                        + "• 官方 App 也找不到 → 水表根本没在广播，属于设备故障\n"
                        + "• 官方 App 能找到 → 是我们这边的问题，按下一步处理",
                go("官方 App 也找不到", "a_broken"),
                go("官方 App 能找到", "a_ours")));

        m.put("a_broken", new DiagStep("结论 · 建议报修", "水表可能没电或模块故障，报修就行",
                "官方 App 也搜不到，说明这块表没有在广播：常见是电池没电、表内蓝牙模块故障，或者这一片断电了。\n\n"
                        + "报修时把这句话发给对方：\n"
                        + "「KLCXKJ 水表蓝牙搜不到，官方 App 也搜不到，疑似电池或模块故障」\n\n"
                        + "知道水表编号 / MAC 的话一起发过去，师傅能直接定位。",
                act("再扫一次试试", ACT_SCAN),
                act("知道了", ACT_CLOSE)));

        m.put("a_ours", new DiagStep("结论 · 是我们这边的问题", "官方能搜到，按三步恢复",
                "说明水表在正常广播，问题出在手机这一侧：\n\n"
                        + "① 系统蓝牙里找到 KLCXKJ-Water，点「取消配对 / 忘记此设备」\n"
                        + "② 完全退出官方 App（后台也划掉）—— 它连着表时别的设备连不上\n"
                        + "③ 关掉手机蓝牙再打开，回来重新扫描",
                act("三步做完了，重新扫描", ACT_SCAN),
                go("还是不行", "a_last")));

        m.put("a_last", new DiagStep("最后一招", "换台手机对照一下",
                "① 重启手机，清掉蓝牙栈的缓存状态，再扫\n"
                        + "② 用另一台手机装本 App 试：能连上就说明是这台手机的蓝牙问题；两台都扫不到，基本就是水表侧的问题，报修。",
                act("重启后再扫一次", ACT_SCAN),
                act("知道了", ACT_CLOSE)));

        m.put("b1", new DiagStep("排查 · 1 / 3", "连不上这块水表",
                "扫到了却连不上，最常见的原因是：这块表已经被别人连着 —— 别人的手机、还开着的官方 App，"
                        + "或者上一次连接没断干净。\n\n水表一次只接受一个连接。",
                go("我开着官方 App / 有同学正在用", "b_release"),
                go("没人用，官方 App 也关着", "b_paired"),
                go("不确定", "b_check")));

        m.put("b_check", new DiagStep("排查 · 怎么确认", "先看这两个地方",
                "① 后台还有没有官方水控 App 在跑（多任务里划掉）\n"
                        + "② 系统蓝牙里 KLCXKJ-Water 是不是显示「已连接」\n\n"
                        + "任意一条中了，就是被占用。",
                go("确实被占着", "b_release"),
                go("都没有，是干净的", "b_paired")));

        m.put("b_release", new DiagStep("排查 · 让占用方退出", "先让占用方退出，再重试",
                "① 关掉官方水控 App，后台也要划掉\n"
                        + "② 如果是同学正在用水，等他关水退出\n"
                        + "③ 等 10 秒再点重试 —— 水表释放连接要一点时间",
                act("好了，重试连接", ACT_CONNECT),
                go("还是连不上", "b_paired")));

        m.put("b_paired", new DiagStep("排查 · 2 / 3", "清掉系统里的旧配对",
                "系统蓝牙里如果还留着 KLCXKJ-Water 的配对记录，它会一直占着连接：\n\n"
                        + "① 设置 → 蓝牙 → KLCXKJ-Water → 取消配对 / 忘记此设备\n"
                        + "② 关掉蓝牙再打开\n"
                        + "③ 回来重新扫描再连",
                act("清完了，重试连接", ACT_CONNECT),
                go("还是连不上", "b_busy")));

        m.put("b_busy", new DiagStep("排查 · 3 / 3", "水表是不是正在放水 / 结算",
                "水表在放水、刷卡、结算中时，蓝牙通道是忙的，连上也会被踢掉。\n\n"
                        + "① 等 1 分钟，或者把水关掉再试\n"
                        + "② 连上后如果状态是「结算中」，点开水会自动补结算，不用手动处理",
                act("等过了，重试连接", ACT_CONNECT),
                go("还是连不上", "b_power")));

        m.put("b_power", new DiagStep("最后一招", "给水表断电重启",
                "① 拔掉水表电池 / 关掉这一路空开，等 10 秒再上电\n"
                        + "② 上电后 30 秒内重新扫描连接\n\n"
                        + "断电重启还是连不上、而官方 App 能连的话，把手机型号和这块表的 MAC 发我，我按日志查。",
                act("重启过了，重试连接", ACT_CONNECT),
                go("官方 App 也连不上", "a_broken"),
                act("知道了", ACT_CLOSE)));

        return m;
    }

    /** 排查向导：一个弹窗里换步骤，带「返回上一步」 */
    private void showDiagWizard(String startId) {
        if (isFinishing() || isDestroyed()) return;
        final Map<String, DiagStep> steps = buildDiagSteps();
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final LinearLayout card = dialogCard();
        final Deque<String> stack = new ArrayDeque<>();
        final String[] cur = {startId};
        final Runnable[] render = new Runnable[1];

        render[0] = () -> {
            DiagStep st = steps.get(cur[0]);
            if (st == null) {
                dialog.dismiss();
                return;
            }
            card.removeAllViews();
            card.addView(dialogLabel(st.label));
            card.addView(dialogTitle(st.title));
            TextView body = dialogNote(st.body);
            body.setTextSize(12.5f);
            body.setLineSpacing(dp(4), 1f);
            body.setPadding(0, dp(12), 0, 0);
            card.addView(body);

            for (final DiagOption op : st.options) {
                TextView b = diagOptionButton(op.label);
                b.setOnClickListener(v -> {
                    tap(v);
                    if (op.next != null) {
                        stack.push(cur[0]);
                        cur[0] = op.next;
                        render[0].run();
                    } else {
                        dialog.dismiss();
                        doDiagAction(op.action);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                card.addView(b, lp);
            }

            if (!stack.isEmpty()) {
                TextView back = dialogGhost("返回上一步");
                back.setOnClickListener(v -> {
                    tap(v);
                    if (!stack.isEmpty()) {
                        cur[0] = stack.pop();
                        render[0].run();
                    }
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
                blp.topMargin = dp(6);
                card.addView(back, blp);
            }

            TextView close = dialogGhost("关闭");
            close.setOnClickListener(v -> {
                tap(v);
                dialog.dismiss();
            });
            card.addView(close, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        };

        render[0].run();
        showStyledDialog(dialog, card, 0.9f);
    }

    private TextView diagOptionButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12.5f);
        v.setTextColor(getColor(R.color.primary));
        v.setBackgroundResource(R.drawable.bg_button_ghost);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(14), dp(12), dp(14), dp(12));
        v.setMinHeight(dp(44));
        return v;
    }

    private void doDiagAction(String action) {
        if (ACT_SCAN.equals(action)) {
            if (btnScan != null) btnScan.performClick();
        } else if (ACT_CONNECT.equals(action)) {
            if (connectingItem != null) {
                connectTo(connectingItem);
            } else {
                toast("先扫描，点一下自己的水表");
            }
        }
    }

    // ---- 弹窗零件：和公告 / 更新弹窗同一套圆角卡片风格 ----

    private LinearLayout dialogCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(16));
        box.setBackgroundResource(R.drawable.bg_dialog);
        return box;
    }

    private TextView dialogLabel(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(10);
        v.setLetterSpacing(0.18f);
        v.setTextColor(getColor(R.color.primary));
        return v;
    }

    private TextView dialogTitle(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(20);
        v.setTextColor(getColor(R.color.ink));
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView dialogSub(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12.5f);
        v.setTextColor(getColor(R.color.muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView dialogBody(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13.5f);
        v.setTextColor(getColor(R.color.ink));
        v.setLineSpacing(dp(3), 1f);
        return v;
    }

    private TextView dialogNote(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(11.5f);
        v.setTextColor(getColor(R.color.muted));
        v.setLineSpacing(dp(4), 1f);
        return v;
    }

    private TextView dialogChip(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(11);
        v.setTextColor(getColor(R.color.muted));
        v.setBackgroundResource(R.drawable.bg_chip_light);
        v.setPadding(dp(10), dp(5), dp(10), dp(5));
        v.setSingleLine(true);
        return v;
    }

    private Button dialogPrimary(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setBackgroundResource(R.drawable.bg_button_primary);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(16);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView dialogGhost(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(getColor(R.color.primary));
        v.setGravity(Gravity.CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        return v;
    }

    private void showStyledDialog(Dialog d, View content, float widthRatio) {
        d.setContentView(content);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.35f);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * widthRatio),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        content.setAlpha(0f);
        content.setScaleX(0.96f);
        content.setScaleY(0.96f);
        content.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
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
        renderHistory();
    }

    /** 省钱账本下面的「最近记录」：时间 · 用量 · 时长 · 省下 */
    private void renderHistory() {
        if (statHistory == null) return;
        statHistory.removeAllViews();
        org.json.JSONArray arr = manager.recentSessions(5);
        if (arr.length() == 0) return;
        TextView head = new TextView(this);
        head.setText("最近记录");
        head.setTextSize(10);
        head.setTextColor(getColor(R.color.muted));
        head.setPadding(0, 0, 0, dp(2));
        statHistory.addView(head);
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (int i = arr.length() - 1; i >= 0; i--) {
            org.json.JSONObject r = arr.optJSONObject(i);
            if (r == null) continue;
            String when = f.format(new Date(r.optLong("ts", 0)));
            // 左：时间 + 用量 + 时长；右：省下的钱（右对齐，方便扫一眼对比）
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView left = new TextView(this);
            left.setText(String.format(Locale.CHINA, "%s · %s%.1f L · %d 秒",
                    when, r.optBoolean("estimated", false) ? "约 " : "",
                    r.optDouble("liters", 0), r.optInt("seconds", 0)));
            left.setTextSize(11.5f);
            left.setTextColor(getColor(R.color.ink));
            left.setSingleLine(true);
            left.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(left, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView right = new TextView(this);
            right.setText(String.format(Locale.CHINA, "省 ¥%.2f", r.optDouble("saved", 0)));
            right.setTextSize(11.5f);
            right.setTextColor(getColor(R.color.success));
            right.setSingleLine(true);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.leftMargin = dp(10);
            row.addView(right, rlp);
            statHistory.addView(row);
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
        final int sy = scroll == null ? 0 : scroll.getScrollY();
        btnSendCode.setEnabled(!busy);
        btnLogin.setEnabled(!busy);
        btnScan.setEnabled(!busy);
        btnQuery.setEnabled(!busy);
        // 开水/关水不禁用：禁用会让焦点乱跳、整页跟着滚；重复点击在点击回调里挡
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
        if (scroll != null) scroll.post(() -> {
            if (scroll.getScrollY() == 0 && sy > 0) scroll.scrollTo(0, sy);   // 只修「被顶回顶部」，不抢用户的滚动
        });
    }

    @Override
    public void onWater(boolean running) {
        final int sy = scroll == null ? 0 : scroll.getScrollY();
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
        if (scroll != null) scroll.post(() -> {
            if (scroll.getScrollY() == 0 && sy > 0) scroll.scrollTo(0, sy);   // 只修「被顶回顶部」，不抢用户的滚动
        });
    }

    @Override
    public void onSession(Cloud.Session session) {
        renderSession(session);
    }

    @Override
    public void onDevices(List<ValveManager.ScanItem> devices) {
        deviceList.removeAllViews();
        if (connected) return;          // 已经连上了就不再显示列表
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
            tv.setText("没扫到水表 · 点这里一步步排查（信号弱 / 蓝牙没开 / 设备故障）");
            tv.setTextSize(12);
            tv.setTextColor(getColor(R.color.muted));
            tv.setPadding(dp(8), dp(10), dp(8), dp(6));
            tv.setOnClickListener(v -> {
                tap(v);
                showDiagWizard("a1");
            });
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
        if (connected) {
            // 连上以后列表就没用了：清掉，别再占着屏幕
            if (deviceList != null) deviceList.removeAllViews();
            btnScan.setText("重新扫描");
        }
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
