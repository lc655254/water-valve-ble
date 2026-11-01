package com.yg.watervalve;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;

/** Android BLE：扫描、连接 ff00/ff02/ff01、20 字节分片、通知组帧。 */
public final class BleClient {
    public interface Listener {
        void onLog(String msg);
        void onScan(BluetoothDevice device, String name, int rssi);
        void onConnected(boolean ok, String msg);
        void onReady();
        void onDeviceName(String name);
        void onDisconnected(String msg);
    }

    private static final long SCAN_MS = 15000;
    private static final long CONNECT_MS = 20000;
    private static final int CHUNK = 20;
    private static final long CHUNK_DELAY_MS = 150;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice device;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private final StringBuilder rx = new StringBuilder();
    private final LinkedBlockingQueue<Protocol.Frame> frames = new LinkedBlockingQueue<>();
    private final Map<String, String> lastNames = new HashMap<>();
    private final Map<String, Long> lastEmit = new HashMap<>();
    private BluetoothGattCharacteristic nameChar;
    private volatile boolean ready;
    private volatile boolean scanning;
    private String selectedName = "";
    private Runnable scanStop;
    private Runnable connectTimeout;

    public BleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm == null ? null : bm.getAdapter();
    }

    public boolean isReady() { return ready; }
    public BluetoothDevice getDevice() { return device; }
    public String getSelectedName() { return selectedName; }
    public boolean isScanning() { return scanning; }

    public void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            listener.onLog("蓝牙未开启或设备不支持 BLE");
            return;
        }
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                listener.onLog("无法获取 BLE 扫描器");
                return;
            }
            lastNames.clear();
            lastEmit.clear();
            scanning = true;            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, scanCallback);
            listener.onLog("开始扫描水表（15 秒）…");
            if (scanStop != null) main.removeCallbacks(scanStop);
            scanStop = this::stopScan;
            main.postDelayed(scanStop, SCAN_MS);
        } catch (SecurityException e) {
            scanning = false;
            listener.onLog("缺少蓝牙扫描权限");
        } catch (Exception e) {
            scanning = false;
            listener.onLog("扫描失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    public void stopScan() {
        try {
            if (scanner != null && scanning) scanner.stopScan(scanCallback);
        } catch (Exception ignored) {
        }
        scanning = false;
        if (scanStop != null) main.removeCallbacks(scanStop);
        scanStop = null;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            BluetoothDevice d = result.getDevice();
            String name = safeName(d);
            ScanRecord rec = result.getScanRecord();
            if ((name == null || name.isEmpty()) && rec != null) name = rec.getDeviceName();
            if (name == null) name = "";
            boolean match = name.toUpperCase(Locale.ROOT).contains("KLCXKJ")
                    || name.toUpperCase(Locale.ROOT).contains("WATER")
                    || name.toUpperCase(Locale.ROOT).contains("YH")
                    || name.toUpperCase(Locale.ROOT).contains("QZXY")
                    || hasService(rec, "fee7") || hasService(rec, "ff00");
            if (!match) return;
            String addr = d.getAddress();
            String prev = lastNames.get(addr);
            boolean nameChanged = !name.isEmpty() && !name.equals(prev);
            long now = SystemClock.elapsedRealtime();
            Long last = lastEmit.get(addr);
            if (!nameChanged && last != null && now - last < 800) return;
            lastEmit.put(addr, now);
            if (!name.isEmpty()) lastNames.put(addr, name);
            selectedName = name;
            listener.onScan(d, name, result.getRssi());
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            listener.onLog("扫描失败，errorCode=" + errorCode);
        }
    };

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "" : n;
        } catch (SecurityException e) {
            return "";
        }
    }

    private boolean hasService(ScanRecord rec, String shortUuid) {
        if (rec == null) return false;
        List<ParcelUuid> uuids = rec.getServiceUuids();
        if (uuids == null) return false;
        for (ParcelUuid u : uuids) {
            String s = u.toString().toLowerCase(Locale.ROOT);
            if (s.contains(shortUuid)) return true;
        }
        return false;
    }

    public void connect(BluetoothDevice d) {
        stopScan();
        close();
        device = d;
        selectedName = safeName(d);
        listener.onLog("正在连接 " + selectedName + " (" + d.getAddress() + ")…");
        try {
            gatt = d.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            listener.onConnected(false, "缺少蓝牙连接权限");
            return;
        }
        if (gatt == null) {
            listener.onConnected(false, "connectGatt 返回空");
            return;
        }
        if (connectTimeout != null) main.removeCallbacks(connectTimeout);
        connectTimeout = () -> {
            if (!ready) {
                listener.onLog("连接超时");
                close();
                listener.onConnected(false, "连接超时");
            }
        };
        main.postDelayed(connectTimeout, CONNECT_MS);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                listener.onLog("蓝牙链路已建立，发现服务…");
                try {
                    g.discoverServices();
                } catch (SecurityException e) {
                    listener.onConnected(false, "发现服务权限不足");
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                ready = false;
                listener.onDisconnected("连接已断开 status=" + status);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onConnected(false, "发现服务失败 status=" + status);
                return;
            }
            writeChar = null;
            notifyChar = null;
            nameChar = null;
            try {
                for (BluetoothGattService svc : g.getServices()) {
                    for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                        String u = ch.getUuid().toString().toLowerCase(Locale.ROOT);
                        if (u.equals(Protocol.WRITE_UUID)) writeChar = ch;
                        if (u.equals(Protocol.NOTIFY_UUID)) notifyChar = ch;
                        if (u.equals("00002a00-0000-1000-8000-00805f9b34fb")) nameChar = ch;
                    }
                }
            } catch (SecurityException e) {
                listener.onConnected(false, "读取服务权限不足");
                return;
            }
            if (writeChar == null || notifyChar == null) {
                listener.onConnected(false, "没有找到 FF02/FF01 特征");
                return;
            }
            int props = writeChar.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }
            try {
                g.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor desc = notifyChar.getDescriptor(
                        UUID.fromString(Protocol.CCCD_UUID));
                if (desc != null) {
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(desc);
                } else {
                    markReady();
                }
            } catch (SecurityException e) {
                listener.onConnected(false, "启用通知权限不足");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (descriptor != null && Protocol.CCCD_UUID.equalsIgnoreCase(
                    descriptor.getUuid().toString())) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    markReady();
                } else {
                    listener.onConnected(false, "启用通知失败 status=" + status);
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (!"00002a00-0000-1000-8000-00805f9b34fb"
                    .equalsIgnoreCase(ch.getUuid().toString())) return;
            handleDeviceName(ch.getValue(), status);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch,
                                         byte[] value, int status) {
            if (!"00002a00-0000-1000-8000-00805f9b34fb"
                    .equalsIgnoreCase(ch.getUuid().toString())) return;
            handleDeviceName(value, status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] v = ch.getValue();
            if (v == null || v.length == 0) return;
            rx.append(new String(v, StandardCharsets.US_ASCII));
            parseAvailable();
        }
    };

    private void markReady() {
        ready = true;
        if (connectTimeout != null) main.removeCallbacks(connectTimeout);
        connectTimeout = null;
        String n = safeName(device);
        if (!n.isEmpty()) {
            selectedName = n;
            listener.onDeviceName(n);
        }
        listener.onConnected(true, "水表已连接");
        listener.onReady();
        main.postDelayed(this::readDeviceName, 250);
    }

    private void readDeviceName() {
        if (gatt == null || nameChar == null) return;
        if ((nameChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) return;
        try {
            gatt.readCharacteristic(nameChar);
        } catch (SecurityException ignored) {
        }
    }

    private void handleDeviceName(byte[] value, int status) {
        if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.length == 0) return;
        String n = new String(value, StandardCharsets.UTF_8).trim();
        if (!n.isEmpty()) {
            selectedName = n;
            listener.onDeviceName(n);
        }
    }

    private void parseAvailable() {
        while (true) {
            int start = rx.indexOf("#60");
            if (start < 0) {
                if (rx.length() > 512) rx.setLength(0);
                return;
            }
            if (start > 0) rx.delete(0, start);
            if (rx.length() < 7) return;
            int len;
            try {
                len = Integer.parseInt(rx.substring(3, 7), 16);
            } catch (Exception e) {
                rx.deleteCharAt(0);
                continue;
            }
            if (len <= 0 || len > 512) {
                rx.deleteCharAt(0);
                continue;
            }
            int total = 11 + len * 2;
            if (rx.length() < total) return;
            String frame = rx.substring(0, total);
            rx.delete(0, total);
            Protocol.Frame f = Protocol.parseFrame(frame);
            if (f != null) frames.offer(f);
        }
    }

    public Protocol.Frame sendAndWait(String cmd, String data, long timeoutMs)
            throws IOException, TimeoutException, InterruptedException {
        if (!ready || gatt == null || writeChar == null) {
            throw new IOException("水表未连接");
        }
        frames.clear();
        writeFrame(Protocol.buildFrame(cmd, data));
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Protocol.Frame f = frames.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (f != null && f.valid && cmd.equalsIgnoreCase(f.funCode)) return f;
        }
        throw new TimeoutException("等待 " + cmd + " 回帧超时");
    }

    private void writeFrame(String frame) throws IOException, InterruptedException {
        byte[] all = frame.getBytes(StandardCharsets.US_ASCII);
        for (int off = 0; off < all.length; off += CHUNK) {
            byte[] part = Arrays.copyOfRange(all, off, Math.min(off + CHUNK, all.length));
            boolean ok = false;
            for (int retry = 0; retry < 3 && !ok; retry++) {
                try {
                    writeChar.setValue(part);
                    ok = gatt.writeCharacteristic(writeChar);
                } catch (SecurityException e) {
                    throw new IOException("缺少蓝牙写入权限");
                }
                if (!ok) Thread.sleep(400);
            }
            if (!ok) throw new IOException("GATT 写入失败");
            Thread.sleep(CHUNK_DELAY_MS);
        }
    }

    public Protocol.Query queryStatus() throws Exception {
        Protocol.Frame f = sendAndWait("23", "00", 10000);
        return Protocol.parseQuery23(f.dataArea);
    }

    public void close() {
        ready = false;
        if (connectTimeout != null) main.removeCallbacks(connectTimeout);
        connectTimeout = null;
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Exception ignored) {
        }
        gatt = null;
        writeChar = null;
        notifyChar = null;
        nameChar = null;
        rx.setLength(0);
        frames.clear();
    }
}
