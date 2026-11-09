package com.yg.watervalve;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * 全屏适配：窗口铺满整块屏幕（包括摄像头的挖孔区），状态栏和导航栏收起来，
 * 从屏幕上沿下滑可以临时唤出。内容只避让「真正的挖孔」和底部手势条，
 * 不会再被状态栏那一整条顶下去。
 */
public final class FullScreen {

    public interface Insets {
        /**
         * @param top    摄像头挖孔占掉的高度（没有挖孔就是 0）
         * @param bottom 底部手势条 / 导航栏高度
         * @param ime    软键盘高度
         */
        void onInsets(int top, int bottom, int ime);
    }

    private FullScreen() {}

    /** 在 setContentView 之后调用：设窗口参数、收系统栏、接管 insets 分发。 */
    public static void apply(final Activity a, final View root, final Insets cb) {
        Window w = a.getWindow();
        // 关键：允许窗口画进挖孔区，否则系统会把整块内容往下推，顶上留一条黑边
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

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0;
            int bottom = 0;
            int ime = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.displayCutout()).top;
                bottom = Math.max(insets.getInsets(WindowInsets.Type.systemGestures()).bottom,
                        insets.getInsets(WindowInsets.Type.navigationBars()).bottom);
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                if (Build.VERSION.SDK_INT >= 28) {
                    DisplayCutout dc = insets.getDisplayCutout();
                    if (dc != null) top = dc.getSafeInsetTop();
                }
                bottom = insets.getSystemWindowInsetBottom();
            }
            cb.onInsets(top, bottom, ime);
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
