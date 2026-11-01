package com.yg.watervalve;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机或 30 分钟闹钟触发：拉起重连关阀服务。 */
public class ValveRecoverReceiver extends BroadcastReceiver {
    public static final String ACTION_ALARM = "com.yg.watervalve.ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_ALARM.equals(a) || Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            try {
                ValveService.startRecover(context);
            } catch (Exception ignored) {
            }
        }
    }
}
