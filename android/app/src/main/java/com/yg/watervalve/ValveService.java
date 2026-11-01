package com.yg.watervalve;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** 放水期间的前台服务：防止进程被系统回收，20 分钟超时自动关水。 */
public final class ValveService extends Service {
    public static final String ACTION_START = "com.yg.watervalve.START";
    public static final String ACTION_CLOSE = "com.yg.watervalve.CLOSE";
    private static final String CHANNEL = "valve_running";
    private static final long MAX_OPEN_MS = 20L * 60L * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> {
        ValveManager.get(this).closeFromService("超过 20 分钟自动关水");
        stopSelf();
    };

    public static void start(Context context) {
        Intent i = new Intent(context, ValveService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ValveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "放水提醒", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("水阀放水期间保持程序运行");
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CLOSE.equals(action)) {
            ValveManager.get(this).closeFromService("通知栏关水");
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(1, buildNotification());
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, MAX_OPEN_MS);
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent close = new Intent(this, ValveService.class).setAction(ACTION_CLOSE);
        PendingIntent closePi = PendingIntent.getService(this, 11, close,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("蓝牙水库 · 放水中")
                .setContentText("点此返回应用；20 分钟未关会自动关水")
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_delete, "关水", closePi);
        return b.build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        ValveManager.get(this).closeFromService("应用被划掉");
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(timeout);
        if (ValveManager.get(this).isRunning()) {
            ValveManager.get(this).closeFromService("服务结束");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
