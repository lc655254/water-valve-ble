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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Appearance.apply(this);

        TextView version = findViewById(R.id.settingsVersion);
        if (version != null) version.setText("v" + Updater.currentVersion(this));

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
                Appearance.clearBgImage(this);
                recreate();
            });
        }
        View customBtn = findViewById(R.id.btnCustomColor);
        if (customBtn != null) customBtn.setOnClickListener(v -> showCustomColorDialog());
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
