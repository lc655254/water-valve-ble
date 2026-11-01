package com.yg.watervalve;

import android.app.AlarmManager;
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

/** 放水前台服务：30 分钟超时、闹钟兜底、进程重启/开机恢复。 */
public final class ValveService extends Service {
    public static final String ACTION_START = "com.yg.watervalve.START";
    public static final String ACTION_CLOSE = "com.yg.watervalve.CLOSE";
    public static final String ACTION_RECOVER = "com.yg.watervalve.RECOVER";
    private static final String CHANNEL = "valve_running";
    private static final long MAX_OPEN_MS = 30L * 60L * 1000L;
    private static final int REQ_ALARM = 300;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> {
        ValveManager.get(this).closeFromService("超过 30 分钟自动关水");
        stopSelf();
    };

    public static void start(Context context) {
        startAction(context, ACTION_START);
    }

    public static void startRecover(Context context) {
        startAction(context, ACTION_RECOVER);
    }

    private static void startAction(Context context, String action) {
        Intent i = new Intent(context, ValveService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ValveService.class));
    }

    public static void scheduleAlarm(Context context, long openTs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        long at = openTs > 0 ? openTs + MAX_OPEN_MS : now + MAX_OPEN_MS;
        if (at < now + 5000) at = now + 5000;
        PendingIntent pi = alarmPi(context);
        try {
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception e) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Exception ignored) {
            }
        }
    }

    public static void cancelAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmPi(context));
    }

    private static PendingIntent alarmPi(Context context) {
        Intent i = new Intent(context, ValveRecoverReceiver.class)
                .setAction(ValveRecoverReceiver.ACTION_ALARM);
        return PendingIntent.getBroadcast(context, REQ_ALARM, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
        ValveManager m = ValveManager.get(this);
        String action = intent == null ? null : intent.getAction();

        if (ACTION_CLOSE.equals(action)) {
            m.closeFromService("通知栏关水");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_RECOVER.equals(action)) {
            startForeground(1, buildNotification("正在恢复未关水…"));
            if (m.hasOpenSession()) {
                m.recoverIfOpen(true);
                scheduleTimeout(m);
                scheduleAlarm(this, m.getOpenTs());
                return START_STICKY;
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent == null) {
            if (m.hasOpenSession()) {
                startForeground(1, buildNotification("服务重启，正在恢复关水…"));
                m.recoverIfOpen(true);
                scheduleTimeout(m);
                scheduleAlarm(this, m.getOpenTs());
                return START_STICKY;
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(1, buildNotification("正在放水；30 分钟未关会自动关水"));
        if (m.hasOpenSession()) {
            scheduleTimeout(m);
            scheduleAlarm(this, m.getOpenTs());
            return START_STICKY;
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    private void scheduleTimeout(ValveManager m) {
        handler.removeCallbacks(timeout);
        long now = System.currentTimeMillis();
        long at = m.getOpenTs() > 0 ? m.getOpenTs() + MAX_OPEN_MS : now + MAX_OPEN_MS;
        handler.postDelayed(timeout, Math.max(1000, at - now));
    }

    private Notification buildNotification(String text) {
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
                .setContentTitle("蓝牙水库")
                .setContentText(text)
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
        ValveManager m = ValveManager.get(this);
        if (m.isRunning() || m.hasOpenSession()) {
            m.closeFromService("服务结束");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
