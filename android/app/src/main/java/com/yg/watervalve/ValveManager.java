package com.yg.watervalve;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
        void onStats(String text);
        void onConnection(boolean connected);
        void onLogin(boolean ok, String msg);
    }

    public static final class ScanItem {
        public final BluetoothDevice device;
        public final String name;
        public final int rssi;
        public final boolean bound;
        public final boolean cloudChecked;
        public final boolean official;

        public ScanItem(BluetoothDevice device, String name, int rssi) {
            this(device, name, rssi, false, false, false);
        }

        public ScanItem(BluetoothDevice device, String name, int rssi,
                        boolean bound, boolean cloudChecked) {
            this(device, name, rssi, bound, cloudChecked, false);
        }

        public ScanItem(BluetoothDevice device, String name, int rssi,
                        boolean bound, boolean cloudChecked, boolean official) {
            this.device = device;
            this.name = name;
            this.rssi = rssi;
            this.bound = bound;
            this.cloudChecked = cloudChecked;
            this.official = official;
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
    private final ExecutorService nameIo = Executors.newSingleThreadExecutor();
    private final BleClient ble;
    private final BluetoothAdapter adapter;
    private final List<ScanItem> devices = new ArrayList<>();
    private final Set<String> cloudLooked = new HashSet<>();
    private final Set<String> cloudChecked = new HashSet<>();
    private final Set<String> officialLooked = new HashSet<>();
    private static final String GENERIC_NAME = "KLCXKJ 水表";
    private Listener listener;
    private Cloud.Session session;
    private volatile boolean running;
    private volatile boolean busy;
    private volatile boolean closing;
    private volatile boolean recovering;
    private boolean safetyOpen;
    private String bleAddr = "";
    private BluetoothDevice lastDevice;
    private int reconnectAttempts;
    private int recoveryRetries;
    private int noDeviceRetries;
    private volatile boolean recoveryCloseRunning;
    private Runnable reconnectRunnable;
    private Runnable recoveryRetryRunnable;
    private static final int MAX_RECONNECT = 20;
    private static final int MAX_RECOVERY_RETRY = 3;
    private String lastRn = "";
    private String lastRnPre = "";
    private String lastCm = "";
    /** 设备历次 23 帧报过的 randomNumber（tacTimes），新→旧最多 8 个。 */
    private final List<String> rnHistory = new ArrayList<>();
    private String mac = "";
    private String deviceName = "";
    private int devId;
    private String clearPref = "";
    private long openTs;
    private Protocol.Flow lastFlow;
    private int lastRateOrder;
    /** 下单时云端返回的 useCount；关阀时用「设备计数 − 它」算本次真实用量 */
    private int lastOrderUseCount = -1;
    /** 最近一次连接失败的原因（GATT 状态 / 超时），给界面弹「连不上怎么办」用 */
    private volatile String lastConnectError = "";

    private ValveManager(Context ctx) {
        context = ctx;
        prefs = ctx.getSharedPreferences("water_valve", Context.MODE_PRIVATE);
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        session = Cloud.Session.fromJson(prefs.getString("session", ""));
        lastRn = prefs.getString("last_rn", "");
        lastRnPre = prefs.getString("last_rn_pre", "");
        lastCm = prefs.getString("last_cm", "");
        for (String s : prefs.getString("rn_hist", "").split(",")) {
            String r = s.trim().toUpperCase(Locale.ROOT);
            if (!r.isEmpty() && !rnHistory.contains(r)) rnHistory.add(r);
        }
        mac = prefs.getString("mac", "");
        bleAddr = prefs.getString("ble_addr", "");
        deviceName = prefs.getString("name", "");
        devId = prefs.getInt("dev_id", 0);
        clearPref = prefs.getString("clear_pref", "");
        lastOrderUseCount = prefs.getInt("order_use_count", -1);
        safetyOpen = prefs.getBoolean("safety_open", false);
        openTs = prefs.getLong("safety_open_ts", 0L);
        running = safetyOpen;
        lastDevice = resolveSavedDevice();
        ble = new BleClient(ctx, bleListener);
    }

    public void setListener(Listener l) {
        listener = l;
        notifyStats();
        notifySession();
        notifyConnection(ble.isReady());
    }
    public Cloud.Session getSession() { return session; }
    public String getPhone() { return prefs.getString("phone", ""); }
    public String getMac() { return mac; }
    public String getDeviceName() { return deviceName; }
    public boolean isRunning() { return running; }
    public boolean isBusy() { return busy; }
    public boolean isConnected() { return ble.isReady(); }
    public boolean hasOpenSession() { return safetyOpen; }
    public long getOpenTs() { return openTs; }
    public String getLastConnectError() { return lastConnectError == null ? "" : lastConnectError; }

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
            final Listener l = listener;
            try {
                Cloud.Session s = Cloud.loginByCode(phone, code);
                try {
                    String bal = Cloud.refreshBalance(s);
                    if (bal != null && !bal.isEmpty()) s.balance = bal;
                } catch (Exception ignored) {
                }
                session = s;
                prefs.edit().putString("session", s.toJson().toString())
                        .putString("phone", phone).apply();
                log("登录成功：" + (s.userName == null || s.userName.isEmpty()
                        ? s.telephone : s.userName)
                        + " · 项目 " + s.projectId
                        + (s.balance.isEmpty() ? "" : " · 余额 " + Cloud.fmtMoney(s.balance) + " 元"));
                notifySession();
                if (l != null) main.post(() -> l.onLogin(true, ""));
                lookupExistingDevices();
            } catch (Exception e) {
                log("登录失败：" + msg(e));
                if (l != null) main.post(() -> l.onLogin(false, msg(e)));
            } finally {
                setBusy(false, "");
            }
        });
    }

    public void scan() {
        if (busy) return;
        main.post(() -> {
            devices.clear();
            cloudLooked.clear();
            cloudChecked.clear();
            officialLooked.clear();
            notifyDevices();
        });
        io.execute(() -> ble.startScan());
    }

    public void connect(ScanItem item) {
        if (busy) return;
        String addr;
        try {
            addr = item.device.getAddress();
        } catch (SecurityException e) {
            addr = "";
        }
        if (safetyOpen) {
            String want = bleAddr;
            if ((want == null || want.isEmpty()) && lastDevice != null) {
                try {
                    want = lastDevice.getAddress();
                } catch (SecurityException ignored) {
                }
            }
            if (want != null && !want.isEmpty() && addr != null && !addr.isEmpty()
                    && !want.equalsIgnoreCase(addr)) {
                log("上次未关水的是 " + want + "，请先连接它完成关阀");
                return;
            }
        }
        setBusy(true, "连接水表");
        stopReconnect();
        recovering = safetyOpen;
        recoveryRetries = 0;
        lastDevice = item.device;
        bleAddr = addr;
        saveDevice();
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
                if (hasOpenSession() && !recovering) recoverIfOpen(true);
            } finally {
                setBusy(false, "");
            }
        });
    }

    public void close() {
        close("用户关水");
    }

    /**
     * 占用检测用：中断当前这次连接尝试。
     * 只关掉 GATT，不触发自动重连，也不当成业务失败。
     */
    public void cancelConnect() {
        io.execute(() -> {
            try {
                ble.close();
            } catch (Exception ignored) {
            }
            setBusy(false, "");
            notifyConnection(false);
        });
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
                if (running || safetyOpen) closeInternal(reason);
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
        rememberRn(q.tacTimes);
        String m = Protocol.fmtMacHex(q.mac);
        if (m != null && !m.isEmpty()) mac = m;
        saveDevice();
        main.post(() -> {
            if (listener != null) listener.onQuery(q);
        });
        log("设备状态：" + q.statusText() + " · deviceId=" + q.deviceIdInt()
                + " · 项目=" + q.projectInt() + " · MAC=" + mac
                + " · random=" + q.tacTimes);
        lookupCloudDevice();
    }

    private void openInternal() throws Exception {
        if (session == null) {
            log("先登录账号");
            return;
        }
        if (safetyOpen) {
            log("上次放水未确认关闭，先恢复");
            recoverIfOpen(true);
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
        rememberRn(q.tacTimes);
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

        // 发 21 之前先落安全日志并拉起前台服务：即使下一秒进程被杀，也能恢复关阀。
        openTs = System.currentTimeMillis();
        lastFlow = null;
        lastRateOrder = ro.rate;
        lastOrderUseCount = ro.useCount;
        prefs.edit().putInt("order_use_count", ro.useCount).apply();
        lastRnPre = q.tacTimes;
        lastRn = q.tacTimes;
        lastCm = q.commMode;
        safetyOpen = true;
        saveDevice();
        saveSafety();
        ValveService.scheduleAlarm(context, openTs);
        try {
            ValveService.start(context);
        } catch (Exception e) {
            log("前台服务启动失败：" + msg(e));
        }

        try {
            ble.sendAndWait("21", ro.downData, 8000);
            log("设备已接受费率");
        } catch (TimeoutException e) {
            log("21 无回帧（部分设备静默执行），继续确认状态…");
        } catch (Exception e) {
            log("21 发送异常：" + msg(e) + "，继续确认状态…");
        }
        for (int i = 1; i <= 5; i++) {
            Thread.sleep(2000);
            Protocol.Query q2 = ble.queryStatus();
            rememberRn(q2.tacTimes);
            // 01=放水中、03=结算中：部分固件下单成功后先报 03 再放水，
            // 所以两者都按「已受理」处理（与旧版/上位机实测一致）。
            if ("01".equals(q2.onCharge) || "03".equals(q2.onCharge)) {
                running = true;
                lastRn = q2.tacTimes == null || q2.tacTimes.isEmpty() ? q.tacTimes : q2.tacTimes;
                lastCm = q.commMode;
                rememberRn(lastRn);
                saveDevice();
                saveSafety();
                ValveService.scheduleAlarm(context, openTs);
                main.post(() -> {
                    if (listener != null) listener.onWater(true);
                });
                log("已开始放水（onCharge=" + q2.onCharge + "）");
                return;
            }
            log("轮询 " + i + "：onCharge=" + q2.onCharge + "，等待…");
        }
        log("未确认放水，执行安全关阀");
        boolean closed = settleInternal();
        if (closed) {
            clearSafety();
            running = false;
            try {
                ValveService.stop(context);
            } catch (Exception ignored) {
            }
        } else {
            running = true;
            log("安全关阀未完成，保留安全日志并等待恢复");
        }
        main.post(() -> {
            if (listener != null) listener.onWater(running);
        });
    }

    private void closeInternal(String reason) throws Exception {
        if (!ble.isReady()) {
            log("水表未连接，自动重连后再关阀");
            if (safetyOpen || running) {
                recovering = true;
                stopReconnect();
                scheduleReconnect();
            }
            return;
        }
        boolean wasRunning = running;
        long start = openTs;
        boolean ok = settleInternal();
        if (ok) {
            if (wasRunning && start > 0) recordSession(start);
            running = false;
            openTs = 0;
            lastFlow = null;
            clearSafety();
            main.post(() -> {
                if (listener != null) listener.onWater(false);
            });
            try {
                ValveService.stop(context);
            } catch (Exception ignored) {
            }
            log("已关水（" + reason + "）");
            refreshBalanceAndNotify();
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
                    lastFlow = flow;
                    log("85 采集成功：" + flow.brief());
                    log("85 原始：" + collectFull);
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

        // 结算前先读一次当前状态：设备此刻报的 tacTimes 才是这条流水绑定的
        // randomNumber。旧版只用上次开阀存下来的值 —— 设备只要又翻过一次号，
        // 上传就永远是 226「加密校验失败」（日志里 45080000 从没被试过）。
        Protocol.Query qNow = null;
        try {
            qNow = ble.queryStatus();
        } catch (Exception e) {
            log("结算前查询失败：" + msg(e));
        }
        List<String> rns = new ArrayList<>();
        if (qNow != null) {
            addRn(rns, qNow.tacTimes);
            rememberRn(qNow.tacTimes);
        }
        addRn(rns, lastRn);
        addRn(rns, lastRnPre);
        for (int i = rnHistory.size() - 1; i >= 0; i--) addRn(rns, rnHistory.get(i));
        if (qNow != null) addRn(rns, qNow.macTime);   // 最后兜底：个别固件用 macTime 当随机数
        String cm = (qNow != null && qNow.commMode != null && !qNow.commMode.isEmpty())
                ? qNow.commMode : lastCm;
        if (rns.isEmpty() || cm == null || cm.isEmpty()) {
            try {
                Protocol.Query q = ble.queryStatus();
                addRn(rns, q.tacTimes);
                if (cm == null || cm.isEmpty()) cm = q.commMode;
            } catch (Exception ignored) {
            }
        }
        log("结算参数：randomNumber 候选 " + rns + " · commMode=" + cm);

        String clData = null;
        try {
            String xf = Protocol.zeroUsage(collectFull);
            log("零用量流水已生成（" + xf.length() / 2 + "B）");
            for (String rn : rns) {
                try {
                    clData = Cloud.upload(session, xf, rn, cm);
                    if (clData != null && !clData.isEmpty()) {
                        lastRn = rn;
                        rememberRn(rn);
                        saveDevice();
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
                log("86 变体 " + c.name + "（" + c.payload.length() / 2 + "B）返回码 " + rc);
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

    private void recordSession(long start) {
        if (lastFlow == null || !lastFlow.ok) {
            log("账本：本次没取到流水，不记账");
            return;
        }
        long end = System.currentTimeMillis();
        int seconds = (int) Math.max(0, (end - start) / 1000);
        int count = lastFlow.useCountI;
        int rate = lastFlow.rate2I > 0 ? lastFlow.rate2I : lastFlow.rate1I;
        if (rate <= 0) rate = lastRateOrder;

        // 本次用量 = 设备计数 − 下单时云端 useCount（跟 PC 版同一套口径）。
        // 只拿设备累计计数会把历史用量重复算进来，看起来就像「每次固定 1 升」。
        int delta = count;
        String basis;
        boolean estimated = false;
        if (lastOrderUseCount >= 0 && count >= lastOrderUseCount) {
            delta = count - lastOrderUseCount;
            basis = "设备计数 − 云端 useCount";
        } else {
            basis = "设备计数（无云端对照）";
        }
        double liters = Math.max(0.0, delta * 0.5);
        if (liters <= 0 && seconds > 0) {
            liters = seconds / 60.0 * 12.0;      // 计数拿不到时按 12 L/min 估算
            estimated = true;
            basis = "按 " + seconds + " 秒 × 12 L/min 估算";
        }
        double byUse = liters * rate / 1000.0;
        double charge = Math.max(byUse, 0.025);
        try {
            JSONObject rec = new JSONObject();
            rec.put("ts", end);
            rec.put("start", start);
            rec.put("seconds", seconds);
            rec.put("count", count);
            rec.put("cloud_count", lastOrderUseCount);
            rec.put("delta", delta);
            rec.put("estimated", estimated);
            rec.put("basis", basis);
            rec.put("liters", round3(liters));
            rec.put("rate", rate);
            rec.put("official", round4(charge));
            rec.put("paid", 0.0);
            rec.put("saved", round4(charge));
            rec.put("source", "android");
            JSONArray arr = new JSONArray(prefs.getString("ledger", "[]"));
            arr.put(rec);
            while (arr.length() > 200) arr.remove(0);
            prefs.edit().putString("ledger", arr.toString())
                    .putInt("order_use_count", -1).apply();
            lastOrderUseCount = -1;      // 这一单已经结掉，别带到下一单
            log(String.format(Locale.CHINA,
                    "账本：计数 %d（云端 %d）→ %s%.3f L · %d 秒 · %d 元/吨 · 官方 ¥%.3f · 实扣 ¥0 · 省 ¥%.3f",
                    count, lastOrderUseCount, estimated ? "约 " : "",
                    liters, seconds, rate, charge, charge));
            notifyStats();
        } catch (Exception e) {
            log("账本记录失败：" + msg(e));
        }
    }

    public String getStatsText() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("ledger", "[]"));
            if (arr.length() == 0) return "账本：还没有记录";
            double liters = 0, saved = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                liters += r.optDouble("liters", 0);
                saved += r.optDouble("saved", 0);
            }
            JSONObject last = arr.optJSONObject(arr.length() - 1);
            int sec = last == null ? 0 : last.optInt("seconds", 0);
            double l = last == null ? 0 : last.optDouble("liters", 0);
            double s = last == null ? 0 : last.optDouble("saved", 0);
            double off = last == null ? 0 : last.optDouble("official", 0);
            return String.format(Locale.CHINA,
                    "本次：%d 秒 · %.3f L · 省 ¥%.3f（官方应扣 ¥%.3f · 实扣 ¥0）\n"
                            + "累计：%d 次 · %.3f L · 省 ¥%.3f",
                    sec, l, s, off, arr.length(), liters, saved);
        } catch (Exception e) {
            return "账本：读取失败";
        }
    }

    /** 最近 n 笔账（给界面画「最近记录」用，旧→新） */
    public JSONArray recentSessions(int n) {
        JSONArray out = new JSONArray();
        try {
            JSONArray arr = new JSONArray(prefs.getString("ledger", "[]"));
            for (int i = Math.max(0, arr.length() - n); i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r != null) out.put(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void notifyStats() {
        String t = getStatsText();
        main.post(() -> {
            if (listener != null) listener.onStats(t);
        });
    }

    private void notifyConnection(boolean connected) {
        main.post(() -> {
            if (listener != null) listener.onConnection(connected);
        });
    }

    private void refreshBalanceAndNotify() {
        if (session == null) return;
        try {
            String bal = Cloud.refreshBalance(session);
            if (bal != null && !bal.isEmpty()) {
                session.balance = bal;
                prefs.edit().putString("session", session.toJson().toString()).apply();
                notifySession();
                log("余额已刷新：" + Cloud.fmtMoney(bal) + " 元");
            }
        } catch (Exception e) {
            log("余额刷新失败：" + msg(e));
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    public void refreshOfficialNames() {
        if (!Xiaolian.hasToken(context)) return;
        main.post(() -> {
            final List<String> addrs = new ArrayList<>();
            for (ScanItem it : devices) {
                String a = addressOf(it.device);
                if (!a.isEmpty()) addrs.add(a);
            }
            if (bleAddr != null && !bleAddr.isEmpty()) addrs.add(bleAddr);
            if (addrs.isEmpty()) return;
            nameIo.execute(() -> {
                for (String a : addrs) lookupOfficialFor(a);
            });
        });
    }

    private void lookupOfficialFor(String addr) {
        if (addr == null || addr.isEmpty() || !Xiaolian.hasToken(context)) return;
        if (!knownCloud(addr).isEmpty()) return;
        String n = Xiaolian.deviceName(context, addr);
        if (n == null || n.trim().isEmpty()) return;
        final String name = n.trim();
        rememberOfficial(addr, name);
        main.post(() -> {
            updateOfficialName(addr, name);
            if (addr.equalsIgnoreCase(bleAddr) && knownCloud(addr).isEmpty()) {
                deviceName = name;
                saveDevice();
                if (listener != null) listener.onConnection(ble.isReady());
            }
            notifyDevices();
        });
    }

    private String knownOfficial(String addr) {
        if (addr == null || addr.isEmpty()) return "";
        return prefs.getString("xlname:" + addr.toLowerCase(Locale.ROOT), "");
    }

    private void rememberOfficial(String addr, String name) {
        if (addr == null || addr.isEmpty() || name == null || name.isEmpty()) return;
        prefs.edit().putString("xlname:" + addr.toLowerCase(Locale.ROOT), name).apply();
    }

    private void updateOfficialName(String addr, String name) {
        if (addr == null || addr.isEmpty() || name == null || name.isEmpty()) return;
        boolean changed = false;
        for (int i = 0; i < devices.size(); i++) {
            ScanItem it = devices.get(i);
            if (addr.equalsIgnoreCase(addressOf(it.device))) {
                if (!name.equals(it.name) || !it.official) {
                    devices.set(i, new ScanItem(it.device, name, it.rssi,
                            it.bound, it.cloudChecked, true));
                    changed = true;
                }
                break;
            }
        }
        if (changed) {
            sortDevices();
            notifyDevices();
        }
    }

    private void lookupExistingDevices() {
        main.post(() -> {
            final List<String> addrs = new ArrayList<>();
            for (ScanItem it : devices) {
                String a = addressOf(it.device);
                if (!a.isEmpty()) addrs.add(a);
            }
            if (addrs.isEmpty()) return;
            nameIo.execute(() -> {
                for (String a : addrs) lookupCloudFor(a);
            });
        });
    }

    private void lookupCloudDevice() {
        String addr = bleAddr;
        if (addr == null || addr.isEmpty()) {
            addr = addressOf(ble.getDevice());
        }
        if (addr == null || addr.isEmpty()) return;
        final String a = addr;
        nameIo.execute(() -> lookupCloudFor(a));
    }

    private void lookupCloudFor(String addr) {
        if (session == null || addr == null || addr.isEmpty()) return;
        try {
            Cloud.DeviceInfo found = null;
            boolean hadResponse = false;
            for (String cand : macCandidates(addr)) {
                Cloud.DeviceInfo info = Cloud.queryDevice(session, cand);
                if (info == null) continue;
                hadResponse = true;
                if (info.found) {
                    found = info;
                    break;
                }
            }
            if (found == null) {
                if (hadResponse && knownCloud(addr).isEmpty()) {
                    main.post(() -> markUnbound(addr));
                }
                return;
            }
            String name = found.deviceName == null ? "" : found.deviceName.trim();
            if (name.isEmpty()) {
                name = (found.buildingName + found.floorName + found.roomName).trim();
            }
            if (name.isEmpty()) {
                if (knownCloud(addr).isEmpty()) main.post(() -> markUnbound(addr));
                return;
            }
            final String finalName = name;
            rememberCloudName(addr, finalName);
            main.post(() -> {
                cloudChecked.add(addr);
                updateOfficialName(addr, finalName);
                if (isMine(addr)) {
                    deviceName = finalName;
                    saveDevice();
                    if (listener != null) listener.onConnection(ble.isReady());
                    log("设备名称：" + finalName);
                }
                notifyDevices();
            });
        } catch (Exception ignored) {
        }
    }

    private boolean isMine(String addr) {
        if (addr == null || addr.isEmpty()) return false;
        if (bleAddr != null && addr.equalsIgnoreCase(bleAddr)) return true;
        if (lastDevice != null) {
            String a = addressOf(lastDevice);
            if (addr.equalsIgnoreCase(a)) return true;
        }
        return false;
    }

    private List<String> macCandidates(String addr) {
        List<String> out = new ArrayList<>();
        if (addr == null) return out;
        String m = addr.toUpperCase(Locale.ROOT).replace("-", ":").trim();
        if (m.isEmpty()) return out;
        if (m.equalsIgnoreCase(bleAddr) && mac != null && !mac.isEmpty()
                && !mac.equalsIgnoreCase(m)) {
            out.add(mac.toUpperCase(Locale.ROOT));
        }
        out.add(m);
        String[] parts = m.split(":");
        if (parts.length == 6) {
            try {
                int b0 = Integer.parseInt(parts[0], 16);
                String[] cands = {String.format(Locale.ROOT, "%02X", b0 & 0x3F),
                        "00", "C0", "02"};
                for (String c : cands) {
                    String cand = c + ":" + parts[1] + ":" + parts[2] + ":"
                            + parts[3] + ":" + parts[4] + ":" + parts[5];
                    if (!out.contains(cand)) out.add(cand);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private void markUnbound(String addr) {
        if (addr == null || addr.isEmpty()) return;
        cloudChecked.add(addr);
        boolean changed = false;
        for (int i = 0; i < devices.size(); i++) {
            ScanItem it = devices.get(i);
            if (addr.equalsIgnoreCase(addressOf(it.device))) {
                if (it.bound || !it.cloudChecked) {
                    devices.set(i, new ScanItem(it.device, it.name, it.rssi,
                            it.bound, true, it.official));
                    changed = true;
                }
                break;
            }
        }
        if (changed) {
            sortDevices();
            notifyDevices();
        }
    }

    private String knownCloud(String addr) {
        if (addr == null || addr.isEmpty()) return "";
        return prefs.getString("cloudname:" + addr.toLowerCase(Locale.ROOT), "");
    }

    private String knownName(String addr) {
        String cloud = knownCloud(addr);
        if (!cloud.isEmpty()) return cloud;
        if (addr != null && addr.equalsIgnoreCase(bleAddr)
                && deviceName != null && !deviceName.isEmpty()) {
            return deviceName;
        }
        if (addr == null || addr.isEmpty()) return "";
        return prefs.getString("devname:" + addr.toLowerCase(Locale.ROOT), "");
    }

    private void rememberName(String addr, String name) {
        if (addr == null || addr.isEmpty() || name == null || name.isEmpty()) return;
        prefs.edit().putString("devname:" + addr.toLowerCase(Locale.ROOT), name).apply();
    }

    private void rememberCloudName(String addr, String name) {
        if (addr == null || addr.isEmpty() || name == null || name.isEmpty()) return;
        prefs.edit().putString("cloudname:" + addr.toLowerCase(Locale.ROOT), name).apply();
    }

    private void updateListName(String addr, String name) {
        if (addr == null || addr.isEmpty() || name == null || name.isEmpty()) return;
        boolean changed = false;
        for (int i = 0; i < devices.size(); i++) {
            ScanItem it = devices.get(i);
            if (addr.equalsIgnoreCase(addressOf(it.device))) {
                if (!name.equals(it.name)) {
                    devices.set(i, new ScanItem(it.device, name, it.rssi,
                            it.bound, it.cloudChecked, it.official));
                    changed = true;
                }
                break;
            }
        }
        if (changed) {
            sortDevices();
            notifyDevices();
        }
    }

    private void updateListName(String addr, String name, boolean bound, boolean checked) {
        if (addr == null || addr.isEmpty() || name == null || name.isEmpty()) return;
        boolean changed = false;
        for (int i = 0; i < devices.size(); i++) {
            ScanItem it = devices.get(i);
            if (addr.equalsIgnoreCase(addressOf(it.device))) {
                if (!name.equals(it.name) || it.bound != bound || it.cloudChecked != checked) {
                    devices.set(i, new ScanItem(it.device, name, it.rssi,
                            bound, checked, it.official));
                    changed = true;
                }
                break;
            }
        }
        if (changed) {
            sortDevices();
            notifyDevices();
        }
    }

    private void sortDevices() {
        devices.sort((a, b) -> {
            if (a.bound != b.bound) return a.bound ? -1 : 1;
            if (a.official != b.official) return a.official ? -1 : 1;
            return Integer.compare(b.rssi, a.rssi);
        });
    }

    private static String addressOf(BluetoothDevice d) {
        if (d == null) return "";
        try {
            String a = d.getAddress();
            return a == null ? "" : a;
        } catch (SecurityException e) {
            return "";
        }
    }

    public void clearOfficialCache() {
        android.content.SharedPreferences.Editor ed = prefs.edit();
        for (String k : new ArrayList<>(prefs.getAll().keySet())) {
            if (k.startsWith("xlname:")) ed.remove(k);
        }
        ed.apply();
        officialLooked.clear();
        main.post(() -> {
            for (int i = 0; i < devices.size(); i++) {
                ScanItem it = devices.get(i);
                if (it.official) {
                    devices.set(i, new ScanItem(it.device, GENERIC_NAME, it.rssi,
                            it.bound, it.cloudChecked, false));
                }
            }
            String n = ble.getSelectedName();
            if (n == null || n.isEmpty()) n = GENERIC_NAME;
            if (bleAddr != null && !bleAddr.isEmpty() && knownCloud(bleAddr).isEmpty()) {
                deviceName = n;
                saveDevice();
            }
            sortDevices();
            notifyDevices();
            if (listener != null) listener.onConnection(ble.isReady());
        });
    }

    private void saveSafety() {
        boolean ok = prefs.edit()
                .putBoolean("safety_open", safetyOpen)
                .putLong("safety_open_ts", openTs)
                .putString("ble_addr", bleAddr == null ? "" : bleAddr)
                .putString("last_rn", lastRn)
                .putString("last_rn_pre", lastRnPre)
                .putString("last_cm", lastCm)
                .commit();
        if (!ok) log("安全日志写入失败，关阀兜底可能受影响");
    }

    private void clearSafety() {
        safetyOpen = false;
        openTs = 0L;
        recovering = false;
        recoveryRetries = 0;
        noDeviceRetries = 0;
        if (recoveryRetryRunnable != null) main.removeCallbacks(recoveryRetryRunnable);
        recoveryRetryRunnable = null;
        stopReconnect();
        prefs.edit().putBoolean("safety_open", false)
                .remove("safety_open_ts").commit();
        ValveService.cancelAlarm(context);
    }

    /** 启动/开机/闹钟恢复：重连上次水表并补零用量关阀。 */
    public void recoverIfOpen(boolean force) {
        if (!safetyOpen || recovering) return;
        if (ble.isReady() && running && !force) {
            log("正在放水，跳过启动恢复");
            return;
        }
        stopReconnect();
        recovering = true;
        running = true;
        recoveryRetries = 0;
        main.post(() -> {
            if (listener != null) listener.onWater(true);
        });
        log("检测到未确认关水，开始自动恢复…");
        if (ble.isReady()) {
            attemptRecoveryClose();
            return;
        }
        BluetoothDevice d = resolveSavedDevice();
        if (d == null) {
            log("找不到上次水表地址，无法自动恢复；请手动连接后关水");
            recovering = false;
            if (noDeviceRetries < 8) {
                noDeviceRetries++;
                main.postDelayed(() -> recoverIfOpen(true), 15000);
            }
            return;
        }
        noDeviceRetries = 0;
        lastDevice = d;
        scheduleReconnect();
    }

    private void attemptRecoveryClose() {
        if (recoveryCloseRunning) return;
        if (recoveryRetryRunnable != null) {
            main.removeCallbacks(recoveryRetryRunnable);
            recoveryRetryRunnable = null;
        }
        recoveryCloseRunning = true;
        io.execute(() -> {
            try {
                closeInternal("断线/重启恢复");
            } catch (Exception e) {
                log("恢复关阀失败：" + msg(e));
            } finally {
                recoveryCloseRunning = false;
            }
            if (!hasOpenSession()) return;
            if (recoveryRetries >= MAX_RECOVERY_RETRY) {
                recovering = false;
                log("恢复关阀未完成，请手动关水或等待闹钟兜底");
                return;
            }
            recoveryRetries++;
            log("恢复重试 " + recoveryRetries + "/" + MAX_RECOVERY_RETRY + "…");
            recoveryRetryRunnable = this::attemptRecoveryClose;
            main.postDelayed(recoveryRetryRunnable, 5000);
        });
    }

    private BluetoothDevice resolveSavedDevice() {
        if (adapter == null) return null;
        String a = bleAddr;
        if ((a == null || a.isEmpty()) && mac != null
                && mac.matches("(?i)([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
            a = mac;
        }
        if (a == null || a.isEmpty()) return null;
        try {
            return adapter.getRemoteDevice(a);
        } catch (Exception e) {
            return null;
        }
    }

    private void scheduleReconnect() {
        if (reconnectRunnable != null) return;
        if (lastDevice == null) lastDevice = resolveSavedDevice();
        if (lastDevice == null) return;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (ble.isReady()) {
                    reconnectRunnable = null;
                    reconnectAttempts = 0;
                    return;
                }
                if (reconnectAttempts >= MAX_RECONNECT) {
                    reconnectRunnable = null;
                    recovering = false;
                    log("自动重连失败（" + MAX_RECONNECT + " 次），请靠近水表手动重连");
                    return;
                }
                reconnectAttempts++;
                log("自动重连 " + reconnectAttempts + "/" + MAX_RECONNECT + "…");
                final BluetoothDevice d = lastDevice;
                io.execute(() -> ble.connect(d));
                reconnectRunnable = this;
                main.postDelayed(this, 5000);
            }
        };
        reconnectRunnable = r;
        main.postDelayed(r, 1200);
    }

    private void stopReconnect() {
        if (reconnectRunnable != null) main.removeCallbacks(reconnectRunnable);
        reconnectRunnable = null;
        reconnectAttempts = 0;
    }

    /** 记住设备报过的 randomNumber：流水绑定的是哪一次会话不确定，多留几个值兜底。 */
    private void rememberRn(String rn) {
        if (rn == null) return;
        String r = rn.trim().toUpperCase(Locale.ROOT);
        if (r.isEmpty()) return;
        rnHistory.remove(r);
        rnHistory.add(r);
        while (rnHistory.size() > 8) rnHistory.remove(0);
        prefs.edit().putString("rn_hist", String.join(",", rnHistory)).apply();
    }

    private static void addRn(List<String> out, String rn) {
        if (rn == null) return;
        String r = rn.trim().toUpperCase(Locale.ROOT);
        if (!r.isEmpty() && !out.contains(r)) out.add(r);
    }

    private void saveDevice() {
        prefs.edit()
                .putString("mac", mac == null ? "" : mac)
                .putString("ble_addr", bleAddr == null ? "" : bleAddr)
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
                String addr = addressOf(device);
                String actual = name == null ? "" : name.trim();
                if (!actual.isEmpty()) rememberName(addr, actual);
                String cloud = knownCloud(addr);
                boolean bound = isMine(addr);
                boolean checked = cloudChecked.contains(addr);
                boolean isOfficial = !cloud.isEmpty();
                String nm;
                if (!cloud.isEmpty()) nm = cloud;
                else if (!actual.isEmpty()) nm = actual;
                else nm = knownName(addr);
                if (nm == null || nm.isEmpty()) nm = GENERIC_NAME;
                int idx = -1;
                for (int i = 0; i < devices.size(); i++) {
                    if (addr.equalsIgnoreCase(addressOf(devices.get(i).device))) {
                        idx = i;
                        break;
                    }
                }
                if (idx >= 0) {
                    ScanItem old = devices.get(idx);
                    if (GENERIC_NAME.equals(nm) && old.name != null
                            && !old.name.isEmpty() && !GENERIC_NAME.equals(old.name)) {
                        nm = old.name;
                    }
                    bound = bound || old.bound;
                    checked = checked || old.cloudChecked;
                    isOfficial = isOfficial || old.official;
                    boolean changed = old.name == null || !old.name.equals(nm)
                            || old.bound != bound || old.cloudChecked != checked
                            || old.official != isOfficial;
                    devices.set(idx, new ScanItem(device, nm, rssi, bound, checked, isOfficial));
                    sortDevices();
                    if (changed) notifyDevices();
                } else {
                    devices.add(new ScanItem(device, nm, rssi, bound, checked, isOfficial));
                    sortDevices();
                    notifyDevices();
                }
                if (session != null && !addr.isEmpty() && !cloudLooked.contains(addr)) {
                    cloudLooked.add(addr);
                    final String a = addr;
                    nameIo.execute(() -> lookupCloudFor(a));
                }
            });
        }

        @Override
        public void onConnected(boolean ok, String msg) {
            if (!ok) {
                lastConnectError = msg == null ? "" : msg;
                log(msg);
                setBusy(false, "");
                notifyConnection(false);
                if (safetyOpen || running) {
                    recovering = true;
                    scheduleReconnect();
                }
            }
        }

        @Override
        public void onDeviceName(String name) {
            if (name == null || name.trim().isEmpty()) return;
            String n = name.trim();
            String addr = addressOf(ble.getDevice());
            if (addr.isEmpty()) addr = bleAddr;
            if (addr.isEmpty()) return;
            bleAddr = addr;
            if (!knownCloud(addr).isEmpty()) return;
            if (n.equals(deviceName)) return;
            deviceName = n;
            rememberName(addr, n);
            saveDevice();
            final String a = addr;
            main.post(() -> {
                updateListName(a, n);
                if (listener != null) listener.onConnection(ble.isReady());
                notifyDevices();
            });
            log("设备名称：" + n);
        }

        @Override
        public void onReady() {
            stopReconnect();
            lastConnectError = "";
            main.post(() -> {
                log("水表已连接");
                setBusy(false, "");
                if (ble.getDevice() != null) {
                    String addr = addressOf(ble.getDevice());
                    if (!addr.isEmpty()) bleAddr = addr;
                    String cloud = knownCloud(bleAddr);
                    String n = ble.getSelectedName();
                    if (!cloud.isEmpty()) {
                        deviceName = cloud;
                    } else if (n != null && !n.isEmpty()) {
                        deviceName = n;
                    } else {
                        String cached = knownName(bleAddr);
                        if (!cached.isEmpty()) deviceName = cached;
                    }
                    if (mac == null || mac.isEmpty()) mac = bleAddr;
                    saveDevice();
                }
                notifyConnection(true);
            });
            if (recovering || hasOpenSession()) {
                recovering = true;
                attemptRecoveryClose();
                return;
            }
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
            lastConnectError = msg == null ? "" : msg;
            log(msg);
            notifyConnection(false);
            if (safetyOpen || running) {
                recovering = true;
                log("连接断开，自动重连并补关水…");
            }
            scheduleReconnect();
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
