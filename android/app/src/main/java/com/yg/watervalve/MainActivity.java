package com.yg.watervalve;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements ValveManager.Listener {
    private ValveManager manager;
    private TextView sessionStatus, balance, deviceStatus, waterStatus, logView, stats;
    private EditText phone, code;
    private Button btnSendCode, btnLogin, btnScan, btnQuery, btnWater;
    private LinearLayout deviceList;
    private ScrollView logScroll;
    private final StringBuilder logBuf = new StringBuilder();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
    private boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        manager = ValveManager.get(this);

        sessionStatus = findViewById(R.id.sessionStatus);
        balance = findViewById(R.id.balance);
        deviceStatus = findViewById(R.id.deviceStatus);
        waterStatus = findViewById(R.id.waterStatus);
        stats = findViewById(R.id.stats);
        logView = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        phone = findViewById(R.id.phone);
        code = findViewById(R.id.code);
        btnSendCode = findViewById(R.id.btnSendCode);
        btnLogin = findViewById(R.id.btnLogin);
        btnScan = findViewById(R.id.btnScan);
        btnQuery = findViewById(R.id.btnQuery);
        btnWater = findViewById(R.id.btnWater);
        deviceList = findViewById(R.id.deviceList);
        manager.setListener(this);

        phone.setText(manager.getPhone());
        running = manager.isRunning();
        renderSession(manager.getSession());
        renderDevice();
        stats.setText(manager.getStatsText());
        btnWater.setText(running ? "关水" : "开水");
        waterStatus.setText(running ? "正在放水" : "状态未知");
        if (running) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        btnSendCode.setOnClickListener(v -> {
            String p = phone.getText().toString().trim();
            if (p.length() != 11) {
                toast("请输入 11 位手机号");
                return;
            }
            manager.savePhone(p);
            manager.sendSms(p);
        });
        btnLogin.setOnClickListener(v -> {
            String p = phone.getText().toString().trim();
            String c = code.getText().toString().trim();
            if (p.length() != 11 || c.isEmpty()) {
                toast("请输入手机号和验证码");
                return;
            }
            manager.savePhone(p);
            manager.login(p, c);
        });
        btnScan.setOnClickListener(v -> manager.scan());
        btnQuery.setOnClickListener(v -> manager.query());
        btnWater.setOnClickListener(v -> {
            if (manager.isRunning()) manager.close("用户关水");
            else manager.open();
        });

        requestPerms();
        appendLog("安卓版启动，版本 0.1.2");
    }

    private void requestPerms() {
        List<String> ps = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            addIfMissing(ps, Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(ps, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            addIfMissing(ps, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(ps, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!ps.isEmpty()) {
            requestPermissions(ps.toArray(new String[0]), 1);
        }
    }

    private void addIfMissing(List<String> ps, String p) {
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ps.add(p);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        int denied = 0;
        for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) denied++;
        if (denied > 0) {
            appendLog("有 " + denied + " 项权限未授予，扫描/通知可能不可用");
            toast("请授予蓝牙和通知权限");
        } else {
            appendLog("权限已就绪");
        }
    }

    private void renderSession(Cloud.Session s) {
        if (s == null) {
            sessionStatus.setText("未登录（手机号 + 短信验证码登录）");
            balance.setText("");
            return;
        }
        String who = s.userName == null || s.userName.isEmpty() ? s.telephone : s.userName;
        sessionStatus.setText("已登录：" + who + " · 项目 " + s.projectId);
        String bal = Cloud.fmtMoney(s.balance);
        balance.setText(bal.isEmpty() ? "" : "余额：" + bal + " 元");
    }

    private void renderDevice() {
        String name = manager.getDeviceName();
        String mac = manager.getMac();
        if ((name == null || name.isEmpty()) && (mac == null || mac.isEmpty())) {
            deviceStatus.setText("未连接设备");
        } else {
            deviceStatus.setText((name == null || name.isEmpty() ? "水表" : name)
                    + " · " + (mac == null ? "" : mac));
        }
    }

    @Override
    public void onLog(String line) {
        appendLog(line);
    }

    private void appendLog(String line) {
        runOnUiThread(() -> {
            String t = timeFmt.format(new Date());
            logBuf.append(t).append("  ").append(line).append('\n');
            if (logBuf.length() > 30000) logBuf.delete(0, logBuf.length() - 20000);
            logView.setText(logBuf);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onBusy(boolean busy, String label) {
        btnSendCode.setEnabled(!busy);
        btnLogin.setEnabled(!busy);
        btnScan.setEnabled(!busy);
        btnQuery.setEnabled(!busy);
        btnWater.setEnabled(!busy);
        if (busy) {
            btnWater.setText(label == null || label.isEmpty() ? "处理中…" : label + "…");
        } else {
            btnWater.setText(running ? "关水" : "开水");
        }
    }

    @Override
    public void onWater(boolean running) {
        this.running = running;
        btnWater.setText(running ? "关水" : "开水");
        waterStatus.setText(running ? "正在放水" : "空闲");
        if (running) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onSession(Cloud.Session session) {
        renderSession(session);
    }

    @Override
    public void onDevices(List<ValveManager.ScanItem> devices) {
        deviceList.removeAllViews();
        for (ValveManager.ScanItem item : devices) {
            TextView tv = new TextView(this);
            String name = item.name == null || item.name.isEmpty() ? "未命名设备" : item.name;
            String addr;
            try {
                addr = item.device.getAddress();
            } catch (SecurityException e) {
                addr = "";
            }
            tv.setText(name + "\n" + addr + "  " + item.rssi + " dBm");
            tv.setTextSize(13);
            tv.setPadding(16, 12, 16, 12);
            tv.setGravity(Gravity.START);
            tv.setBackgroundColor(0x11000000);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 6;
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> manager.connect(item));
            deviceList.addView(tv);
        }
        if (devices.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("没有扫描到水表，靠近水表再试");
            tv.setTextSize(12);
            tv.setPadding(8, 8, 8, 8);
            deviceList.addView(tv);
        }
    }

    @Override
    public void onQuery(Protocol.Query q) {
        waterStatus.setText("设备：" + q.statusText() + " · onCharge=" + q.onCharge);
        renderDevice();
    }

    @Override
    public void onStats(String text) {
        stats.setText(text);
    }

    @Override
    public void onBackPressed() {
        if (manager.isRunning()) {
            manager.close("返回退出");
            toast("已通知关水，稍候可在通知栏确认");
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        manager.setListener(null);
        super.onDestroy();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
