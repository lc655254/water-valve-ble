package com.yg.watervalve;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        // 调试日志要在崩溃钩子之后装，这样闪退会先落盘再交给 CrashLog
        DebugLog.init(this);
    }
}
