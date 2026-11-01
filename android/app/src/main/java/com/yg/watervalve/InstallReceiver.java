package com.yg.watervalve;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

/** 接收 PackageInstaller 状态，并把系统确认界面拉起来。 */
public class InstallReceiver extends BroadcastReceiver {
    public static final String ACTION_STATUS = "com.yg.watervalve.INSTALL_STATUS";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm;
            if (Build.VERSION.SDK_INT >= 33) {
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "更新已安装", Toast.LENGTH_SHORT).show();
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, "安装未完成：" + (msg == null ? status : msg),
                    Toast.LENGTH_LONG).show();
        }
    }
}
