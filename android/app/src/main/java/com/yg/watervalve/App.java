package com.yg.watervalve;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        // 调试日志要在崩溃钩子之后装，这样闪退会先落盘再交给 CrashLog
        DebugLog.init(this);
        // 靶机模式：接口地址可以被设置页改到本地假云端
        Cloud.setBaseOverride(getSharedPreferences("water_valve", MODE_PRIVATE)
                .getString("api_base", ""));
    }
}
