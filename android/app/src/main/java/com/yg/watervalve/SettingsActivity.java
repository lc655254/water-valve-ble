package com.yg.watervalve;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.Locale;

/**
 * 设置页：外观（主题色 / 自定义颜色 / 背景图）与关于。
 * 入口是主界面右上角的齿轮。
 */
public class SettingsActivity extends Activity {

    private static final int REQ_PICK_BG = 2001;
    private static final int REQ_SAVE_LOG = 2002;

    /** 导出时先把合并好的文本放这里，用户选完保存位置再写盘。 */
    private String pendingLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Appearance.apply(this);

        // 全屏：背景铺进挖孔区，内容只避让刘海（小圆孔不推整页）
        final View content = findViewById(R.id.settingsContent);
        if (content != null) {
            final int topPad = content.getPaddingTop();
            final int bottomPad = content.getPaddingBottom();
            FullScreen.apply(this, findViewById(R.id.root), (cutoutTop, contentTop, bottom, ime) ->
                    content.setPadding(content.getPaddingLeft(), topPad + contentTop,
                            content.getPaddingRight(), bottomPad + Math.max(bottom, ime)));
        } else {
            FullScreen.apply(this, findViewById(R.id.root), (cutoutTop, contentTop, bottom, ime) -> { });
        }

        TextView version = findViewById(R.id.settingsVersion);
        if (version != null) version.setText("v" + Updater.currentVersion(this));

        TextView device = findViewById(R.id.settingsDevice);
        if (device != null) {
            device.setText(android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE
                    + " (" + android.os.Build.VERSION.SDK_INT + ")");
        }

        View back = findViewById(R.id.btnBack);
        if (back != null) back.setOnClickListener(v -> finish());

