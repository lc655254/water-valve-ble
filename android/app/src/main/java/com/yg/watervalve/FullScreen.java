package com.yg.watervalve;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * 全屏适配：窗口铺满整块屏幕（包括摄像头挖孔区），状态栏和导航栏收起来，
 * 从屏幕上沿下滑可临时唤出。
 *
 * 关键区别：
 * - 「刘海 / 水滴」这种横跨大半个顶部的挖孔，内容必须整条让开，否则字会被挡；
 * - 「小圆孔」只在中间占一小块，左右两边（标题、返回箭头）本来就空着，
 *   再把整页往下推一整条反而像没全屏 —— 所以这种只让居中内容避让。
 */
public final class FullScreen {

    /** 挖孔宽度超过屏宽这个比例，就当成刘海处理。 */
    private static final float WIDE_NOTCH_RATIO = 0.4f;

    public interface Insets {
        /**
         * @param cutoutTop  挖孔/刘海原始高度（居中内容避让用）
         * @param contentTop 整页内容建议避让高度（小圆孔为 0，刘海等于 cutoutTop）
         * @param bottom     底部手势条 / 导航栏
         * @param ime        软键盘高度
         */
        void onInsets(int cutoutTop, int contentTop, int bottom, int ime);
    }

    private FullScreen() {}

    public static void apply(final Activity a, final View root, final Insets cb) {
        Window w = a.getWindow();
        // 允许窗口画进挖孔区，否则系统会把整块内容往下推、顶上留一条
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarDividerColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        hideBars(a);

        final int screenWidth = a.getResources().getDisplayMetrics().widthPixels;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int cutoutTop = 0;
            boolean wide = false;
            int bottom = 0;
            int ime = 0;
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout dc = insets.getDisplayCutout();
                if (dc != null) {
                    cutoutTop = Math.max(dc.getSafeInsetTop(), 0);
                    for (Rect r : dc.getBoundingRects()) {
                        if (r.width() >= screenWidth * WIDE_NOTCH_RATIO) wide = true;
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                bottom = Math.max(insets.getInsets(WindowInsets.Type.systemGestures()).bottom,
                        insets.getInsets(WindowInsets.Type.navigationBars()).bottom);
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
            int contentTop = wide ? cutoutTop : 0;
            cb.onInsets(cutoutTop, contentTop, bottom, ime);
            return insets;
        });
        root.requestApplyInsets();
    }

    /** 重新收起系统栏：弹过对话框、收过键盘之后系统会把它们放回来。 */
    public static void hideBars(Activity a) {
        if (a == null) return;
        Window w = a.getWindow();
        if (w == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
