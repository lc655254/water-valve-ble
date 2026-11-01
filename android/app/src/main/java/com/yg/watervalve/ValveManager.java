package com.yg.watervalve;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/** 业务控制器：账号、设备、开水/零用量关阀。所有 BLE/HTTP 操作在单线程执行。 */
public final class ValveManager {
    public interface Listener {
        void onLog(String line);
        void onBusy(boolean busy, String label);
        void onWater(boolean running);
        void onSession(Cloud.Session session);
        void onDevices(List<ScanItem> devices);
        void onQuery(Protocol.Query query);
    }

    public static final class ScanItem {
        public final BluetoothDevice device;
        public final String name;
        public final int rssi;
        public ScanItem(BluetoothDevice device, String name, int rssi) {
            this.device = device;
            this.name = name;
            this.rssi = rssi;
        }
    }

    private static ValveManager INSTANCE;

    public static synchronized ValveManager get(Context context) {
        if (INSTANCE == null) INSTANCE = new ValveManager(context.getApplicationContext());
        return INSTANCE;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final BleClient ble;
    private final List<ScanItem> devices = new ArrayList<>();
    private Listener listener;
    private Cloud.Session session;
    private volatile boolean running;
    private volatile boolean busy;
    private volatile boolean closing;
    private String lastRn = "";
    private String lastRnPre = "";
    private String lastCm = "";
    private String mac = "";
    private String deviceName = "";
    private int devId;
    private String clearPref = "";

    private ValveManager(Context ctx) {
        context = ctx;
        prefs = ctx.getSharedPreferences("water_valve", Context.MODE_PRIVATE);
        session = Cloud.Session.fromJson(prefs.getString("session", ""));
        lastRn = prefs.getString("last_rn", "");
        lastRnPre = prefs.getString("last_rn_pre", "");
        lastCm = prefs.getString("last_cm", "");
        mac = prefs.getString("mac", "");
        deviceName = prefs.getString("name", "");
        devId = prefs.getInt("dev_id", 0);
        clearPref = prefs.getString("clear_pref", "");
        ble = new BleClient(ctx, bleListener);
    }

    public void setListener(Listener l) { listener = l; }
    public Cloud.Session getSession() { return session; }
    public String getPhone() { return prefs.getString("phone", ""); }
    public String getMac() { return mac; }
    public String getDeviceName() { return deviceName; }
    public boolean isRunning() { return running; }
    public boolean isBusy() { return busy; }

    public void savePhone(String phone) {
        prefs.edit().putString("phone", phone).apply();
    }

    public void sendSms(String phone) {
        if (busy) return;
        setBusy(true, "发送验证码");
        io.execute(() -> {
            try {
                Cloud.sendSms(phone);
                log("验证码已发送");
            } catch (Exception e) {
                log("发码失败：" + msg(e));
            } finally {
                setBusy(false, "");
            }
        });
    }

    public void login(String phone, String code) {
        if (busy) return;
        setBusy(true, "登录");
        io.execute(() -> {
            try {
                Cloud.Session s = Cloud.loginByCode(phone, code);
                session = s;
                prefs.edit().putString("session", s.toJson().toString())
                        .putString("phone", phone).apply();
                log("登录成功：" + (s.userName == null || s.userName.isEmpty()
                        ? s.telephone : s.userName)
                        + " · 项目 " + s.projectId
                        + (s.balance.isEmpty() ? "" : " · 余额 " + s.balance));
                notifySession();
            } catch (Exception e) {
                log("登录失败：" + msg(e));
            } finally {
                setBusy(false, "");
            }
        });
    }

    public void scan() {
        if (busy) return;
        io.execute(() -> {
            devices.clear();
            notifyDevices();
            ble.startScan();
        });
    }

    public void connect(ScanItem item) {
        if (busy) return;
        setBusy(true, "连接水表");
        io.execute(() -> {
            try {
                deviceName = item.name;
                ble.connect(item.device);
            } catch (Exception e) {
                log("连接失败：" + msg(e));
                setBusy(false, "");
            }
        });
    }

    public void query() {
        if (busy) return;
        setBusy(true, "查询状态");
        io.execute(() -> {
            try {
                queryInternal();
            } catch (Exception e) {
                log("查询失败：" + msg(e));
            } finally {
                setBusy(false, "");
            }
        });
    }

    public void open() {
        if (busy) return;
        if (running) {
            log("已经在放水，先关水再开");
            return;
        }
        setBusy(true, "开水");
        io.execute(() -> {
            try {
                openInternal();
            } catch (Exception e) {
                log("开水中止：" + msg(e));
            } finally {
                setBusy(false, "");
            }
        });
    }

    public void close() {
        close("用户关水");
    }

    public void close(String reason) {
        if (closing) return;
        if (busy) {
            log("上一操作还没结束，稍后再关水");
            return;
        }
        closing = true;
        setBusy(true, reason);
        io.execute(() -> {
            try {
                closeInternal(reason);
            } catch (Exception e) {
                log("关阀异常：" + msg(e));
            } finally {
                closing = false;
                setBusy(false, "");
            }
        });
    }

    public void closeFromService(String reason) {
        if (closing) return;
        closing = true;
        io.execute(() -> {
            try {
                if (running) closeInternal(reason);
            } catch (Exception e) {
                log("服务关阀异常：" + msg(e));
            } finally {
                closing = false;
            }
        });
    }

    private void queryInternal() throws Exception {
        Protocol.Query q = ble.queryStatus();
        if (!"80".equals(q.returnCode)) {
            log("设备返回异常 returnCode=" + q.returnCode);
        }
        if (q.deviceIdInt() > 0) devId = q.deviceIdInt();
        String m = Protocol.fmtMacHex(q.mac);
        if (m != null && !m.isEmpty()) mac = m;
        saveDevice();
        main.post(() -> {
            if (listener != null) listener.onQuery(q);
        });
        log("设备状态：" + q.statusText() + " · deviceId=" + q.deviceIdInt()
                + " · 项目=" + q.projectInt() + " · MAC=" + mac
                + " · random=" + q.tacTimes);
    }

    private void openInternal() throws Exception {
        if (session == null) {
            log("先登录账号");
            return;
        }
        if (!ble.isReady()) {
            log("先连接水表");
            return;
        }
        Protocol.Query q = ble.queryStatus();
        if (!"80".equals(q.returnCode)) {
            log("设备未就绪 returnCode=" + q.returnCode);
            return;
        }
        if (!"00".equals(q.onCharge)) {
            log("设备当前：" + q.statusText() + "，先补结算");
            settleInternal();
            q = ble.queryStatus();
            if (!"00".equals(q.onCharge)) {
                log("设备仍未空闲，开阀中止");
                return;
            }
        }
        int curDev = q.deviceIdInt();
        if (curDev <= 0) {
            log("设备 deviceId 无效");
            return;
        }
        if (devId > 0 && devId != curDev) {
            log("这块表 deviceId=" + curDev + "，上次认定 " + devId + " —— 不是同一块表，已中止");
            return;
        }
        int devProject = q.projectInt();
        int accProject;
        try { accProject = Integer.parseInt(session.projectId); }
        catch (Exception e) { accProject = 0; }
        if (devProject > 0 && accProject > 0 && devProject != accProject) {
            log("账号项目 " + accProject + " ≠ 设备项目 " + devProject + "，云端会找不到设备");
            return;
        }
        devId = curDev;
        String m = Protocol.fmtMacHex(q.mac);
        if (m != null && !m.isEmpty()) mac = m;
        if (mac == null || mac.isEmpty()) {
            BluetoothDevice d = ble.getDevice();
            if (d != null) mac = d.getAddress();
        }
        if (mac == null || mac.isEmpty()) {
            log("没有设备 MAC，无法下单");
            return;
        }
        saveDevice();

        log("云端下单（预扣 5 元）…");
        Cloud.RateOrder ro = Cloud.rateOrder(session, devId, q.tacTimes, q.commMode, mac);
        log("拿到 downData，费率 rate=" + ro.rate + " 厘");
        try {
            ble.sendAndWait("21", ro.downData, 8000);
            log("设备已接受费率");
        } catch (TimeoutException e) {
            log("21 无回帧（部分设备静默执行），继续确认状态…");
        }
        for (int i = 1; i <= 5; i++) {
            Thread.sleep(2000);
            Protocol.Query q2 = ble.queryStatus();
            if ("01".equals(q2.onCharge) || "03".equals(q2.onCharge)) {
                running = true;
                lastRnPre = q.tacTimes;
                lastRn = q2.tacTimes == null || q2.tacTimes.isEmpty() ? q.tacTimes : q2.tacTimes;
                lastCm = q.commMode;
                saveDevice();
                main.post(() -> {
                    if (listener != null) listener.onWater(true);
                });
                try {
                    ValveService.start(context);
                } catch (Exception e) {
                    log("前台服务启动失败：" + msg(e));
                }
                log("已开始放水（onCharge=" + q2.onCharge + "）");
                return;
            }
            log("轮询 " + i + "：onCharge=" + q2.onCharge + "，等待…");
        }
        log("未确认放水，执行安全关阀");
        settleInternal();
        running = false;
        main.post(() -> {
            if (listener != null) listener.onWater(false);
        });
    }

    private void closeInternal(String reason) throws Exception {
        if (!ble.isReady()) {
            log("水表未连接，无法关阀；请靠近水表重连");
            return;
        }
        boolean ok = settleInternal();
        if (ok) {
            running = false;
            main.post(() -> {
                if (listener != null) listener.onWater(false);
            });
            try {
                ValveService.stop(context);
            } catch (Exception ignored) {
            }
            log("已关水（" + reason + "）");
        } else {
            log("关阀未确认，阀门可能仍开着，请重试或靠近水表");
        }
    }

    /** 22 结束 → 85 采集 → 零用量上传 → 86 清账 → 23 确认。 */
    private boolean settleInternal() {
        log("开始关阀结算：22 → 85 → 零用量上传 → 86 → 23");
        boolean ended = false;
        for (int i = 1; i <= 2 && !ended; i++) {
            try {
                ble.sendAndWait("22", "00", 10000);
                ended = true;
                log("22 结束回帧已收到");
            } catch (Exception e) {
                log("22 第 " + i + " 次无回包");
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            }
        }
        if (!ended) log("22 未确认（部分设备静默执行），继续采集");
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        String collectFull = null;
        for (int i = 1; i <= 3; i++) {
            try {
                Protocol.Frame f = ble.sendAndWait("85", "00", 10000);
                String rc = f.dataArea.length() >= 2 ? f.dataArea.substring(0, 2) : "";
                if ("80".equals(rc)) {
                    collectFull = Protocol.normHex(f.dataArea);
                    Protocol.Flow flow = Protocol.parseFlow(collectFull);
                    log("85 采集成功：" + flow.brief());
                    break;
                }
                log("85 返回码 " + rc);
                if ("83".equals(rc)) {
                    try { ble.sendAndWait("86", "00", 8000); } catch (Exception ignored) {}
                    Thread.sleep(1500);
                } else {
                    Thread.sleep(1500);
                }
            } catch (Exception e) {
                log("85 第 " + i + " 次失败：" + msg(e));
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
        if (collectFull == null) {
            try {
                Protocol.Query q = ble.queryStatus();
                if ("00".equals(q.onCharge)) {
                    log("设备已回空闲，无需结算");
                    return true;
                }
            } catch (Exception ignored) {}
            log("未取得 85 流水，结算中止");
            return false;
        }

        String collect = collectFull.startsWith("80")
                ? collectFull.substring(2) : collectFull;
        List<String> rns = new ArrayList<>();
        if (!lastRn.isEmpty()) rns.add(lastRn);
        if (!lastRnPre.isEmpty() && !rns.contains(lastRnPre)) rns.add(lastRnPre);
        String cm = lastCm;
        if (rns.isEmpty() || cm == null || cm.isEmpty()) {
            try {
                Protocol.Query q = ble.queryStatus();
                if (rns.isEmpty() && q.tacTimes != null && !q.tacTimes.isEmpty()) rns.add(q.tacTimes);
                if (cm == null || cm.isEmpty()) cm = q.commMode;
            } catch (Exception ignored) {}
        }

        String clData = null;
        try {
            String xf = Protocol.zeroUsage(collectFull);
            log("零用量流水已生成（" + xf.length() / 2 + "B）");
            for (String rn : rns) {
                try {
                    clData = Cloud.upload(session, xf, rn, cm);
                    if (clData != null && !clData.isEmpty()) {
                        log("账单已上传，randomNumber=" + rn);
                        break;
                    }
                } catch (Exception e) {
                    log("上传失败（" + rn + "）：" + msg(e));
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        } catch (Exception e) {
            log("零用量上传失败：" + msg(e));
        }
        String clearCode = clData == null ? null : Protocol.aesDecrypt(clData);
        if (clearCode != null) log("云端 clearCode=" + clearCode);

        String clearBy = null;
        List<Protocol.Labeled> candidates = Protocol.clearCandidates(
                collect, clearCode, collectFull, clearPref);
        for (Protocol.Labeled c : candidates) {
            try {
                Protocol.Frame f = ble.sendAndWait("86", c.payload, 10000);
                String rc = f.dataArea.length() >= 2 ? f.dataArea.substring(0, 2) : "";
                if ("80".equals(rc)) {
                    clearBy = c.name;
                    clearPref = c.name;
                    saveDevice();
                    log("86 清账成功（" + c.name + "）");
                    break;
                }
                log("86 变体 " + c.name + " 返回码 " + rc);
            } catch (Exception e) {
                log("86 变体 " + c.name + " 无回包");
            }
            try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
        }
        if (clearBy == null) log("所有 86 变体未确认，等待设备自行结算");

        for (int i = 1; i <= 5; i++) {
            try {
                Thread.sleep(2000);
                Protocol.Query q = ble.queryStatus();
                if ("00".equals(q.onCharge)) {
                    log("结算完成，设备已回空闲");
                    return true;
                }
                log("确认 onCharge=" + q.onCharge + "，继续等待…");
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void saveDevice() {
        prefs.edit()
                .putString("mac", mac == null ? "" : mac)
                .putString("name", deviceName == null ? "" : deviceName)
                .putInt("dev_id", devId)
                .putString("last_rn", lastRn)
                .putString("last_rn_pre", lastRnPre)
                .putString("last_cm", lastCm)
                .putString("clear_pref", clearPref)
                .apply();
    }

    private final BleClient.Listener bleListener = new BleClient.Listener() {
        @Override
        public void onLog(String msg) {
            log(msg);
        }

        @Override
        public void onScan(BluetoothDevice device, String name, int rssi) {
            main.post(() -> {
                devices.add(new ScanItem(device, name, rssi));
                notifyDevices();
            });
        }

        @Override
        public void onConnected(boolean ok, String msg) {
            log(msg);
            if (!ok) setBusy(false, "");
        }

        @Override
        public void onReady() {
            main.post(() -> {
                log("水表已连接");
                setBusy(false, "");
                if (ble.getDevice() != null) {
                    deviceName = ble.getSelectedName();
                    try {
                        mac = ble.getDevice().getAddress();
                    } catch (SecurityException ignored) {
                    }
                    saveDevice();
                }
            });
            io.execute(() -> {
                try {
                    queryInternal();
                } catch (Exception e) {
                    log("连接后查询失败：" + msg(e));
                }
            });
        }

        @Override
        public void onDisconnected(String msg) {
            log(msg);
            if (running) log("警告：连接断开，阀门可能仍在出水；请靠近水表重连后关水");
        }
    };

    private void log(String msg) {
        main.post(() -> {
            if (listener != null) listener.onLog(msg);
        });
    }

    private void setBusy(boolean b, String label) {
        busy = b;
        main.post(() -> {
            if (listener != null) listener.onBusy(b, label);
        });
    }

    private void notifyDevices() {
        main.post(() -> {
            if (listener != null) listener.onDevices(new ArrayList<>(devices));
        });
    }

    private void notifySession() {
        main.post(() -> {
            if (listener != null) listener.onSession(session);
        });
    }

    private static String msg(Exception e) {
        if (e instanceof Cloud.ApiException) {
            Cloud.ApiException a = (Cloud.ApiException) e;
            return a.getMessage() + "（" + a.code + "）";
        }
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : " " + m);
    }
}