        TextView check = findViewById(R.id.settingsCheckUpdate);
        if (check != null) check.setOnClickListener(v -> {
            check.setEnabled(false);
            check.setText("检查中…");
            Updater.check(this, (info, error) -> runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                check.setEnabled(true);
                check.setText("检查更新");
                if (error != null) {
                    toast("检查更新失败：" + error);
                    return;
                }
                if (info == null || !info.hasUpdate) {
                    toast("已是最新版本 v" + Updater.currentVersion(this));
                    return;
                }
                // 有新版本：回主界面，由主界面弹更新窗（下载/安装流程都在那边）
                toast("发现 v" + info.version + "，回主界面更新");
                Intent i = new Intent(this, MainActivity.class);
                i.putExtra("show_update", true);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                finish();
            }));
        });

        setupAppearance();
        setupDebug();
        setupBilling();

        // 实验开关：零用量时连费率一起清零
        TextView zeroRate = findViewById(R.id.zeroRateSwitch);
        if (zeroRate != null) {
            final String KEY = "zero_rate";
            boolean on = getSharedPreferences("water_valve", MODE_PRIVATE).getBoolean(KEY, false);
            zeroRate.setText(on ? "已开启" : "关闭");
            zeroRate.setTextColor(getColor(on ? R.color.success : R.color.muted));
            zeroRate.setOnClickListener(v -> {
                boolean now = !getSharedPreferences("water_valve", MODE_PRIVATE).getBoolean(KEY, false);
                getSharedPreferences("water_valve", MODE_PRIVATE).edit().putBoolean(KEY, now).apply();
                DebugLog.line("prefs", "零用量连费率清零 = " + now);
                zeroRate.setText(now ? "已开启" : "关闭");
                zeroRate.setTextColor(getColor(now ? R.color.success : R.color.muted));
                toast(now ? "已开启：零用量时费率也清零" : "已关闭：费率保持原样");
            });
        }
    }

    // ── 外观 ────────────────────────────────────────────────
    private void setupAppearance() {
        LinearLayout row = findViewById(R.id.themeSwatches);
        TextView name = findViewById(R.id.themeName);
        if (row != null) {
            row.removeAllViews();
            String cur = Appearance.themeId(this);
            for (final Appearance.Palette pal : Appearance.PRESETS) {
                View dot = swatch(pal.light, pal.id.equals(cur));
                dot.setOnClickListener(v -> {
                    Appearance.setTheme(this, pal.id);
                    DebugLog.line("prefs", "主题 = " + pal.id);
                    recreate();
                });
                row.addView(dot);
            }
            int custom = Appearance.customColor(this);
            View dot = swatch(custom != 0 ? custom : 0xFF94A3B8, "custom".equals(cur));
            dot.setOnClickListener(v -> showCustomColorDialog());
            row.addView(dot);

            if (name != null) {
                String label = "默认";
                for (Appearance.Palette pal : Appearance.PRESETS) {
                    if (pal.id.equals(cur)) label = pal.name;
                }
                if ("custom".equals(cur) && custom != 0) {
                    label = String.format(Locale.CHINA, "自定义 #%06X", custom & 0xFFFFFF);
                }
                name.setText(label);
            }
        }

        View pick = findViewById(R.id.btnPickBg);
        if (pick != null) pick.setOnClickListener(v -> pickBackground());
        View clear = findViewById(R.id.btnClearBg);
        if (clear != null) {
            boolean has = !Appearance.bgImage(this).isEmpty();
            clear.setVisibility(has ? View.VISIBLE : View.GONE);
            clear.setOnClickListener(v -> {
                DebugLog.op("清除自定义背景图");
                Appearance.clearBgImage(this);
                // 背景图是主界面启动时贴上去的，清掉之后已经开着的界面还留着旧图，
                // 所以要明确提示重启；顺手给一个「立即重启」按钮。
                confirm("背景图已清除",
                        "需要重启软件才会恢复默认背景：从后台划掉本应用再打开，或者点下面的「立即重启」。",
                        "立即重启", this::restartApp, this::recreate);
            });
        }
        View customBtn = findViewById(R.id.btnCustomColor);
        if (customBtn != null) customBtn.setOnClickListener(v -> showCustomColorDialog());
    }

    // ── 计费自检 ────────────────────────────────────────────
    private void setupBilling() {
        View check = findViewById(R.id.btnBillingCheck);
        if (check != null) check.setOnClickListener(v -> {
            DebugLog.op("计费自检：查云端账单");
            runBillingCheck();
        });
        View test = findViewById(R.id.btnBillingTest);
        if (test != null) test.setOnClickListener(v -> {
            DebugLog.op("计费自检：点实测一次");
            confirm("要实测一次吗？",
                    "会开阀放水约 8 秒再自动结算，用余额差直接验证云端扣不扣钱。"
                            + "请先连上水表、把龙头打开。",
                    "开始实测", this::doBillingTest);
        });
    }

    private void runBillingCheck() {
        final Cloud.Session s = Cloud.Session.fromJson(
                getSharedPreferences("water_valve", MODE_PRIVATE).getString("session", ""));
        if (s == null) {
            toast("先登录再自检");
            return;
        }
        toast("正在查云端账单…");
        new Thread(() -> {
            final BillingCheck.Result r = BillingCheck.run(this, s);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                DebugLog.line("check", "自检结论：" + r.verdict);
                final String msg = r.detail + "\n" + r.verdict;
                boolean on = getSharedPreferences("water_valve", MODE_PRIVATE)
                        .getBoolean("zero_rate", false);
                if (r.known && r.needZeroRate && !on) {
                    confirm("建议开启实验开关", msg, "开启实验", () -> {
                        getSharedPreferences("water_valve", MODE_PRIVATE)
                                .edit().putBoolean("zero_rate", true).apply();
                        DebugLog.op("计费自检：已按结论开启实验开关");
                        toast("已开启：下次关水结算时连费率一起清零");
                        recreate();
                    });
                } else {
                    confirm("计费自检", msg, "知道了", null);
                }
            });
        }).start();
    }

    private void doBillingTest() {
        toast("开始实测：放水 8 秒后自动结算");
        ValveManager.get(this).billingTest(8, (paid, text) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            confirm("计费自检结果", text, "知道了", null);
        }));
    }

    // ── 调试日志 ────────────────────────────────────────────
    private void setupDebug() {
        final TextView sw = findViewById(R.id.debugSwitch);
        final TextView state = findViewById(R.id.debugState);
        final TextView status = findViewById(R.id.debugStatus);
        paintDebug(sw, state, DebugLog.isOn());
        if (status != null) status.setText(DebugLog.stats());

        if (sw != null) sw.setOnClickListener(v -> {
            boolean now = !DebugLog.isOn();
            DebugLog.setOn(this, now);
            paintDebug(sw, state, now);
            if (status != null) status.setText(DebugLog.stats());
            toast(now ? "已开启：重现一次扣款问题，再回来导出日志"
                    : "已关闭：已经记下的日志仍然保留");
        });

        View export = findViewById(R.id.btnExportLog);
        if (export != null) export.setOnClickListener(v -> exportLog());

        View copy = findViewById(R.id.btnCopyLog);
        if (copy != null) copy.setOnClickListener(v -> {
            String text = DebugLog.recentText();
            if (text == null || text.trim().isEmpty()) {
                toast("还没有日志，先打开调试记录一次");
                return;
            }
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("这台手机不支持复制");
                return;
            }
            cm.setPrimaryClip(android.content.ClipData.newPlainText("debug", text));
            int n = text.split("\n").length;
            toast("已复制最近 " + n + " 行");
        });

        View clear = findViewById(R.id.btnClearLog);
        if (clear != null) clear.setOnClickListener(v -> confirm("清空调试日志？",
                "会把手机里保存的所有日志文件删掉，无法恢复。", "清空", () -> {
                    DebugLog.clear(this);
                    if (status != null) status.setText(DebugLog.stats());
                    toast("已清空");
                }));
    }

    private void paintDebug(TextView sw, TextView state, boolean on) {
        int color = getColor(on ? R.color.success : R.color.muted);
        if (sw != null) {
            sw.setText(on ? "已开启" : "关闭");
            sw.setTextColor(color);
        }
        if (state != null) {
            state.setText(on ? "记录中" : "未开启");
            state.setTextColor(color);
        }
    }

    private void exportLog() {
        toast("正在整理日志…");
        new Thread(() -> {
            final String text = DebugLog.bundle(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                pendingLog = text;
                String name = "watervalve-debug-"
                        + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA)
                        .format(new java.util.Date()) + ".txt";
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, REQ_SAVE_LOG);
                } catch (Exception e) {
                    pendingLog = null;
                    toast("这台手机没有文件保存界面，改用「复制最近」");
                }
            });
        }).start();
    }

    /** 简单确认框，跟设置页其它弹窗同一套外观。 */
    private void confirm(String titleText, String msg, String okText, final Runnable onOk) {
        confirm(titleText, msg, okText, onOk, null);
    }

    /**
     * @param onOk    点主按钮
     * @param onClose 关掉弹窗（点取消 / 点空白处）时执行，可为 null
     */
    private void confirm(String titleText, String msg, String okText,
                         final Runnable onOk, final Runnable onClose) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(12));
        box.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.ink));
        box.addView(title);

        TextView note = new TextView(this);
        note.setText(msg);
        note.setTextSize(12);
        note.setLineSpacing(dp(2), 1f);
        note.setTextColor(getColor(R.color.muted));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10);
        box.addView(note, nlp);

        TextView ok = new TextView(this);
        ok.setText(okText);
        ok.setTextSize(14);
        ok.setGravity(android.view.Gravity.CENTER);
        ok.setTextColor(getColor(R.color.primary));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        olp.topMargin = dp(14);
        box.addView(ok, olp);
        final boolean[] handled = {false};
        ok.setOnClickListener(v -> {
            handled[0] = true;
            dialog.dismiss();
            if (onOk != null) onOk.run();
        });

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(13);
        cancel.setTextColor(getColor(R.color.muted));
        cancel.setGravity(android.view.Gravity.CENTER);
        box.addView(cancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        cancel.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(box);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> {
            if (!handled[0] && onClose != null) onClose.run();
        });
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.35f);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88f),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private View swatch(int color, boolean selected) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (selected) g.setStroke(dp(2), getColor(R.color.ink));
        v.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        lp.rightMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private void showCustomColorDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(16));
        box.setBackgroundResource(R.drawable.bg_dialog);

        TextView label = new TextView(this);
        label.setText("CUSTOM COLOR");
        label.setTextSize(10);
        label.setLetterSpacing(0.18f);
        label.setTextColor(getColor(R.color.primary));
        box.addView(label);

        TextView title = new TextView(this);
        title.setText("自定义主色");
        title.setTextSize(20);
        title.setTextColor(getColor(R.color.ink));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(8);
        box.addView(title, tlp);

        TextView note = new TextView(this);
        note.setText("点一个颜色，或者直接填 #RRGGBB");
        note.setTextSize(11.5f);
        note.setTextColor(getColor(R.color.muted));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        box.addView(note, nlp);

        final EditText hex = new EditText(this);
        hex.setSingleLine(true);
        hex.setText(String.format(Locale.CHINA, "#%06X", Appearance.primary(this) & 0xFFFFFF));
        hex.setTextSize(15);
        hex.setTextColor(getColor(R.color.ink));
        hex.setBackgroundResource(R.drawable.bg_input);
        hex.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        hlp.topMargin = dp(12);
        box.addView(hex, hlp);

        int[] colors = {
                0xFF2557F6, 0xFF0EA5E9, 0xFF0E9AA7, 0xFF16A34A, 0xFF84CC16, 0xFFEAB308,
                0xFFEA580C, 0xFFDC2626, 0xFFE11D48, 0xFFDB2777, 0xFF7C3AED, 0xFF6366F1,
                0xFF0F172A, 0xFF475569, 0xFF78716C, 0xFF0891B2, 0xFF059669, 0xFFB45309
        };
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(6);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(14);
        box.addView(grid, glp);
        for (final int c : colors) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            dot.setBackground(g);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(34);
            lp.height = dp(34);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            grid.addView(dot, lp);
            dot.setOnClickListener(v -> hex.setText(String.format(Locale.CHINA, "#%06X", c & 0xFFFFFF)));
        }

        Button ok = new Button(this);
        ok.setText("应用");
        ok.setAllCaps(false);
        ok.setTextSize(15);
        ok.setTextColor(0xFFFFFFFF);
        ok.setBackgroundResource(R.drawable.bg_button_primary);
        ok.setStateListAnimator(null);
        ok.setMinHeight(0);
        ok.setMinWidth(0);
        ok.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        olp.topMargin = dp(16);
        box.addView(ok, olp);
        ok.setOnClickListener(v -> {
            int c = parseHex(hex.getText().toString());
            if (c == 0) {
                toast("颜色格式不对，用 #RRGGBB");
                return;
            }
            Appearance.setCustomColor(this, c);
            dialog.dismiss();
            recreate();
        });

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(13);
        cancel.setTextColor(getColor(R.color.primary));
        cancel.setGravity(android.view.Gravity.CENTER);
        box.addView(cancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        cancel.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(box);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.35f);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 重启软件：清掉任务栈再起主界面，外观/背景图会重新按当前设置生成。 */
    private void restartApp() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        } catch (Exception e) {
            toast("自动重启失败，请从后台划掉本应用再打开");
        }
    }

    private int parseHex(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return 0;
        try {
            return 0xFF000000 | Integer.parseInt(s, 16);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        FullScreen.hideBars(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) FullScreen.hideBars(this);
    }

    private void pickBackground() {
        Intent intent = null;
        // Android 13+ 用系统相册选择器（Photo Picker），比文件管理器好找图
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
            intent.setType("image/*");
            if (intent.resolveActivity(getPackageManager()) == null) intent = null;
        }
        // 老系统先试相册（MediaStore），不行再退回文件选择器
        if (intent == null) {
            Intent pick = new Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            pick.setType("image/*");
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (pick.resolveActivity(getPackageManager()) != null) intent = pick;
        }
        if (intent == null) {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        try {
            startActivityForResult(intent, REQ_PICK_BG);
        } catch (Exception e) {
            toast("打不开相册，换台设备或先用文件管理器选图");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SAVE_LOG) {
            if (res == RESULT_OK && data != null && data.getData() != null && pendingLog != null) {
                try (java.io.OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                    if (os == null) throw new IllegalStateException("打不开目标文件");
                    os.write(pendingLog.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    os.flush();
                    toast("日志已保存：" + pendingLog.length() + " 字符");
                } catch (Exception e) {
                    toast("保存失败：" + e.getMessage());
                }
            }
            pendingLog = null;
            return;
        }
        if (req != REQ_PICK_BG || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String ext = "jpg";
        String type = getContentResolver().getType(uri);
        if (type != null && type.contains("png")) ext = "png";
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            String path = Appearance.importBgImage(this, in, ext);
            if (path == null || path.isEmpty()) {
                toast("这张图读不了，换一张试试");
                return;
            }
            Appearance.setBgImage(this, path);
            recreate();
        } catch (Exception e) {
            toast("选图失败：" + e.getMessage());
        }
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
