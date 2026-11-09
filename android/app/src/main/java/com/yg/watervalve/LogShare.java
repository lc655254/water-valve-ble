package com.yg.watervalve;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/**
 * 一键把日志分享到微信 / QQ / 邮件：走系统分享面板，不经过「保存到文件再找」。
 * 文件通过 {@link LogProvider} 暴露成 content:// URI，避免 API 24+ 的 file:// 限制。
 */
public final class LogShare {

    private LogShare() {}

    /** 现打包一份最新日志再分享 */
    public static void share(final Activity a) {
        String name = DebugLog.writeShareFile("logs");
        if (name == null || name.isEmpty()) {
            Toast.makeText(a, "日志打包失败，先确认调试模式开着", Toast.LENGTH_SHORT).show();
            return;
        }
        shareFile(a, name);
    }

    /** 分享已有的文件（崩溃自动打包的那份） */
    public static void shareFile(final Activity a, String name) {
        if (name == null || name.isEmpty()) return;
        try {
            Uri uri = LogProvider.uriFor(name);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "蓝牙水库调试日志");
            send.putExtra(Intent.EXTRA_TEXT, "蓝牙水库调试日志（" + name + "）");
            // ClipData 才能真正把读权限授给微信/QQ 这类目标
            send.setClipData(ClipData.newRawUri("log", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(send, "分享日志到…");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(chooser);
            DebugLog.line("share", "分享日志：" + name);
        } catch (Exception e) {
            Toast.makeText(a, "没有可用的分享应用：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
