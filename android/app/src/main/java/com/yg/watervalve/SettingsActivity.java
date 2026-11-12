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

    /** 去官方充值页的时间：回来后自动刷一次余额 */
    private long rechargeAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        // 外观统一在 onResume 里刷（onCreate 紧跟一次 onResume，别刷两遍）

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
        View billsBtn = findViewById(R.id.settingsBills);
        if (billsBtn != null) billsBtn.setOnClickListener(v -> {
            DebugLog.op("查看本月账单");
            showBills();
        });

        View recharge = findViewById(R.id.settingsRecharge);
        if (recharge != null) recharge.setOnClickListener(v -> {
            DebugLog.op("打开官方充值页");
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://app-cold-water.china-qzxy.cn/"));
                startActivity(i);
                rechargeAt = System.currentTimeMillis();
            } catch (Exception e) {
                toast("打不开浏览器：" + e.getMessage());
            }
        });

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
                // 现在返回主界面就会重新贴背景，一般不用重启；留一句兜底说明
                confirm("背景图已清除",
                        "返回主界面就会恢复默认底色，不用重启。如果哪一页还留着旧图，重新进一次设置或重启软件即可。",
                        "知道了", null, this::recreate);
            });
        }
        View customBtn = findViewById(R.id.btnCustomColor);
        if (customBtn != null) customBtn.setOnClickListener(v -> showCustomColorDialog());
    }

    // ── 靶机：接口地址 ──────────────────────────────────────
    private void paintApiBase(TextView v) {
        String b = getSharedPreferences("water_valve", MODE_PRIVATE).getString("api_base", "");
        if (b == null) b = "";
        v.setText(b.isEmpty() ? "默认" : b.replace("https://", "").replace("http://", ""));
    }

    /** 把接口地址指到本地假云端（靶机）；留空即回官方 */
    private void showApiBaseDialog(final TextView label) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(14));
        box.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText("接口地址（靶机）");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.ink));
        box.addView(title);

        TextView note = new TextView(this);
        note.setText("填 http://电脑IP:8000 指向本地假云端；留空回官方地址。改完重启生效。");
        note.setTextSize(12);
        note.setLineSpacing(dp(2), 1f);
        note.setTextColor(getColor(R.color.muted));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10);
        box.addView(note, nlp);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(getSharedPreferences("water_valve", MODE_PRIVATE).getString("api_base", ""));
        input.setHint("http://192.168.1.10:8000");
        input.setTextSize(14);
        input.setTextColor(getColor(R.color.ink));
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        ilp.topMargin = dp(12);
        box.addView(input, ilp);

        TextView ok = new TextView(this);
        ok.setText("保存");
        ok.setTextSize(14);
        ok.setGravity(android.view.Gravity.CENTER);
        ok.setTextColor(getColor(R.color.primary));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        olp.topMargin = dp(12);
        box.addView(ok, olp);
        ok.setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            getSharedPreferences("water_valve", MODE_PRIVATE)
                    .edit().putString("api_base", url).apply();
            Cloud.setBaseOverride(url);
            DebugLog.op("接口地址改为：" + (url.isEmpty() ? "官方" : url));
            dialog.dismiss();
            paintApiBase(label);
            toast(url.isEmpty() ? "已改回官方地址" : "已指向 " + url);
        });

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(13);
        cancel.setTextColor(getColor(R.color.muted));
        cancel.setGravity(android.view.Gravity.CENTER);
        box.addView(cancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        cancel.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(box);
        dialog.setCanceledOnTouchOutside(true);
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

    // ── 金额上限 ────────────────────────────────────────────
    private void paintBudget(TextView v) {
        float cap = getSharedPreferences("water_valve", MODE_PRIVATE)
                .getFloat("budget_yuan", 0f);
        v.setText(cap <= 0 ? "不限制"
                : String.format(Locale.CHINA, "¥%.2f", cap));
    }

    private void showBudgetDialog(final TextView label) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(14));
        box.setBackgroundResource(R.drawable.bg_dialog);

        TextView title = new TextView(this);
        title.setText("金额上限（元）");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(getColor(R.color.ink));
        box.addView(title);

        TextView note = new TextView(this);
        note.setText("放水时每 5 秒估算一次花费，到上限自动关水。填 0 表示不限制。");
        note.setTextSize(12);
        note.setTextColor(getColor(R.color.muted));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        box.addView(note, nlp);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        float cap = getSharedPreferences("water_valve", MODE_PRIVATE)
                .getFloat("budget_yuan", 0f);
        input.setText(cap <= 0 ? "" : String.format(Locale.CHINA, "%.2f", cap));
        input.setHint("0 = 不限制");
        input.setTextSize(15);
        input.setTextColor(getColor(R.color.ink));
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        ilp.topMargin = dp(12);
        box.addView(input, ilp);

        TextView ok = new TextView(this);
        ok.setText("保存");
        ok.setTextSize(14);
        ok.setGravity(android.view.Gravity.CENTER);
        ok.setTextColor(getColor(R.color.primary));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        olp.topMargin = dp(10);
        box.addView(ok, olp);
        ok.setOnClickListener(v -> {
            float value = 0f;
            try {
                String s = input.getText().toString().trim();
                if (!s.isEmpty()) value = Float.parseFloat(s);
            } catch (Exception ignored) {
            }
            if (value < 0) value = 0f;
            getSharedPreferences("water_valve", MODE_PRIVATE)
                    .edit().putFloat("budget_yuan", value).apply();
            DebugLog.op("金额上限改为：" + value);
            dialog.dismiss();
            paintBudget(label);
            toast(value <= 0 ? "已关闭金额上限" : String.format(Locale.CHINA, "到 ¥%.2f 自动关水", value));
        });

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(13);
        cancel.setTextColor(getColor(R.color.muted));
        cancel.setGravity(android.view.Gravity.CENTER);
        box.addView(cancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        cancel.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(box);
        dialog.setCanceledOnTouchOutside(true);
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

    // ── 计费自检 ────────────────────────────────────────────
    private void setupBilling() {
        final TextView budget = findViewById(R.id.settingsBudget);
        if (budget != null) {
            paintBudget(budget);
            budget.setOnClickListener(v -> showBudgetDialog(budget));
        }
        View check = findViewById(R.id.btnBillingCheck);
        if (check != null) check.setOnClickListener(v -> {
            DebugLog.op("计费自检：查云端账单");
            runBillingCheck();
        });
        View test = findViewById(R.id.btnBillingTest);
        if (test != null) test.setOnClickListener(v -> {
            DebugLog.op("计费自检：点实测一次");
            boolean running = ValveManager.get(this).isRunning();
            confirm("要实测一次吗？",
                    (running ? "现在正在放水，实测会先把它关掉结算，再重新开阀 8 秒。\n" : "")
                            + "会开阀放水约 8 秒再自动结算，用余额差直接验证云端扣不扣钱。"
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

    // ── 账单 ────────────────────────────────────────────────
    /** 本月消费 / 充值 / 退款逐笔列出来：扣钱和加钱分别走哪条记录，一眼能看出来 */
    private void showBills() {
        final Cloud.Session s = Cloud.Session.fromJson(
                getSharedPreferences("water_valve", MODE_PRIVATE).getString("session", ""));
        if (s == null) {
            toast("先登录再查账单");
            return;
        }
        toast("正在查账单…");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                String month = new java.text.SimpleDateFormat("yyyy-MM", Locale.CHINA)
                        .format(new java.util.Date());
                org.json.JSONArray bills = Cloud.bills(s, month, 1);
                DebugLog.line("bill", "账单查询：" + bills.length() + " 条（" + month + "）");
                int n = 0;
                for (int i = bills.length() - 1; i >= 0 && n < 12; i--) {
                    org.json.JSONObject b = bills.optJSONObject(i);
                    if (b == null) continue;
                    int type = b.optInt("billType", 0);
                    String line = null;
                    if (type == 2) {
                        org.json.JSONObject c = b.optJSONObject("consumeBillDTO");
                        if (c != null) {
                            line = String.format(Locale.CHINA, "消费  %s  ¥%.3f  %s",
                                    shortTime(c.optString("consumeDate", "")),
                                    c.optDouble("consumeMoney", 0),
                                    c.optString("description", ""));
                        }
                    } else if (type == 1) {
                        org.json.JSONObject r = b.optJSONObject("rechargeBillDTO");
                        if (r != null) {
                            line = String.format(Locale.CHINA, "充值  %s  +¥%.3f  %s",
                                    shortTime(r.optString("dealDate", "")),
                                    r.optDouble("dealMoney", 0),
                                    r.optString("typeName", ""));
                        }
                    } else if (type == 3) {
                        org.json.JSONObject f = b.optJSONObject("refundBillDTO");
                        if (f != null) {
                            line = String.format(Locale.CHINA, "退款  %s  +¥%.3f  %s",
                                    shortTime(f.optString("dealDate", "")),
                                    f.optDouble("dealMoney", 0),
                                    f.optString("typeName", ""));
                        }
                    }
                    if (line == null) continue;
                    sb.append(line).append('\n');
                    n++;
                }
                if (n == 0) sb.append("本月还没有账单记录\n");
                sb.append("\n余额：").append(Cloud.fmtMoney(s.balance)).append(" 元");
                sb.append("\n\n充值 / 退款由云端支付渠道记账，本页只读展示。");
            } catch (Exception e) {
                sb.append("查询失败：").append(e.getMessage());
                DebugLog.exc("bill", e);
            }
            final String text = sb.toString();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                confirm("本月账单", text, "知道了", null);
            });
        }).start();
    }

    private static String shortTime(String s) {
        if (s == null) return "";
        String t = s.trim().replace('T', ' ');
        return t.length() >= 16 ? t.substring(5, 16) : t;
    }

    // ── 调试日志 ────────────────────────────────────────────
    private void setupDebug() {
        final TextView sw = findViewById(R.id.debugSwitch);
        final TextView state = findViewById(R.id.debugState);
        final TextView status = findViewById(R.id.debugStatus);
        paintDebug(sw, state, DebugLog.isOn());
        if (status != null) status.setText(DebugLog.stats());

        final TextView apiBase = findViewById(R.id.settingsApiBase);
        if (apiBase != null) {
            paintApiBase(apiBase);
            apiBase.setOnClickListener(v -> showApiBaseDialog(apiBase));
        }

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

        View share = findViewById(R.id.btnShareLog);
        if (share != null) share.setOnClickListener(v -> {
            DebugLog.op("分享日志");
            LogShare.share(this);
        });

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
        // 换完主题/背景图立刻生效（取消对话框、返回本页都能看到）
        Appearance.apply(this);
        // 从官方充值页回来：自动刷一次余额
        if (rechargeAt > 0 && System.currentTimeMillis() - rechargeAt > 3000) {
            rechargeAt = 0;
            ValveManager.get(this).refreshBalanceOnce();
            toast("已刷新余额");
        }
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
