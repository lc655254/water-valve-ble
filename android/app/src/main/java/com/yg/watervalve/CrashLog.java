package com.yg.watervalve;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** 保存最后一次未捕获异常，便于定位真机闪退。 */
public final class CrashLog {
    private static final String PREF = "water_valve";
    private static final String KEY = "last_crash";

    private CrashLog() {}

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                String s = Log.getStackTraceString(e);
                app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .edit().putString(KEY, s).commit();
                File dir = app.getExternalFilesDir(null);
                if (dir != null) {
                    File f = new File(dir, "crash-last.txt");
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(s.getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Throwable ignored) {
            }
            if (prev != null) {
                prev.uncaughtException(t, e);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(1);
            }
        });
    }

    public static String last(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, "");
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply();
    }
}
