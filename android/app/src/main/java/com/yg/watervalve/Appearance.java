package com.yg.watervalve;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 外观：主色主题 + 自定义颜色 + 自定义背景图。
 *
 * 颜色不写死在资源里，而是运行期按调色板生成 drawable / 设置文字色，
 * 这样换主题不用重装，也不用给每个主题复制一套 res。
 */
public final class Appearance {

    public static final class Palette {
        public final String id;
        public final String name;
        public final int light;   // 浅色模式主色
        public final int dark;    // 深色模式主色

        Palette(String id, String name, int light, int dark) {
            this.id = id;
            this.name = name;
            this.light = light;
            this.dark = dark;
        }
    }

    public static final List<Palette> PRESETS = new ArrayList<>();

    static {
        PRESETS.add(new Palette("blue", "经典蓝", 0xFF2557F6, 0xFF4C7DFF));
        PRESETS.add(new Palette("teal", "湖水青", 0xFF0E9AA7, 0xFF2CC5CE));
        PRESETS.add(new Palette("green", "森野绿", 0xFF16A34A, 0xFF34D399));
        PRESETS.add(new Palette("orange", "暖橘", 0xFFEA580C, 0xFFFB923C));
        PRESETS.add(new Palette("rose", "玫瑰", 0xFFE11D48, 0xFFFB7185));
        PRESETS.add(new Palette("violet", "紫罗兰", 0xFF7C3AED, 0xFFA78BFA));
        PRESETS.add(new Palette("graphite", "石墨", 0xFF475569, 0xFF94A3B8));
    }

    private Appearance() {
    }

