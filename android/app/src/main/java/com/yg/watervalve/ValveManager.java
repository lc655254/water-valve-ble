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
    /** 水表不报流量时的估算出水速度（升/分钟），宿舍水龙头量级 */
    private static final double FLOW_LPM = 8.0;
    /** 官方起步价（元/次）：实测「开了立马关」也固定扣 0.025，和 Windows 版同一口径 */
    private static final double MIN_CHARGE_YUAN = 0.025;
    /** 起步价换成厘：rate1 只有 25 时说明设备报的是最低收费，不是真实用量 */
    private static final int MIN_CHARGE_LI = 25;
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
    /** 下单前的余额（元）；结算后对比就知道云端实际扣了多少 */
    private double balanceBeforeOrder = -1;
    /** 最近一次连接失败的原因（GATT 状态 / 超时），给界面弹「连不上怎么办」用 */
    private volatile String lastConnectError = "";
    /** 正在续期会话，避免 errorCode=8 连环触发 */
    private volatile boolean renewing;

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
        // 云端回 errorCode=8 时清掉死会话，别拿它继续刷接口、也别挡住重新登录
        Cloud.watchSession(this::onSessionExpired);
    }

    /** 会话失效：先静默续期一次，续不上再清会话、回登录页 */
    private void onSessionExpired() {
        final Cloud.Session s = session;
        if (s == null || renewing) return;
        renewing = true;
        io.execute(() -> {
            try {
                Cloud.Session n = Cloud.renew(s);
                session = n;
                prefs.edit().putString("session", n.toJson().toString()).apply();
                DebugLog.line("session", "会话续期成功，loginCode 已更新");
                log("登录状态已自动续期");
                main.post(() -> {
                    if (listener != null) listener.onSession(n);
                });
            } catch (Exception e) {
                session = null;
                prefs.edit().remove("session").apply();
                DebugLog.line("session", "续期失败（" + msg(e) + "），清除本地会话");
                log("登录已失效，请重新登录");
                main.post(() -> {
                    if (listener != null) listener.onSession(null);
                });
            } finally {
                renewing = false;
            }
        });
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

    /**
     * 退到后台一段时间后把连接放掉：BLE 水表同时只容一台设备，
     * 手机挂着不放，电脑版就会一直连不上（现场反馈的头号原因）。
     */
    public void releaseBle(final String reason) {
        if (running || safetyOpen) {
            DebugLog.line("ble", "后台释放跳过：" + reason + "（正在放水/有未关会话）");
            return;
        }
        if (!ble.isReady()) return;
        io.execute(() -> {
            try {
                ble.close();
                DebugLog.line("ble", "后台释放连接：" + reason);
                log("已释放水表连接（" + reason + "），下次用之前重新扫描");
                setBusy(false, "");
                notifyConnection(false);
            } catch (Exception e) {
                DebugLog.line("ble", "释放连接失败：" + msg(e));
            }
        });
    }
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
        DebugLog.section("登录开始");
        DebugLog.op("登录：手机号 " + phone + "，验证码 " + (code == null ? "" : code.length()) + " 位");
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
                DebugLog.line("bal", "余额[登录后]：" + Cloud.fmtMoney(s.balance) + " 元");
                logSnapshot("登录成功");
                notifySession();
                if (l != null) main.post(() -> l.onLogin(true, ""));
                lookupExistingDevices();
            } catch (Exception e) {
                log("登录失败：" + msg(e));
                DebugLog.exc("login", e);
                logSnapshot("登录失败");
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
        DebugLog.op("查询设备状态");
        io.execute(() -> {
            try {
                queryInternal();
                logSnapshot("查询后");
                logBalanceNow("查询后");
            } catch (Exception e) {
                log("查询失败：" + msg(e));
                DebugLog.exc("query", e);
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
        DebugLog.section("开水开始");
        DebugLog.op("开水：点击开水按钮");
        io.execute(() -> {
            try {
                openInternal();
            } catch (Exception e) {
                log("开水中止：" + msg(e));
                DebugLog.exc("open", e);
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
        DebugLog.section("关水开始");
        DebugLog.op("关水：原因=" + reason);
        io.execute(() -> {
            try {
                closeInternal(reason);
                logSnapshot("关水后");
                logBalanceNow("关水后");
            } catch (Exception e) {
                log("关阀异常：" + msg(e));
                DebugLog.exc("close", e);
                logSnapshot("关水异常");
            } finally {
                closing = false;
                setBusy(false, "");
            }
        });
    }

    /** 计费自检的结果回调。 */
    public interface TestCallback {
        /** @param paid 云端实扣（元），-1 表示没测出来 */
        void done(double paid, String text);
    }

    /**
     * 计费实测：开阀放水 seconds 秒 → 正常关阀结算 → 对比余额差，
     * 直接回答「这台设备零用量结算会不会扣钱」，也就是要不要打开实验开关。
     */
    public void billingTest(final int seconds, final TestCallback cb) {
        if (busy) {
            if (cb != null) cb.done(-1, "正在忙，等这一笔结束再试");
            return;
        }
        final Cloud.Session s = session;
        if (s == null) {
            if (cb != null) cb.done(-1, "先登录再测");
            return;
        }
        if (!ble.isReady()) {
            if (cb != null) cb.done(-1, "先连接水表再测");
            return;
        }
        setBusy(true, "计费自检");
        DebugLog.section("计费自检（放水 " + seconds + " 秒）");
        DebugLog.op("计费自检：开关=" + (prefs.getBoolean("zero_rate", false) ? "开" : "关"));
        io.execute(() -> {
            double paid = -1;
            String result;
            try {
                // 上次没确认关水时，openInternal 会走「恢复」分支、根本不会开阀，
                // 测试就白做了。先把旧会话收干净再测。
                if (safetyOpen || running) {
                    log("计费自检：先关掉上次没确认的放水");
                    closeInternal("计费自检：清理上次会话");
                    Thread.sleep(1500);
                }
                String b0 = Cloud.refreshBalance(s);
                if (b0 != null && !b0.isEmpty()) s.balance = b0;
                double before = parseMoney(s.balance);
                log("计费自检：开阀，放水 " + seconds + " 秒");
                openInternal();
                if (!running) {
                    result = "没开成阀，自检中止（看上面的日志）";
                } else {
                    Thread.sleep(seconds * 1000L);
                    log("计费自检：关阀并结算");
                    closeInternal("计费自检");
                    String b1 = Cloud.refreshBalance(s);
                    double after = parseMoney(b1);
                    if (b1 != null && !b1.isEmpty()) s.balance = b1;
                    if (before >= 0 && after >= 0) {
                        paid = round3(before - after);
                        String money = String.format(Locale.CHINA, "%.3f", paid);
                        boolean on = prefs.getBoolean("zero_rate", false);
                        if (paid > 0.03) {
                            result = "实测结果：云端扣了 ¥" + money
                                    + (on ? "，实验开关开着也没挡住 → 这台设备还有别的计费字段"
                                    : "，说明零用量没生效 → 建议开启实验开关");
                        } else if (paid < -0.001) {
                            // 余额反而变多：下单预扣的 5 元被退回来了，说明这笔没扣钱
                            result = "实测结果：预扣的 ¥" + String.format(Locale.CHINA, "%.3f", -paid)
                                    + " 已退回、实扣 ¥0"
                                    + (on ? "，实验开关有效" : "，零用量本来就生效 → 不用开这个开关");
                        } else {
                            result = "实测结果：实扣 ¥" + money
                                    + (on ? "，实验开关有效" : "，零用量本来就生效 → 不用开这个开关");
                        }
                    } else {
                        result = "余额没取到，判断不了（看日志里的余额行）";
                    }
                }
            } catch (Exception e) {
                result = "自检失败：" + msg(e);
                DebugLog.exc("check", e);
            } finally {
                setBusy(false, "");
            }
            DebugLog.line("check", result + "（实扣 " + paid + "）");
            log(result);
            final double fp = paid;
            final String fr = result;
            main.post(() -> {
                if (cb != null) cb.done(fp, fr);
            });
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

    /** 把一帧 23 的解析结果推给界面；结算轮询里的查询也要推，否则状态会停在「结算中」 */
    private void publishQuery(Protocol.Query q) {
        if (q == null) return;
        main.post(() -> {
            if (listener != null) listener.onQuery(q);
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
        DebugLog.line("dev", "23帧解析 returnCode=" + q.returnCode + " onCharge=" + q.onCharge
                + "(" + q.statusText() + ")"
                + " deviceId=" + q.deviceIdInt() + " productId=" + q.productId
                + " accountId=" + q.accountId + " mac=" + q.mac
                + " commMode=" + q.commMode + " macTime=" + q.macTime
                + " tacTimes=" + q.tacTimes + " macType=" + q.macType
                + " chargeMethod=" + q.chargeMethod);
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

        // 下单前先刷一次余额：上次结算如果因为断网没刷到，缓存值就是旧的，
        // 结算时用「下单前 − 结算后」算实扣会算错（实测出现过 −1.55）
        try {
            String pre = Cloud.refreshBalance(session);
            if (pre != null && !pre.isEmpty()) {
                session.balance = pre;
                prefs.edit().putString("session", session.toJson().toString()).apply();
                log("下单前余额：" + Cloud.fmtMoney(pre) + " 元");
            }
        } catch (Exception e) {
            DebugLog.line("bal", "下单前刷新余额失败：" + msg(e));
        }
        log("云端下单（预扣 5 元）…");
        Cloud.RateOrder ro = Cloud.rateOrder(session, devId, q.tacTimes, q.commMode, mac);
        // 记下单前的余额：结算后拿它和新余额比，就知道云端到底扣没扣钱
        balanceBeforeOrder = parseMoney(session == null ? null : session.balance);
        log("拿到 downData，费率 rate=" + ro.rate + " 厘");
        DebugLog.line("order", "下单返回 rate=" + ro.rate + " useCount=" + ro.useCount
                + " downData=" + ro.downData + " raw=" + ro.raw);
        logBalanceNow("下单后");

        // 发 21 之前先落安全日志并拉起前台服务：即使下一秒进程被杀，也能恢复关阀。
        openTs = System.currentTimeMillis();
        lastFlow = null;
        lastRateOrder = ro.rate;
        lastOrderUseCount = ro.useCount;
        prefs.edit().putInt("order_use_count", ro.useCount).apply();
        logSnapshot("下单后");
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
                DebugLog.line("order", "放水确认 onCharge=" + q2.onCharge + " 开阀时刻=" + openTs);
                logSnapshot("放水开始");
                logBalanceNow("放水开始");
                return;
            }
            log("轮询 " + i + "：onCharge=" + q2.onCharge + "，等待…");
        }
        log("未确认放水，执行安全关阀");
        boolean closed = settleInternal();
        logBalanceNow("开阀未确认");
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
            // 账单晚一点才落库：后台等 2.5 秒再取真实用水量，取到就覆盖估算
            if (wasRunning && start > 0) applyCloudUsage(start);
        } else {
            log("关阀未确认，阀门可能仍开着，请重试或靠近水表");
            DebugLog.line("settle", "结算未确认，仍查一次余额看有没有扣钱");
            logBalanceNow("结算未确认");
        }
    }

    /** 22 结束 → 85 采集 → 零用量上传 → 86 清账 → 23 确认。 */
    private boolean settleInternal() {
        log("开始关阀结算：22 → 85 → 零用量上传 → 86 → 23");
        DebugLog.section("结算开始");
        logSnapshot("结算前");
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
        boolean zeroRate = prefs.getBoolean("zero_rate", false);
        boolean rejected = false;
        Cloud.lastConsumeMoney = -1;
        try {
            String xf = Protocol.zeroUsage(collectFull, zeroRate);
            log("零用量流水已生成（" + xf.length() / 2 + "B"
                    + (zeroRate ? " · 费率也清零" : "") + "）");
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
                    if (msg(e).contains("226")) rejected = true;
                    log("上传失败（" + rn + "）：" + msg(e));
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
            // 实验开关（费率也清零）被云端判 226 时，绝不能就这么放着：
            // 没有 clearCode 就关不了阀，用户会一直放水。立刻退回标准零用量再传一次。
            if (clData == null && zeroRate) {
                log("实验开关被云端拒绝（226），改用标准零用量上传（保留费率）");
                DebugLog.line("settle", "zero_rate 被拒，回退标准零用量");
                String std = Protocol.zeroUsage(collectFull, false);
                for (String rn : rns) {
                    try {
                        clData = Cloud.upload(session, std, rn, cm);
                        if (clData != null && !clData.isEmpty()) {
                            lastRn = rn;
                            rememberRn(rn);
                            saveDevice();
                            log("回退上传成功，randomNumber=" + rn);
                            break;
                        }
                    } catch (Exception e) {
                        log("回退上传失败（" + rn + "）：" + msg(e));
                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                    }
                }
                if (clData != null && !clData.isEmpty()) {
                    // 这台设备的云端不吃「费率清零」：记两次就直接把开关关掉，免得下次又卡住
                    int fails = prefs.getInt("zero_rate_fail", 0) + 1;
                    prefs.edit().putInt("zero_rate_fail", fails).apply();
                    if (fails >= 2) {
                        prefs.edit().putBoolean("zero_rate", false)
                                .putInt("zero_rate_fail", 0).apply();
                        log("这台设备的云端拒绝费率清零，已自动关闭实验开关");
                    }
                }
            } else if (rejected && !zeroRate) {
                DebugLog.line("settle", "标准零用量也被判 226");
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
                    // 把最后这帧 23 也推给界面：否则状态还停在上一次的「结算中」
                    publishQuery(q);
                    return true;
                }
                log("确认 onCharge=" + q.onCharge + "，继续等待…");
                publishQuery(q);
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * 记一笔用量。
     *
     * 关键事实（用 Windows 版真实账本核对过）：水表 85 流水里的 useCount 是「次数」不是流量 ——
     * 实测 5 秒和 606 秒两笔，设备计数都是 2、云端下单 useCount 都是 1，差值恒为 1。
     * 所以按差值 × 系数算，永远显示 1.000 L。
     *
     * 现在：差值 >= 2 才当真实计数增量（1 计数 = 1 升）；否则按出水时长估算并标「约」。
     */
    private void recordSession(long start) {
        if (lastFlow == null || !lastFlow.ok) {
            log("账本：本次没取到流水，不记账");
            return;
        }
        long end = System.currentTimeMillis();
        int seconds = (int) Math.max(0, (end - start) / 1000);
        int count = lastFlow.useCountI;
        int cloudCount = lastOrderUseCount;
        int rate = lastFlow.rate2I > 0 ? lastFlow.rate2I : lastFlow.rate1I;
        if (rate <= 0) rate = lastRateOrder;

        int delta = (cloudCount >= 0 && count >= cloudCount) ? count - cloudCount : -1;
        // 设备自己算的钱（rate1，厘）÷ 单价（rate2，元/吨）= 这一单的水量（升）。
        // 但 rate1 等于起步价（25 厘 = 0.025 元）时，那只是「最低收费」，不是真实用量，
        // 这种情况按流速估算，别把 1.00 L 当成实测。
        boolean aboveMin = lastFlow.rate1I > MIN_CHARGE_LI;
        double deviceLiters = (aboveMin && lastFlow.rate2I > 0)
                ? (double) lastFlow.rate1I / lastFlow.rate2I : 0;
        double liters;
        boolean estimated;
        String basis;
        if (deviceLiters > 0.01) {
            liters = deviceLiters;
            estimated = false;
            basis = "设备计量：rate1 " + lastFlow.rate1I + " ÷ 单价 " + lastFlow.rate2I
                    + " = " + String.format(Locale.CHINA, "%.2f", liters) + " L";
            if (seconds >= 45) calibrateFlow(liters, seconds);
        } else if (lastFlow.rate1I > 0 && lastFlow.rate1I <= MIN_CHARGE_LI && seconds > 0) {
            double lpm = flowLpm();
            liters = seconds / 60.0 * lpm;
            estimated = true;
            basis = "设备只报了起步价 " + lastFlow.rate1I + " 厘（1 L 以内），按 "
                    + String.format(Locale.CHINA, "%.2f", lpm) + " L/min 估算";
        } else if (delta >= 2) {
            liters = delta;
            estimated = false;
            basis = "设备计数增量（" + count + " − " + cloudCount + "）";
        } else if (seconds > 0) {
            double lpm = flowLpm();
            liters = seconds / 60.0 * lpm;
            estimated = true;
            basis = "按 " + seconds + " 秒 × " + String.format(Locale.CHINA, "%.2f", lpm)
                    + " L/min 估算（这台表只报次数，不报流量）";
        } else {
            liters = 0;
            estimated = true;
            basis = "时长太短，记为 0";
        }
        liters = Math.max(0.0, liters);
        DebugLog.section("结算明细");
        DebugLog.line("settle", "计时 开始=" + start + " 结束=" + end + " 时长=" + seconds + "s"
                + " 计数=" + count + " 云端计数=" + cloudCount + " 差值=" + delta
                + " 预扣=" + lastFlow.prepayI + " rate1=" + lastFlow.rate1I
                + " rate2=" + lastFlow.rate2I + " 采用费率=" + rate
                + " 下单费率=" + lastRateOrder
                + " 下单前余额=" + (balanceBeforeOrder < 0 ? "-"
                        : String.format(Locale.CHINA, "%.3f", balanceBeforeOrder))
                + " 依据=" + basis);
        // 把流水里的计费字段原样打出来：云端扣费对不上时，靠这几个数判断
        log("结算原始：useCount=" + count + " 预扣=" + lastFlow.prepayI
                + " rate1=" + lastFlow.rate1I + " rate2=" + lastFlow.rate2I);
        // 和 Windows 版完全一致：先算按用量的金额（保留 4 位），再和起步价 0.025 取大
        double byUse = round4(liters * rate / 1000.0);
        double charge = round4(Math.max(byUse, MIN_CHARGE_YUAN));
        DebugLog.line("settle", String.format(Locale.CHINA,
                "结算计算 用量=%s%.3fL × %d 元/吨 = ¥%.4f → 起步价 ¥%.3f → 应扣 ¥%.4f",
                estimated ? "约" : "", liters, rate, byUse, MIN_CHARGE_YUAN, charge));
        // 云端上传接口自己报了实扣金额就按它记账：有些云端不看零用量，按设备流水里的
        // rate1 照扣（实测 rate1=325 → 账单 consumeMoney=0.325），这时不能再说「省了」
        double cloudCharge = Cloud.lastConsumeMoney;
        double paidNow = cloudCharge > 0.0005 ? round4(cloudCharge) : 0;
        if (paidNow > 0) {
            charge = paidNow;
            DebugLog.line("settle", String.format(Locale.CHINA,
                    "云端实扣 ¥%.4f（零用量未生效，按设备流水计费）", paidNow));
        }
        try {
            JSONObject rec = new JSONObject();
            rec.put("ts", end);
            rec.put("start", start);
            rec.put("seconds", seconds);
            rec.put("count", count);
            rec.put("cloud_count", cloudCount);
            rec.put("delta", delta);
            rec.put("estimated", estimated);
            rec.put("basis", basis);
            rec.put("liters", round3(liters));
            rec.put("rate", rate);
            rec.put("official", round4(charge));
            rec.put("paid", round4(paidNow));
            rec.put("saved", round4(paidNow > 0 ? 0 : charge));
            if (paidNow > 0) rec.put("note", "云端按量扣费，零用量未生效");
            rec.put("source", deviceLiters > 0.01 ? "device" : (estimated ? "estimate" : "count"));
            JSONArray arr = new JSONArray(prefs.getString("ledger", "[]"));
            arr.put(rec);
            while (arr.length() > 200) arr.remove(0);
            prefs.edit().putString("ledger", arr.toString())
                    .putInt("order_use_count", -1).apply();
            log(String.format(Locale.CHINA,
                    "账本：计数 %d（云端 %s）差值 %d → %s%.2f L · %d 秒 · %d 元/吨 · 官方 ¥%.3f · 实扣 ¥%.3f · 省 ¥%.3f",
                    count, cloudCount < 0 ? "无" : String.valueOf(cloudCount), delta,
                    estimated ? "约 " : "", liters, seconds, rate, charge, paidNow,
                    paidNow > 0 ? 0 : charge));
            lastOrderUseCount = -1;      // 这一单已经结掉，别带到下一单
            notifyStats();
        } catch (Exception e) {
            log("账本记录失败：" + msg(e));
        }
    }

    /** 这台表学习到的流速（L/min），没学过就用默认 8.0 */
    private double flowLpm() {
        float v = prefs.getFloat("flow_lpm", (float) FLOW_LPM);
        if (v <= 0) return FLOW_LPM;
        return Math.max(1.0, Math.min(30.0, v));
    }

    /** 用设备自己算出来的水量校准流速：下次没有水量字段时按这个估，越用越准 */
    private void calibrateFlow(double liters, int seconds) {
        if (liters <= 0 || seconds < 5) return;
        double lpm = liters / (seconds / 60.0);
        if (lpm < 1 || lpm > 30) return;
        float old = prefs.getFloat("flow_lpm", (float) FLOW_LPM);
        float now = (float) (old <= 0 ? lpm : old * 0.6 + lpm * 0.4);
        prefs.edit().putFloat("flow_lpm", now).apply();
        DebugLog.line("usage", String.format(Locale.CHINA,
                "流速校准：本次 %.2f L / %d 秒 = %.2f L/min，学习值 %.2f L/min",
                liters, seconds, lpm, now));
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
            String src = last == null ? "" : last.optString("source", "");
            // 云端账单 > 设备计量 > 计数 > 按流速估算，标注来源别把估算说成实测
            String mark;
            if ("cloud".equals(src)) mark = "（云端）";
            else if ("device".equals(src)) mark = "（设备）";
            else if ("count".equals(src)) mark = "（计数）";
            else mark = "（约）";
            String ltxt = String.format(Locale.CHINA, "%.3f L%s", l, mark);
            // 金额优先：跟 Windows 版一致，先给「省了多少钱」，用量放后面
            return String.format(Locale.CHINA,
                    "本次：省 ¥%.3f · %d 秒 · %s（官方应扣 ¥%.3f · 实扣 ¥0）\n"
                            + "累计：省 ¥%.3f · %d 次 · %.3f L",
                    s, sec, ltxt, off, saved, arr.length(), liters);
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

    /** 给界面用：主动刷一次余额（充值回来、下拉刷新之类） */
    public void refreshBalanceOnce() {
        io.execute(this::refreshBalanceAndNotify);
    }

    /** 给界面用：主动续期一次会话 */
    public void renewSession() {
        onSessionExpired();
    }

    private void refreshBalanceAndNotify() {
        if (session == null) return;
        try {
            String bal = Cloud.refreshBalance(session);
            if (bal != null && !bal.isEmpty()) {
                double before = balanceBeforeOrder;
                double after = parseMoney(bal);
                session.balance = bal;
                prefs.edit().putString("session", session.toJson().toString()).apply();
                notifySession();
                log("余额已刷新：" + Cloud.fmtMoney(bal) + " 元");
                DebugLog.line("bal", String.format(Locale.CHINA,
                        "余额[结算后]：%s → %s 元（下单前=%s）",
                        before < 0 ? "-" : String.format(Locale.CHINA, "%.3f", before),
                        Cloud.fmtMoney(bal),
                        balanceBeforeOrder < 0 ? "-"
                                : String.format(Locale.CHINA, "%.3f", balanceBeforeOrder)));
                // 零用量结算本该不扣钱：真扣了就如实记账，别在账本里假报「省了多少」。
                // 但如果 recordSession 已经用过上传接口报的 consumeMoney，就不要再用
                // 余额差覆盖一遍（余额缓存偶尔会旧，会把实扣算成负数）。
                if (before >= 0 && after >= 0 && !lastRecordPaidPositive()) {
                    double paid = round3(before - after);
                    DebugLog.line("bal", String.format(Locale.CHINA,
                            "实扣 ¥%.3f（%s）", paid,
                            paid > 0.001 ? "零用量结算未生效" : "未扣款"));
                    if (paid > 0.001) {
                        patchLastSessionPaid(paid);
                        log(String.format(Locale.CHINA,
                                "⚠ 云端实际扣了 ¥%.3f（零用量结算没生效，已按实扣记账）", paid));
                    }
                    balanceBeforeOrder = -1;
                }
                logSnapshot("结算后");
            }
        } catch (Exception e) {
            log("余额刷新失败：" + msg(e));
            DebugLog.exc("balance", e);
        }
    }

    private static double parseMoney(String s) {
        if (s == null || s.trim().isEmpty()) return -1;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 结算后向云端账单要「这一单真实用了多少水」，取到就覆盖账本里的估算值。
     * 官方是按用水量计费的，账单里必然有水量，比按秒数估算准。
     */
    private void applyCloudUsage(final long sessionStart) {
        io.execute(() -> {
            try {
                Thread.sleep(2500);          // 账单落库要一点时间
                Cloud.Session s = session;
                if (s == null) return;
                String month = new java.text.SimpleDateFormat("yyyy-MM", Locale.CHINA)
                        .format(new java.util.Date());
                JSONArray bills = Cloud.bills(s, month, 1);
                JSONObject best = null;
                long bestStamp = 0;
                for (int i = 0; i < bills.length(); i++) {
                    JSONObject b = bills.optJSONObject(i);
                    if (b == null || b.optInt("billType", 0) != 2) continue;
                    JSONObject c = b.optJSONObject("consumeBillDTO");
                    if (c == null) continue;
                    long ts = billStamp(c);
                    if (ts >= bestStamp) {
                        bestStamp = ts;
                        best = c;
                    }
                }
                if (best == null) {
                    DebugLog.line("bill", "云端账单里没有消费单，用水量继续用估算");
                    return;
                }
                DebugLog.line("bill", "最新消费单：" + Usage.dump(best));
                double liters = Usage.litersFrom(best);
                if (liters <= 0) {
                    DebugLog.line("bill", "账单里没找到水量字段，用水量继续用估算");
                    return;
                }
                if (bestStamp > 0 && sessionStart > 0
                        && bestStamp < sessionStart - 10 * 60 * 1000L) {
                    DebugLog.line("bill", "最新消费单不是这一单（时间对不上），跳过");
                    return;
                }
                patchLastSessionLiters(liters, best.optDouble("consumeMoney", 0));
            } catch (Exception e) {
                DebugLog.line("bill", "取云端用水量失败：" + msg(e));
            }
        });
    }

    /** 把账本最后一笔的用水量换成云端数字 */
    private void patchLastSessionLiters(double liters, double money) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("ledger", "[]"));
            if (arr.length() == 0) return;
            JSONObject r = arr.optJSONObject(arr.length() - 1);
            if (r == null) return;
            double old = r.optDouble("liters", 0);
            r.put("liters", round3(liters));
            r.put("source", "cloud");
            r.put("basis", String.format(Locale.CHINA,
                    "云端账单用水量（本地估算 %.2f L）", old));
            if (money > 0) r.put("official", round4(money));
            prefs.edit().putString("ledger", arr.toString()).apply();
            notifyStats();
            log(String.format(Locale.CHINA, "云端用水量：%.2f L（本地估算 %.2f L）", liters, old));
            DebugLog.line("bill", String.format(Locale.CHINA,
                    "账本用水量改为云端值 %.2f L（原 %.2f L）", liters, old));
        } catch (Exception e) {
            DebugLog.line("bill", "回写用水量失败：" + msg(e));
        }
    }

    /** 账单里的时间字段：可能是时间戳，也可能是「2026-11-10 00:34:48」 */
    private static long billStamp(JSONObject c) {
        for (String k : new String[]{"consumeDate", "uploadDate", "dealDate", "createTime"}) {
            Object v = c.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            if (v instanceof Number) return ((Number) v).longValue();
            String s = String.valueOf(v).trim();
            if (s.matches("\\d{10,}")) {
                try {
                    return Long.parseLong(s);
                } catch (Exception ignored) {
                }
            }
            try {
                java.text.SimpleDateFormat f =
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
                f.setLenient(true);
                java.util.Date d = f.parse(s.replace('T', ' '));
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /** 账本最后一笔是不是已经记过实扣（>0）：记过就别再用余额差覆盖 */
    private boolean lastRecordPaidPositive() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("ledger", "[]"));
            if (arr.length() == 0) return false;
            JSONObject r = arr.optJSONObject(arr.length() - 1);
            return r != null && r.optDouble("paid", 0) > 0.0005;
        } catch (Exception e) {
            return false;
        }
    }

    /** 云端真扣了钱：把最后一笔账本改成「实扣」，省下的钱归零 */
    private void patchLastSessionPaid(double paid) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("ledger", "[]"));
            if (arr.length() == 0) return;
            JSONObject r = arr.optJSONObject(arr.length() - 1);
            if (r == null) return;
            r.put("official", round4(paid));
            r.put("paid", round4(paid));
            r.put("saved", 0.0);
            r.put("note", "云端实扣，零用量未生效");
            prefs.edit().putString("ledger", arr.toString()).apply();
            notifyStats();
        } catch (Exception e) {
            log("账本回写失败：" + msg(e));
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
                if (session == null) break;   // 会话刚失效：别再拿死会话挨个试 MAC 变体
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
        // 界面活着时由 MainActivity 落盘；界面没了（后台服务）这里兜底，日志不断档
        if (listener == null) DebugLog.line("vm", msg);
        main.post(() -> {
            if (listener != null) listener.onLog(msg);
        });
    }

    /**
     * 记一次「操作后的账户/设备快照」：账号字段、余额、连接与放水状态、上次流水与下单参数。
     * 出问题时对着时间点看这一行就知道当时是什么局面。
     */
    private void logSnapshot(String why) {
        try {
            Cloud.Session s = session;
            StringBuilder sb = new StringBuilder("快照[").append(why).append("]");
            sb.append(" 时间=").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.CHINA).format(new java.util.Date()));
            sb.append(" 账号=").append(s == null ? "未登录" : s.telephone);
            if (s != null) {
                sb.append(" userId=").append(s.userId)
                        .append(" accountId=").append(s.accountId)
                        .append(" 项目=").append(s.projectId)
                        .append(" 余额=").append(Cloud.fmtMoney(s.balance));
            }
            sb.append(" 网络=").append(DebugLog.netType());
            sb.append(" 连接=").append(ble.isReady() ? "已连接" : "未连接")
                    .append(" 放水中=").append(running)
                    .append(" 安全未关=").append(safetyOpen)
                    .append(" mac=").append(mac)
                    .append(" deviceId=").append(devId);
            if (lastFlow != null) {
                sb.append(" 流水[useCount=").append(lastFlow.useCountI)
                        .append(" 预扣=").append(lastFlow.prepayI)
                        .append(" rate1=").append(lastFlow.rate1I)
                        .append(" rate2=").append(lastFlow.rate2I).append("]");
            }
            sb.append(" 下单[rate=").append(lastRateOrder)
                    .append(" useCount=").append(lastOrderUseCount)
                    .append(" 下单前余额=").append(balanceBeforeOrder < 0 ? "-"
                            : String.format(Locale.CHINA, "%.3f", balanceBeforeOrder))
                    .append("]");
            DebugLog.line("snap", sb.toString());
        } catch (Throwable e) {
            DebugLog.line("snap", "快照失败：" + e);
        }
    }

    /** 实时查一次余额并落盘（在 io 线程排队执行，不阻塞界面）。 */
    private void logBalanceNow(final String why) {
        io.execute(() -> {
            Cloud.Session s = session;
            if (s == null) {
                DebugLog.line("bal", "余额[" + why + "]：未登录，跳过");
                return;
            }
            String before = s.balance;
            try {
                String bal = Cloud.refreshBalance(s);
                if (bal != null && !bal.isEmpty()) {
                    s.balance = bal;
                    prefs.edit().putString("session", s.toJson().toString()).apply();
                }
                DebugLog.line("bal", "余额[" + why + "]：" + Cloud.fmtMoney(before) + " → "
                        + (bal == null || bal.isEmpty() ? "查询失败" : Cloud.fmtMoney(bal)) + " 元");
                if (bal != null && !bal.isEmpty()) notifySession();
            } catch (Exception e) {
                DebugLog.line("bal", "余额[" + why + "]查询异常：" + msg(e));
            }
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
