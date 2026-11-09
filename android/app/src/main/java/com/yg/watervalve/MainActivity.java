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
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
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
    private TextView btnNotice;
    private LinearLayout noticeBar;
    private Announcement.Info currentNotice;
    private LinearLayout deviceList, header, bottomBar, loginFields, logBody, statHistory;
    private ScrollView logScroll;
    private View root;
    private String lastInsetKey = "";
    private ScrollView scroll;
    private View content, heroBlock, accountCard, deviceCard, statsCard, logCard;
    private final StringBuilder logBuf = new StringBuilder();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private boolean running;
    private boolean connected;
    private boolean sawBound;      // 这次扫描里有没有出现「我的水表」
    private TextView scanHint;
    private boolean logExpanded;
    private boolean logFlushScheduled;
    /** 日志框是不是贴着底部：用户往上翻看时不再自动跟到底 */
    private boolean logStickBottom = true;
    private Dialog downloadDialog;
    private ProgressBar downloadBar;
    private TextView downloadPct;
    private Updater.Info pendingUpdate;
    private String lastHeroTitle = "";

    // 独立登录页
    private ScrollView loginGate;
    private LinearLayout gateBody;
    private EditText gatePhone, gateCode;
    private TextView gateSend, gateHint, gateSkip, banner;
    private Button gateLogin;
    private boolean gateSkipped;
    private String lastGateCode = "";
    private long lastGateCodeAt;
    private long gateCodeSentAt;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    // 连接过程反馈
    private Dialog connectDialog;
    private Dialog failDialog;
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
        btnNotice = findViewById(R.id.btnNotice);
        deviceList = findViewById(R.id.deviceList);
        scanHint = findViewById(R.id.scanHint);
        statHistory = findViewById(R.id.statHistory);
        noticeBar = findViewById(R.id.noticeBar);
        header = findViewById(R.id.header);
        bottomBar = findViewById(R.id.bottomBar);
        root = findViewById(R.id.root);
        scroll = findViewById(R.id.scroll);
        content = findViewById(R.id.content);
        heroBlock = findViewById(R.id.heroBlock);
        accountCard = findViewById(R.id.accountCard);
        deviceCard = findViewById(R.id.deviceCard);
        statsCard = findViewById(R.id.statsCard);
        logCard = findViewById(R.id.logCard);
        loginGate = findViewById(R.id.loginGate);
        gateBody = findViewById(R.id.gateBody);
        gatePhone = findViewById(R.id.gatePhone);
        gateCode = findViewById(R.id.gateCode);
        gateSend = findViewById(R.id.gateSend);
        gateLogin = findViewById(R.id.gateLogin);
        gateHint = findViewById(R.id.gateHint);
        gateSkip = findViewById(R.id.gateSkip);
        banner = findViewById(R.id.banner);
        setupLoginGate();

        setupWindow();
        // 等第一帧出来再收一次系统栏：权限弹窗、公告弹窗都可能把它放回来
        root.post(() -> FullScreen.hideBars(this));
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
        btnWater.setTextColor(running ? getColor(R.color.btn_stop_text) : Color.WHITE);
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
            sawBound = false;
            hideScanHint();
            manager.scan();
            // 2 秒还没看到自己的水表 → 露出「找不到自己的水表？」入口
            uiHandler.postDelayed(this::maybeShowScanHint, 2000);
            btnScan.postDelayed(() -> {
                if (!manager.isBusy()) btnScan.setText("扫描水表");
            }, 16000);
        });
        if (scanHint != null) {
            scanHint.setOnClickListener(v -> {
                tap(v);
                showDiagWizard("a1");
            });
        }
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
        View btnShareLog = findViewById(R.id.btnShareLog);
        if (btnShareLog != null) {
            btnShareLog.setOnClickListener(v -> {
                tap(v);
                LogShare.share(this);
            });
        }
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
                logStickBottom = true;
                logView.setText(logBuf);
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
        noticeBar.setOnClickListener(v -> {
            tap(v);
            if (currentNotice != null) showAnnouncementDialog(currentNotice);
        });
        requestPerms();
        appendLog("安卓版启动，版本 " + Updater.currentVersion(this));
        String crash = CrashLog.last(this);
        if (!crash.isEmpty()) {
            appendLog("上次崩溃日志：\n" + crash.substring(0, Math.min(1500, crash.length())));
            CrashLog.clear(this);
        }
        // 崩溃时已经自动打包好的日志：这次打开就问一句要不要直接发出去
        String pendingShare = getSharedPreferences("water_valve", MODE_PRIVATE)
                .getString("pending_share", "");
        if (pendingShare != null && !pendingShare.isEmpty()) {
            getSharedPreferences("water_valve", MODE_PRIVATE)
                    .edit().remove("pending_share").apply();
            final String shareName = pendingShare;
            new Handler(Looper.getMainLooper())
                    .postDelayed(() -> offerCrashShare(shareName), 900);
        }
        setupInteractions();
        Appearance.apply(this);
        View gear = findViewById(R.id.btnSettings);
        if (gear != null) {
            gear.setOnClickListener(v -> {
                tap(v);
                startActivity(new Intent(this, SettingsActivity.class));
            });
        }
        if (getIntent() != null && getIntent().getBooleanExtra("show_update", false)) {
            getIntent().removeExtra("show_update");
            new Handler(Looper.getMainLooper()).postDelayed(this::checkUpdateOnLaunch, 250);
        }
        new Handler(Looper.getMainLooper()).postDelayed(this::checkAnnouncement, 2500);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing()) checkUpdateOnLaunch();
        }, 300);
    }

    private void updateXiaolianButton() {
        // 0.6.1 起改用趣智校园 /device/info/mac，「同步官方宿舍名」入口已下线，这里保留空实现
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
                if (noticeBar != null) noticeBar.setVisibility(View.GONE);
                return;
            }
            currentNotice = info;
            if (noticeBar != null) {
                String title = info.title == null || info.title.trim().isEmpty()
                        ? "有新公告" : info.title.trim();
                btnNotice.setText(title);
                noticeBar.setVisibility(View.VISIBLE);
                noticeBar.setAlpha(0f);
                noticeBar.setTranslationY(-dp(6));
                noticeBar.animate().alpha(1f).translationY(0f).setDuration(220)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
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

    // ================= 启动即检查更新 =================

    private String skippedVersion() {
        return getSharedPreferences("ui", MODE_PRIVATE).getString("skip_version", "");
    }

    private void skipVersion(String v) {
        getSharedPreferences("ui", MODE_PRIVATE).edit()
                .putString("skip_version", v == null ? "" : v).apply();
    }

    /**
     * 启动后尽快查一次：发现新版本直接弹窗，不用等用户自己去点。
     * 用户主动「跳过这个版本」的，只在底部留一行提示。
     */
    private void checkUpdateOnLaunch() {
        if (isFinishing() || isDestroyed()) return;
        if (manager.isRunning() || manager.hasOpenSession()) return;
        Updater.check(this, (info, error) -> {
            if (isFinishing() || isDestroyed()) return;
            if (error != null) {
                appendLog("检查更新失败：" + error);
                return;
            }
            if (info == null || !info.hasUpdate) {
                appendLog("检查更新：当前版本已是最新");
                return;
            }
            appendLog("检查更新：发现新版本 v" + info.version);
            if (info.version.equals(skippedVersion())) {
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
        LinearLayout notesList = v.findViewById(R.id.dialogNotesList);
        ScrollView notesScroll = v.findViewById(R.id.dialogNotesScroll);
        Button install = v.findViewById(R.id.dialogInstall);
        TextView later = v.findViewById(R.id.dialogLater);

        version.setText("v" + Updater.currentVersion(this));
        meta.setText("已是最新版本");
        fillNotes(notesList, "当前版本已是最新。");
        View flow = v.findViewById(R.id.dialogFlow);
        if (flow != null) flow.setVisibility(View.GONE);
        View skip = v.findViewById(R.id.dialogSkip);
        if (skip != null) skip.setVisibility(View.GONE);
        install.setVisibility(View.GONE);
        later.setText("关闭");

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(v);
        dialog.setCanceledOnTouchOutside(true);
        later.setOnClickListener(x -> dialog.dismiss());
        dialog.show();
        capNotesHeight(notesScroll, notesList);
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
        TextView from = v.findViewById(R.id.dialogFrom);
        TextView to = v.findViewById(R.id.dialogTo);
        TextView count = v.findViewById(R.id.dialogCount);
        LinearLayout notesList = v.findViewById(R.id.dialogNotesList);
        ScrollView notesScroll = v.findViewById(R.id.dialogNotesScroll);
        Button install = v.findViewById(R.id.dialogInstall);
        TextView later = v.findViewById(R.id.dialogLater);

        String cur = Updater.currentVersion(this);
        version.setText("v" + info.version);
        meta.setText(mb(info.size) + " · 换个网更快就重试一次");
        from.setText("当前 v" + cur);
        to.setText("最新 v" + info.version);
        String notes = info.notes == null ? "" : info.notes.trim();
        fillNotes(notesList, notes.isEmpty() ? "包含界面与稳定性更新。" : notes);
        int secs = countSections(notes);
        if (secs > 1) {
            count.setText("共 " + secs + " 个版本的更新，往下滑看全部");
            count.setVisibility(View.VISIBLE);
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(v);
        dialog.setCanceledOnTouchOutside(true);
        install.setOnClickListener(x -> {
            DebugLog.op("更新：点安装 v" + info.version);
            dialog.dismiss();
            if (!Updater.canInstall(this)) {
                Updater.openInstallSettings(this);
                toast("请允许安装未知应用后再试");
                return;
            }
            downloadUpdate(info);
        });
        later.setOnClickListener(x -> {
            DebugLog.op("更新：点稍后");
            dialog.dismiss();
        });
        TextView skip = v.findViewById(R.id.dialogSkip);
        if (skip != null) {
            skip.setText("跳过 v" + info.version);
            skip.setOnClickListener(x -> {
                DebugLog.op("更新：跳过 v" + info.version);
                skipVersion(info.version);
                dialog.dismiss();
                toast("已跳过 v" + info.version + "，需要时点「检查更新」");
            });
        }
        dialog.show();
        capNotesHeight(notesScroll, notesList);
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

    /** 更新说明里有几段「## vX.Y.Z」→ 这次跨了几个版本 */
    private int countSections(String notes) {
        if (notes == null || notes.isEmpty()) return 0;
        int n = 0;
        for (String line : notes.split("\n")) {
            if (line.trim().startsWith("## v")) n++;
        }
        return n;
    }

    private void downloadUpdate(Updater.Info info) {
        if (isFinishing() || isDestroyed()) return;
        if (manager.isRunning() || manager.hasOpenSession()) {
            toast("放水中，先关水再下载更新");
            return;
        }
        showDownloadDialog(info);
        Updater.download(this, info,
                (pct, bytes, total) -> runOnUiThread(() -> {
                    if (downloadPct == null) return;
                    if (pct < 0) {
                        downloadPct.setText("正在同时连接几个下载源…");
                    } else {
                        if (downloadBar != null) {
                            downloadBar.setProgress(Math.max(0, Math.min(1000, pct * 10)));
                        }
                        downloadPct.setText(pct + "% · " + mb(bytes) + " / " + mb(total));
                    }
                }),
                (file, error) -> runOnUiThread(() -> {
                    if (downloadDialog != null) {
                        downloadDialog.dismiss();
                        downloadDialog = null;
                        downloadBar = null;
                        downloadPct = null;
                    }
                    if (isFinishing() || isDestroyed()) return;
                    if (error != null) {
                        LinearLayout box = dialogCard();
                        box.addView(dialogLabel("DOWNLOAD FAILED"));
                        box.addView(dialogTitle("下载没成功"));
                        TextView body = dialogBody(error + "\n\n几个镜像都没下下来，可以换到浏览器直接下载。");
                        body.setPadding(0, dp(14), 0, 0);
                        box.addView(body);
                        final Dialog d = new Dialog(this);
                        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
                        Button browser = dialogPrimary("浏览器下载");
                        browser.setOnClickListener(x -> {
                            tap(x);
                            d.dismiss();
                            openDownloadInBrowser(info);
                        });
                        box.addView(browser);
                        TextView close = dialogGhost("关闭");
                        close.setOnClickListener(x -> {
                            tap(x);
                            d.dismiss();
                        });
                        box.addView(close, new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
                        showStyledDialog(d, box, 0.9f);
                        return;
                    }
                    installUpdate(file);
                }));
    }

    private void showDownloadDialog(Updater.Info info) {
        LinearLayout box = dialogCard();
        box.addView(dialogLabel("DOWNLOADING"));
        box.addView(dialogTitle("正在下载更新"));
        box.addView(dialogSub("v" + info.version + " · " + mb(info.size)
                + " · 几个镜像同时下，谁快用谁"));

        ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.primary)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(getColor(R.color.outline)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        blp.topMargin = dp(18);
        box.addView(bar, blp);

        TextView pct = dialogNote("正在同时连接几个下载源…");
        pct.setPadding(0, dp(9), 0, 0);
        box.addView(pct);

        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCanceledOnTouchOutside(false);
        showStyledDialog(d, box, 0.86f);
        downloadDialog = d;
        downloadBar = bar;
        downloadPct = pct;
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

    /**
     * 更新说明按条目渲染：一行一条、带圆点，二级条目缩进换色，长文本自动换行。
     * 以前是一整块文字，条目挤在一起，读起来很累。
     */
    private void fillNotes(LinearLayout list, String notes) {
        if (list == null) return;
        list.removeAllViews();
        boolean first = true;
        for (String raw : notes.replace("\r", "").split("\n")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            // 「## v0.8.3」这种行渲染成版本小标题（跨版本更新时按版本分段）
            if (t.startsWith("## ")) {
                TextView head = new TextView(this);
                head.setText(t.substring(3).trim());
                head.setTextSize(12);
                head.setLetterSpacing(0.06f);
                head.setTextColor(getColor(R.color.primary));
                head.setTypeface(null, android.graphics.Typeface.BOLD);
                head.setPadding(0, first ? 0 : dp(16), 0, dp(2));
                list.addView(head);
                first = false;
                continue;
            }
            boolean sub = raw.startsWith("  ") || raw.startsWith("\t");
            String text = t.replaceFirst("^[•·\\-*+]\\s*", "");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(sub ? dp(18) : 0, first ? 0 : dp(8), 0, 0);
            TextView dot = new TextView(this);
            dot.setText(sub ? "·" : "•");
            dot.setTextSize(sub ? 15 : 12);
            dot.setTextColor(getColor(sub ? R.color.muted : R.color.primary));
            dot.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(14), LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(dot);
            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTextSize(sub ? 12f : 13f);
            tv.setTextColor(getColor(sub ? R.color.muted : R.color.ink));
            tv.setLineSpacing(dp(3), 1f);
            row.addView(tv, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            list.addView(row);
            first = false;
        }
    }

    private String mb(long bytes) {
        return String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0);
    }

    /**
     * 更新说明最多占屏幕 40%，超了就在框里滚动 ——
     * 以前是 maxLines=7 + 省略号，条目一多就看不全。
     */
    private void capNotesHeight(final ScrollView scroll, final View notes) {
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
        // 全屏：窗口铺进挖孔区、收起系统栏；页面内容只避让挖孔，背景图/渐变铺满整屏
        final int contentTopBase = content == null ? 0 : content.getPaddingTop();
        final int contentBottom = content == null ? 0 : content.getPaddingBottom();
        final int bottomPad = bottomBar.getPaddingBottom();
        final int gateTop = gateBody == null ? 0 : gateBody.getPaddingTop();
        final int gateBottom = gateBody == null ? 0 : gateBody.getPaddingBottom();
        FullScreen.apply(this, root, (cutoutTop, contentTop, bottom, ime) -> {
            String key = cutoutTop + "/" + contentTop + "/" + bottom + "/" + ime;
            if (!key.equals(lastInsetKey)) {
                lastInsetKey = key;
                DebugLog.line("ui", "insets cutout=" + cutoutTop + " content=" + contentTop
                        + " bottom=" + bottom + " ime=" + ime);
            }
            if (content != null) {
                content.setPadding(content.getPaddingLeft(), contentTopBase + contentTop,
                        content.getPaddingRight(), contentBottom);
            }
            bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(),
                    bottomBar.getPaddingRight(), bottomPad + bottom);
            if (gateBody != null) {
                // 登录页内容是居中的，小圆孔也要让开，所以这里用挖孔原始高度
                gateBody.setPadding(gateBody.getPaddingLeft(), gateTop + cutoutTop,
                        gateBody.getPaddingRight(), gateBottom + ime);
            }
        });
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
        // 自动提交和手动点「登录」可能同时触发：第二次请求会拿着已经用掉的验证码，
        // 云端直接回「验证码已失效」，日志里就会出现「登录成功」后紧跟一条「登录失败」。
        // 现在按「同一个验证码 20 秒内只提交一次」去重，会话失效时也能正常重新登录。
        if (manager.isBusy()) {
            DebugLog.op("登录请求被忽略：上一个操作还在进行");
            return;
        }
        String p = gatePhone.getText().toString().trim();
        String c = gateCode.getText().toString().trim();
        if (p.length() != 11) {
            DebugLog.op("登录中止：手机号不是 11 位（" + p.length() + "）");
            gateHint.setText("手机号要 11 位");
            return;
        }
        if (c.isEmpty()) {
            DebugLog.op("登录中止：验证码为空");
            gateHint.setText("先填验证码");
            return;
        }
        if (c.equals(lastGateCode) && System.currentTimeMillis() - lastGateCodeAt < 20000) {
            DebugLog.op("登录请求被忽略：同一个验证码 20 秒内重复提交");
            return;
        }
        lastGateCode = c;
        lastGateCodeAt = System.currentTimeMillis();
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
            // 已经登录成功的话，后到的失败响应（重复请求）不该把界面刷成失败
            if (manager.getSession() != null) {
                appendLog("忽略重复的登录响应：" + msg);
                return;
            }
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
        hideScanHint();
        if (connectDialog != null && connectDialog.isShowing()) connectDialog.dismiss();
        if (failDialog != null && failDialog.isShowing()) {
            failDialog.dismiss();
            failDialog = null;
        }

        LinearLayout box = dialogCard();
        box.addView(dialogLabel("CONNECTING"));
        box.addView(dialogTitle("正在连接"));
        box.addView(dialogSub(name));

        final ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.primary)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(getColor(R.color.outline)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        blp.topMargin = dp(16);
        box.addView(bar, blp);

        final TextView elapse = dialogNote("已等 0 秒 · 最多 5 秒");
        elapse.setPadding(0, dp(9), 0, 0);
        box.addView(elapse);

        TextView note = dialogNote("正常情况下 1 秒内就能连上；超过 5 秒基本就是被别人占着连接。");
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

        // 进度条按 5 秒线性推满：一帧一帧走，不再一秒跳一格
        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(CONNECT_WAIT_MS);
        anim.setInterpolator(new LinearInterpolator());
        final int[] shownSec = {-1};
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            bar.setProgress((int) (f * 1000));
            int s = Math.min(5, (int) Math.floor(f * 5));
            if (s != shownSec[0]) {
                shownSec[0] = s;
                elapse.setText(s < 3 ? "已等 " + s + " 秒 · 最多 5 秒"
                        : "已等 " + s + " 秒 · 有人在占着连接…");
            }
        });
        anim.start();
        dialog.setOnDismissListener(d -> anim.cancel());

        final long t0 = System.currentTimeMillis();
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (connectDialog != dialog) return;
                if (!dialog.isShowing()) {
                    anim.cancel();
                    return;
                }
                if (manager.isConnected()) {
                    anim.cancel();
                    dialog.dismiss();
                    connectDialog = null;
                    connected = true;
                    renderWaterStatus();
                    toast("✓ 已连接 " + name);
                    manager.query();
                    return;
                }
                if (System.currentTimeMillis() - t0 >= CONNECT_WAIT_MS) {
                    anim.cancel();
                    dialog.dismiss();
                    connectDialog = null;
                    showConnectFailed(item);
                    return;
                }
                uiHandler.postDelayed(this, 150);
            }
        });
    }

    /** 开水按钮底色跟着主题走；关水固定红色 */
    private void styleWaterButton(boolean stop) {
        if (btnWater == null) return;
        if (stop) {
            btnWater.setBackgroundResource(R.drawable.bg_button_ink);
            btnWater.setTextColor(getColor(R.color.btn_stop_text));
        } else {
            int p = Appearance.primary(this);
            btnWater.setBackground(Appearance.pill(this, Appearance.lift(p, 0.14f),
                    Appearance.mix(p, 0xFF000000, 0.14f)));
            btnWater.setTextColor(Color.WHITE);
        }
    }

    private void maybeShowScanHint() {
        if (isFinishing() || isDestroyed()) return;
        if (scanHint == null || connected || sawBound) return;
        if (scanHint.getVisibility() == View.VISIBLE) return;
        scanHint.setVisibility(View.VISIBLE);
        scanHint.setAlpha(0f);
        scanHint.setTranslationY(dp(6));
        scanHint.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void hideScanHint() {
        if (scanHint == null) return;
        scanHint.setVisibility(View.GONE);
    }

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

        TextView probe = dialogTonal("检测是否被占用（约 10 秒）");
        probe.setOnClickListener(v -> {
            tap(v);
            dialog.dismiss();
            probeOccupancy(item);
        });
        box.addView(probe);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView fix = dialogGhost("排查步骤");
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
        failDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (failDialog == dialog) failDialog = null;
        });
    }

    /**
     * 扫到了却连不上，只有一种可能：这块表已经被别人连着（宿舍里谁的手机、官方 App 都算）。
     * 水表一次只接受一个连接，所以别再往「信号弱」上引。
     */
    private String connectCause(String err) {
        String e = err == null ? "" : err.toLowerCase(Locale.ROOT);
        if (e.contains("ff02") || e.contains("ff01") || e.contains("特征")) {
            return "连上了，但没找到 FF02 / FF01 特征：可能连到了别的设备。";
        }
        return "扫到了却连不上，一定是有人正占着这块表的连接（宿舍里谁的手机连着它）。";
    }

    /** 两步：问宿舍的人 / 点重试 */
    private String connectSteps(String err) {
        String e = err == null ? "" : err.toLowerCase(Locale.ROOT);
        if (e.contains("ff02") || e.contains("ff01") || e.contains("特征")) {
            return "① 确认选的是 KLCXKJ-Water\n"
                    + "② 断开后重新扫描再连";
        }
        return "① 问一下宿舍里的人：是不是有人连了水表？让大家把蓝牙都关掉\n"
                + "② 点「重试」";
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
    private static final String ACT_PROBE = "probe";
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

        m.put("b1", new DiagStep("排查 · 1 / 2", "连不上，一定是有人占着连接",
                "扫到了却连不上，只有一种可能：这块表已经被别人连着 —— 宿舍里谁的手机还连着它。\n\n"
                        + "水表一次只接受一个连接，别人不断开，谁也连不上。",
                go("去问一圈：谁连着水表", "b_check"),
                go("问过了，都说没连", "b_again"),
                go("室友不在 / 问不到", "b_wait")));

        m.put("b_check", new DiagStep("排查 · 怎么确认", "确认一下是谁连着",
                "① 问刚才谁开过水、谁手机蓝牙里 KLCXKJ-Water 显示「已连接」\n"
                        + "② 让你自己手机也看一眼：蓝牙列表里是不是还连着它\n"
                        + "③ 对方把手机蓝牙关掉，连接立刻就断",
                act("他关了 → 重试连接", ACT_CONNECT),
                go("都问过了，都说没连", "b_again"),
                go("室友不在，问不到", "b_wait")));

        m.put("b_again", new DiagStep("排查 · 2 / 2", "那就再确认一次",
                "有时候对方刚断开，水表还没释放连接，隔 10 秒再连一次通常就好了。\n\n"
                        + "如果还是连不上，跑一次占用检测：连续 3 次快速连接，全失败基本就能确定是被占用。",
                act("现在重试连接", ACT_CONNECT),
                act("跑一次占用检测（约 10 秒）", ACT_PROBE),
                go("室友不在，先等等", "b_wait")));

        m.put("b_wait", new DiagStep("排查 · 等一等", "等几分钟，或者直接让人关蓝牙",
                "对方手机长时间不用，水表会把连接踢掉，一般 3–5 分钟。\n\n"
                        + "等不及的话最直接：回宿舍喊一声，谁连着水表先把蓝牙关一下。",
                act("等过了 → 重试连接", ACT_CONNECT),
                act("跑一次占用检测", ACT_PROBE),
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
        } else if (ACT_PROBE.equals(action)) {
            probeOccupancy(connectingItem);
        }
    }

    // ---- 占用检测：连续 3 次快速连接，全失败基本就是被别人连着 ----

    private static final int PROBE_ATTEMPTS = 3;
    private static final long PROBE_WINDOW_MS = 3000;
    /** 连接等待上限：宿舍里正常 1 秒内连上，5 秒还没连上就是被占用 */
    private static final long CONNECT_WAIT_MS = 5000;

    private void probeOccupancy(final ValveManager.ScanItem item) {
        if (item == null) {
            toast("先扫描，点一下自己的水表");
            return;
        }
        if (manager.isRunning() || manager.hasOpenSession()) {
            toast("正在放水，先关水再检测");
            return;
        }
        if (manager.isConnected()) {
            toast("已经连着这块水表了");
            return;
        }
        final String name = item.name == null || item.name.isEmpty() ? "水表" : item.name;

        final LinearLayout box = dialogCard();
        box.addView(dialogLabel("OCCUPANCY"));
        box.addView(dialogTitle("检测是否被占用"));
        box.addView(dialogSub(name));
        final TextView status = dialogNote("准备开始，最多 10 秒…");
        status.setTextSize(12.5f);
        status.setPadding(0, dp(12), 0, 0);
        box.addView(status);
        final LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(10);
        box.addView(lines, llp);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        TextView cancel = dialogGhost("取消检测");
        cancel.setOnClickListener(v -> {
            tap(v);
            manager.cancelConnect();
            dialog.dismiss();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        clp.topMargin = dp(6);
        box.addView(cancel, clp);
        showStyledDialog(dialog, box, 0.88f);

        probeAttempt(item, dialog, status, lines, 1, new ArrayList<String>());
    }

    private void probeAttempt(final ValveManager.ScanItem item, final Dialog dialog,
                              final TextView status, final LinearLayout lines,
                              final int attempt, final List<String> results) {
        if (attempt > PROBE_ATTEMPTS) {
            probeVerdict(dialog, results);
            return;
        }
        status.setText("第 " + attempt + " / " + PROBE_ATTEMPTS + " 次：正在连接…");
        manager.connect(item);
        final long t0 = System.currentTimeMillis();
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) {
                    manager.cancelConnect();
                    return;
                }
                if (manager.isConnected()) {
                    probeLine(lines, "第 " + attempt + " 次：连上了 ✓", R.color.success);
                    probeVerdict(dialog, results);
                    return;
                }
                long ms = System.currentTimeMillis() - t0;
                if (manager.isBusy() && ms < PROBE_WINDOW_MS) {
                    uiHandler.postDelayed(this, 200);
                    return;
                }
                // 3 秒还没连上（或者已经报错）：中断这次尝试，记下结果
                String tag = manager.isBusy() ? "超时"
                        : shortErr(manager.getLastConnectError());
                if (manager.isBusy()) manager.cancelConnect();
                results.add(tag);
                probeLine(lines, "第 " + attempt + " 次：连不上（" + tag + "）", R.color.danger);
                uiHandler.postDelayed(() -> probeAttempt(item, dialog, status, lines,
                        attempt + 1, results), 450);
            }
        });
    }

    private void probeVerdict(Dialog dialog, List<String> results) {
        dialog.dismiss();
        if (manager.isConnected()) {
            connected = true;
            renderWaterStatus();
            toast("✓ 没有被占用，已经连上了");
            manager.query();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String r : results) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(r);
        }
        LinearLayout box = dialogCard();
        box.addView(dialogLabel("RESULT"));
        box.addView(dialogTitle("基本可以确定：有人正连着这块表"));
        TextView body = dialogBody("连续 " + PROBE_ATTEMPTS + " 次都连不上（" + sb + "）。\n\n"
                + "水表一次只接受一个连接 —— 让对方把手机蓝牙关掉（关蓝牙就会断开），"
                + "或者等 3–5 分钟水表自己把连接踢掉，再点重试。");
        body.setPadding(0, dp(14), 0, 0);
        box.addView(body);
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Button retry = dialogPrimary("重试连接");
        retry.setOnClickListener(v -> {
            tap(v);
            d.dismiss();
            if (connectingItem != null) connectTo(connectingItem);
        });
        box.addView(retry);
        TextView close = dialogGhost("关闭");
        close.setOnClickListener(v -> {
            tap(v);
            d.dismiss();
        });
        box.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        showStyledDialog(d, box, 0.9f);
    }

    private void probeLine(LinearLayout lines, String text, int colorRes) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(getColor(colorRes));
        tv.setPadding(0, dp(3), 0, dp(3));
        lines.addView(tv);
    }

    private String shortErr(String err) {
        if (err == null || err.isEmpty()) return "失败";
        String e = err.toLowerCase(Locale.ROOT);
        if (e.contains("133")) return "133";
        if (e.contains("超时") || e.contains("timeout")) return "超时";
        if (e.contains("ff02") || e.contains("ff01")) return "FF02";
        return "失败";
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

    /** 上次崩溃时自动打包的日志：问一句要不要直接分享出去 */
    private void offerCrashShare(final String name) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(12));
        box.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText("上次异常退出");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.ink));
        box.addView(title);

        TextView note = new TextView(this);
        note.setText("日志已经自动打包好了（含崩溃堆栈和最后的操作），可以直接分享给开发者定位问题。");
        note.setTextSize(12);
        note.setLineSpacing(dp(2), 1f);
        note.setTextColor(getColor(R.color.muted));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10);
        box.addView(note, nlp);

        Button share = dialogPrimary("分享日志");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        slp.topMargin = dp(16);
        box.addView(share, slp);
        share.setOnClickListener(v -> {
            dialog.dismiss();
            LogShare.shareFile(this, name);
        });

        TextView later = dialogGhost("以后再说");
        later.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        llp.topMargin = dp(6);
        box.addView(later, llp);
        later.setOnClickListener(v -> dialog.dismiss());

        showStyledDialog(dialog, box, 0.88f);
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

    private TextView dialogTonal(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(getColor(R.color.primary));
        v.setBackgroundResource(R.drawable.bg_button_tonal);
        v.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
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
        // 金额优先：主数字是「省了多少钱」，用量和时长放副标题
        Matcher m = Pattern.compile("本次：省 ¥([\\d.]+) · (\\d+) 秒 · ([\\d.]+) L")
                .matcher(text);
        if (m.find()) {
            statLastMain.setText(moneyStat(m.group(1)));
            statLastSub.setText(m.group(2) + " 秒 · " + m.group(3) + " L");
        }
        Matcher t = Pattern.compile("累计：省 ¥([\\d.]+) · (\\d+) 次 · ([\\d.]+) L")
                .matcher(text);
        if (t.find()) {
            statTotalMain.setText(moneyStat(t.group(1)));
            statTotalSub.setText(t.group(2) + " 次 · " + t.group(3) + " L");
        }
        renderHistory();
    }

    /** 金额做主数字：¥ 缩到 60% 并转灰，后面数字保持大号 */
    private CharSequence moneyStat(String yuan) {
        String text = "¥" + yuan;
        SpannableString sp = new SpannableString(text);
        sp.setSpan(new RelativeSizeSpan(0.6f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new ForegroundColorSpan(getColor(R.color.muted)), 0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    /** 数值 + 单位：数字保持大号，单位缩到 52% 并转灰，读数更清爽 */
    private CharSequence statValue(String num, String unit) {
        String text = num + " " + unit;
        SpannableString sp = new SpannableString(text);
        int start = num.length() + 1;
        if (start < text.length()) {
            sp.setSpan(new RelativeSizeSpan(0.52f), start, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new ForegroundColorSpan(getColor(R.color.muted)), start, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
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
        head.setLetterSpacing(0.08f);
        head.setTextColor(getColor(R.color.muted));
        head.setPadding(0, 0, 0, dp(2));
        statHistory.addView(head);
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (int i = arr.length() - 1; i >= 0; i--) {
            org.json.JSONObject r = arr.optJSONObject(i);
            if (r == null) continue;
            String when = f.format(new Date(r.optLong("ts", 0)));
            // 行间一条极细分割线，方便一条条读
            View div = new View(this);
            div.setBackgroundColor(getColor(R.color.line));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            dlp.topMargin = dp(1);
            statHistory.addView(div, dlp);
            // 左：时间 + 用量 + 时长；右：省下的钱（右对齐，方便扫一眼对比）
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView left = new TextView(this);
            left.setText(String.format(Locale.CHINA, "%s · %d 秒 · %s%.1f L",
                    when, r.optInt("seconds", 0),
                    r.optBoolean("estimated", false) ? "约 " : "",
                    r.optDouble("liters", 0)));
            left.setTextSize(11.5f);
            left.setTextColor(getColor(R.color.ink));
            left.setSingleLine(true);
            left.setEllipsize(TextUtils.TruncateAt.END);
            left.setFontFeatureSettings("tnum");
            row.addView(left, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView right = new TextView(this);
            right.setText(String.format(Locale.CHINA, "省 ¥%.3f", r.optDouble("saved", 0)));
            right.setTextSize(11.5f);
            right.setTextColor(getColor(R.color.success));
            right.setSingleLine(true);
            right.setFontFeatureSettings("tnum");
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

    private void appendLog(final String line) {
        DebugLog.line("ui", line);
        runOnUiThread(() -> {
            String t = timeFmt.format(new Date());
            logBuf.append(t).append("  ").append(line).append('\n');
            if (logBuf.length() > 30000) logBuf.delete(0, logBuf.length() - 20000);
            // 日志卡片收着的时候完全不碰界面，结算刷屏也不会打断用户正在看的内容
            if (logExpanded) scheduleLogFlush();
        });
    }

    /** 结算时一秒几十行，攒 250ms 刷一次，避免每条都重排整页 */
    private void scheduleLogFlush() {
        if (logFlushScheduled) return;
        logFlushScheduled = true;
        uiHandler.postDelayed(this::flushLog, 250);
    }

    private void flushLog() {
        logFlushScheduled = false;
        if (!logExpanded) return;
        final int outerY = scroll == null ? 0 : scroll.getScrollY();
        logView.setText(logBuf);
        // 只有用户本来就贴在底部时才自动跟到底，往上翻着看就别抢
        if (logStickBottom) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
        // 日志重排有时会把外层的整页带着滚一下：按原位置放回去
        if (scroll != null) {
            scroll.post(() -> {
                if (scroll.getScrollY() != outerY) scroll.scrollTo(0, outerY);
            });
        }
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
            boolean stop = label != null && label.contains("关");
            btnWater.setText(waterOp
                    ? (label + "…")
                    : (running ? "关水" : "开水"));
            styleWaterButton(stop);
        } else {
            btnSendCode.setText("发码");
            btnLogin.setText("登录");
            btnScan.setText("扫描水表");
            btnQuery.setText("查询状态");
            btnWater.setText(running ? "关水" : "开水");
            styleWaterButton(running);
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
        styleWaterButton(running);
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
        if (session == null) {
            // 云端回 errorCode=8：会话已经废了，直接把登录页顶上来，别等用户点开水才发现
            gateHint.setText("登录已失效，请重新获取验证码登录");
            gateCode.setText("");
            showLoginGate(true);
            toast("登录已失效，请重新登录");
        }
    }

    @Override
    public void onDevices(List<ValveManager.ScanItem> devices) {
        deviceList.removeAllViews();
        if (connected) return;          // 已经连上了就不再显示列表
        for (int i = 0; i < devices.size(); i++) {
            ValveManager.ScanItem item = devices.get(i);
            if (item.bound) {
                sawBound = true;
                hideScanHint();
            }
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
            hideScanHint();
            // 5 秒判失败后又连上了：把失败弹窗收掉，别让人以为还连不上
            if (failDialog != null && failDialog.isShowing()) {
                failDialog.dismiss();
                failDialog = null;
            }
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        DebugLog.op("onNewIntent：" + (intent == null ? "null" : intent.getAction()));
        if (intent != null && intent.getBooleanExtra("show_update", false)) {
            intent.removeExtra("show_update");
            new Handler(Looper.getMainLooper()).postDelayed(this::checkUpdateOnLaunch, 250);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        FullScreen.hideBars(this);
        // 从设置页回来时立刻换上新主题 / 新背景图，不用重启
        Appearance.apply(this);
        styleWaterButton(running);
        renderWaterStatus();
        DebugLog.op("界面可见（onResume）");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 从弹窗/通知栏/键盘回来时系统会把状态栏放出来，拿到焦点就再收一次
        if (hasFocus) FullScreen.hideBars(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        DebugLog.op("界面进入后台（onPause）");
    }

    @Override
    protected void onDestroy() {
        DebugLog.op("界面销毁（onDestroy）");
        manager.setListener(null);
        if (downloadDialog != null) {
            downloadDialog.dismiss();
            downloadDialog = null;
            downloadBar = null;
            downloadPct = null;
        }
        super.onDestroy();
    }

    private void setupInteractions() {
        View[] press = {btnWater, btnScan, btnQuery, btnLogin, btnSendCode,
                btnCopyLog, btnToggleLogin, btnToggleLog, btnCheckUpdate};
        for (View v : press) pressScale(v);
        // 日志框：用户自己往上翻的时候，别被新日志拽回底部
        logScroll.setOnScrollChangeListener((v, sx, sy, oldX, oldY) -> {
            View child = logScroll.getChildAt(0);
            if (child == null) return;
            logStickBottom = sy + logScroll.getHeight() >= child.getHeight() - dp(16);
        });
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
        if (v != null) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            String label;
            try {
                label = getResources().getResourceEntryName(v.getId());
            } catch (Exception e) {
                // 弹窗里的按钮是代码建的，没有资源名：退回按钮文字，日志里照样看得懂
                label = v instanceof TextView ? ((TextView) v).getText().toString()
                        : "view#" + Integer.toHexString(v.getId());
            }
            DebugLog.op("点击 " + label);
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