    // ── 偏好 ────────────────────────────────────────────────
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    }

    public static String themeId(Context c) {
        return prefs(c).getString("theme", "blue");
    }

    /** 自定义颜色（0 表示没设） */
    public static int customColor(Context c) {
        return prefs(c).getInt("custom", 0);
    }

    public static String bgImage(Context c) {
        String p = prefs(c).getString("bg_image", "");
        if (p == null || p.isEmpty()) return "";
        return new File(p).exists() ? p : "";
    }

    public static void setTheme(Context c, String id) {
        prefs(c).edit().putString("theme", id).apply();
    }

    public static void setCustomColor(Context c, int color) {
        prefs(c).edit().putString("theme", "custom").putInt("custom", color).apply();
    }

    public static void setBgImage(Context c, String path) {
        prefs(c).edit().putString("bg_image", path == null ? "" : path).apply();
    }

    public static boolean isDark(Context c) {
        return (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 当前生效的主色 */
    public static int primary(Context c) {
        boolean dark = isDark(c);
        String id = themeId(c);
        if ("custom".equals(id)) {
            int custom = customColor(c);
            if (custom != 0) return dark ? lift(custom, 0.18f) : custom;
        }
        for (Palette p : PRESETS) {
            if (p.id.equals(id)) return dark ? p.dark : p.light;
        }
        return dark ? 0xFF4C7DFF : 0xFF2557F6;
    }

    // ── 颜色工具 ────────────────────────────────────────────
    public static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return Color.rgb(r, g, bl);
    }

    public static int lift(int color, float t) {
        return mix(color, 0xFFFFFFFF, t);
    }

    public static int withAlpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(Math.max(0f, Math.min(1f, a)) * 255) << 24);
    }

    // ── drawable ───────────────────────────────────────────
    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static Drawable pill(Context c, int top, int bottom) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        g.setCornerRadius(dp(c, 999));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null);
    }

    public static Drawable tonal(Context c, int primary) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(withAlpha(primary, isDark(c) ? 0.20f : 0.12f));
        g.setCornerRadius(dp(c, 999));
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(primary, 0.18f)), g, null);
    }

    public static Drawable cardBg(Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c.getColor(R.color.card));
        g.setCornerRadius(dp(c, 24));
        g.setStroke(dp(c, 1), c.getColor(R.color.outline));
        return g;
    }

    /** 页面底色：跟着主色轻微染色，但不抢内容 */
    public static Drawable pageBg(Context c) {
        int p = primary(c);
        int top, bottom;
        if (isDark(c)) {
            top = mix(0xFF0E1219, p, 0.10f);
            bottom = mix(0xFF07090E, p, 0.04f);
        } else {
            top = mix(0xFFF8FAFD, p, 0.06f);
            bottom = mix(0xFFEEF1F6, p, 0.04f);
        }
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
    }

    /** 背景图上的蒙层：保证正文还能看清 */
    public static int scrimColor(Context c) {
        return isDark(c) ? 0xB30B0F17 : 0xCCFFFFFF;
    }

    // ── 应用 ───────────────────────────────────────────────
    /**
     * 把当前主题刷到界面上：主色按钮、强调文字、页面底色/背景图。
     * 只处理已知 id，找不到就跳过，避免以后改布局时崩。
     */
    public static void apply(Activity a) {
        if (a == null) return;
        final int p = primary(a);
        final boolean dark = isDark(a);

        View root = a.findViewById(R.id.root);
        ImageView img = a.findViewById(R.id.bgImage);
        View scrim = a.findViewById(R.id.bgScrim);

        String bg = bgImage(a);
        if (root != null) {
            if (!bg.isEmpty()) {
                if (root.getBackground() != null) root.setBackground(null);
                root.setBackgroundColor(dark ? 0xFF07090E : 0xFFEEF1F6);
            } else {
                root.setBackground(pageBg(a));
            }
        }
        if (img != null && scrim != null) {
            if (!bg.isEmpty()) {
                try {
                    Bitmap bm = decodeSampled(bg, 1600);
                    if (bm != null) {
                        img.setImageBitmap(bm);
                        img.setVisibility(View.VISIBLE);
                        scrim.setBackgroundColor(scrimColor(a));
                        scrim.setVisibility(View.VISIBLE);
                    } else {
                        img.setVisibility(View.GONE);
                        scrim.setVisibility(View.GONE);
                    }
                } catch (Exception e) {
                    img.setVisibility(View.GONE);
                    scrim.setVisibility(View.GONE);
                }
            } else {
                img.setImageDrawable(null);
                img.setVisibility(View.GONE);
                scrim.setVisibility(View.GONE);
            }
        }

        // 主按钮（关水是红色，由 MainActivity 自己切换，这里只管开水的常态）
        Button water = a.findViewById(R.id.btnWater);
        if (water != null) {
            String t = water.getText() == null ? "" : water.getText().toString();
            if (!t.startsWith("关")) {
                water.setBackground(pill(a, lift(p, 0.14f), mix(p, 0xFF000000, 0.14f)));
            }
        }
        Button gateLogin = a.findViewById(R.id.gateLogin);
        if (gateLogin != null) {
            gateLogin.setBackground(pill(a, lift(p, 0.14f), mix(p, 0xFF000000, 0.14f)));
        }

        // 次要按钮（tonal）
        int[] tonals = {R.id.btnScan, R.id.btnQuery, R.id.btnSendCode, R.id.gateSend};
        for (int id : tonals) {
            View v = a.findViewById(id);
            if (v != null) v.setBackground(tonal(a, p));
        }

        // 强调文字
        int[] accents = {R.id.btnToggleLogin, R.id.btnToggleLog, R.id.btnCopyLog,
                R.id.btnNotice, R.id.scanHint, R.id.gateSend,
                R.id.balance, R.id.noticeTag, R.id.noticeMore};
        for (int id : accents) {
            View v = a.findViewById(id);
            if (v instanceof TextView) ((TextView) v).setTextColor(p);
        }

        // 顶部品牌标 + 主区域装饰水滴
        View mark = a.findViewById(R.id.brandMark);
        if (mark != null) mark.setBackground(pill(a, lift(p, 0.18f), mix(p, 0xFF000000, 0.10f)));
        ImageView deco = a.findViewById(R.id.heroDeco);
        if (deco != null) deco.setColorFilter(p);
    }

    /** 按最长边采样解码，避免大图直接把内存撑爆 */
    private static Bitmap decodeSampled(String path, int maxEdge) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, probe);
            int w = probe.outWidth, h = probe.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = 1;
            while (w / (sample * 2) >= maxEdge || h / (sample * 2) >= maxEdge) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ── 背景图存取 ─────────────────────────────────────────
    /** 把选中的图片复制进应用私有目录，返回本地路径（失败返回空串） */
    public static String importBgImage(Context c, InputStream in, String ext) {
        if (in == null) return "";
        File dir = new File(c.getFilesDir(), "bg");
        // 只保留一张：先清掉旧的
        File[] olds = dir.listFiles();
        if (olds != null) {
            for (File f : olds) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        if (!dir.exists() && !dir.mkdirs()) return "";
        String name = "bg." + (ext == null || ext.isEmpty() ? "jpg" : ext);
        File out = new File(dir, name);
        try (InputStream src = in; FileOutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = src.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } catch (Exception e) {
            return "";
        }
        return out.getAbsolutePath();
    }

    public static void clearBgImage(Context c) {
        setBgImage(c, "");
        File dir = new File(c.getFilesDir(), "bg");
        File[] olds = dir.listFiles();
        if (olds != null) {
            for (File f : olds) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
