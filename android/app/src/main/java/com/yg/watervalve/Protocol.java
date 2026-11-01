package com.yg.watervalve;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** KLCXKJ 水表帧协议 + 云端签名/AES。纯 Java，无 Android BLE 依赖。 */
public final class Protocol {
    public static final String SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb";
    public static final String WRITE_UUID = "0000ff02-0000-1000-8000-00805f9b34fb";
    public static final String NOTIFY_UUID = "0000ff01-0000-1000-8000-00805f9b34fb";
    public static final String CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb";
    public static final String SIGN_KEY = "sign-kailu-855c74a88b8b494187c99b08b8c9a744";
    public static final byte[] AES_KEY = "20210118klcx@002".getBytes(StandardCharsets.US_ASCII);
    public static final int FLOW_BASE_LEN = 42;
    public static final int[] CLEAR_SIZES = {22, 41, 23, -1, 27, 6};

    private Protocol() {}

    public static String normHex(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) b.append(Character.toUpperCase(c));
        }
        return b.toString();
    }

    public static int sumPayload(String hex) {
        String h = normHex(hex);
        int s = 0;
        for (int i = 0; i + 1 < h.length(); i += 2) {
            try {
                s = (s + Integer.parseInt(h.substring(i, i + 2), 16)) & 0xFF;
            } catch (NumberFormatException ignored) {
            }
        }
        return s;
    }

    public static String buildFrame(String cmd, String data) {
        String c = normHex(cmd);
        if (c.length() < 2) c = "00";
        c = c.substring(c.length() - 2);
        String d = normHex(data);
        if (d.isEmpty()) d = "00";
        String payload = "80" + c + "00" + d;
        String len = String.format("%04X", payload.length() / 2);
        String cs = String.format("%02X", sumPayload(payload));
        return ("#60" + len + payload + cs + "16").toUpperCase();
    }

    public static Frame parseFrame(String raw) {
        String f = normHex(raw);
        if (f.length() < 15 || !f.startsWith("#") || !f.endsWith("16")) return null;
        int len;
        try {
            len = Integer.parseInt(f.substring(3, 7), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        int endPayload = 7 + len * 2;
        String control, fun, extend, data, cs;
        if (endPayload + 4 > f.length()) {
            control = sub(f, 7, 9);
            fun = sub(f, 9, 11);
            extend = sub(f, 11, 13);
            data = sub(f, 13, f.length() - 4);
            cs = sub(f, f.length() - 4, f.length() - 2);
        } else {
            String payload = sub(f, 7, endPayload);
            control = sub(payload, 0, 2);
            fun = sub(payload, 2, 4);
            extend = sub(payload, 4, 6);
            data = sub(payload, 6, payload.length());
            cs = sub(f, endPayload, endPayload + 2);
        }
        int calc = sumPayload(control + fun + extend + data) & 0xFF;
        boolean valid = String.format("%02X", calc).equalsIgnoreCase(cs);
        return new Frame(f, control, fun, extend, data, cs, valid);
    }

    public static Query parseQuery23(String da) {
        String d = normHex(da);
        Query q = new Query();
        q.dataArea = d;
        q.returnCode = sub(d, 0, 2);
        q.productId = sub(d, 2, 10);
        q.deviceID = sub(d, 10, 18);
        q.accountId = sub(d, 18, 26);
        q.mac = sub(d, 26, 38);
        q.commMode = sub(d, 38, 40);
        q.macTime = sub(d, 40, 48);
        q.tacTimes = sub(d, 48, 56);
        q.onCharge = sub(d, 56, 58);
        q.macType = sub(d, 58, 62);
        q.chargeMethod = sub(d, 62, 64);
        return q;
    }

    public static String fmtMacHex(String hexmac) {
        String h = normHex(hexmac);
        if (h.length() != 12) return hexmac == null ? "" : hexmac;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) b.append(':');
            b.append(h, i, i + 2);
        }
        return b.toString();
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(32);
            for (byte x : out) b.append(String.format("%02x", x & 0xFF));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String signature(String param, String loginCode) {
        String inner = md5(param + "&key=" + loginCode);
        return md5(inner + "&key=" + SIGN_KEY);
    }

    public static String aesDecrypt(String clData) {
        if (clData == null || clData.isEmpty()) return null;
        byte[] raw;
        try {
            raw = Base64.decode(clData, Base64.DEFAULT);
        } catch (Exception e) {
            try {
                raw = hexToBytes(clData);
            } catch (Exception e2) {
                return null;
            }
        }
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"));
            String text = new String(c.doFinal(raw), StandardCharsets.UTF_8);
            int p = text.indexOf('-');
            return p >= 0 ? text.substring(p + 1) : text;
        } catch (Exception e) {
            return null;
        }
    }

    public static Flow parseFlow(String collectFull) {
        String f = normHex(collectFull);
        Flow out = new Flow();
        out.raw = f;
        out.len = f.length() / 2;
        if (f.length() < FLOW_BASE_LEN * 2) {
            out.ok = false;
            return out;
        }
        out.ok = true;
        out.status = sub(f, 0, 2);
        out.time = sub(f, 2, 14);
        out.productId = sub(f, 14, 22);
        out.deviceId = sub(f, 22, 30);
        out.accountId = sub(f, 30, 38);
        out.accountType = sub(f, 38, 40);
        out.useCount = sub(f, 40, 48);
        out.prepay = sub(f, 48, 56);
        out.rate1 = sub(f, 56, 64);
        out.rate2 = sub(f, 64, 72);
        out.mac = sub(f, 72, 84);
        out.ext = sub(f, 84, f.length());
        out.useCountI = parseIntHex(out.useCount);
        out.prepayI = parseIntHex(out.prepay);
        out.rate1I = parseIntHex(out.rate1);
        out.rate2I = parseIntHex(out.rate2);
        return out;
    }

    public static String zeroUsage(String collectFull) {
        Flow f = parseFlow(collectFull);
        if (!f.ok) throw new IllegalArgumentException("流水长度不足 " + FLOW_BASE_LEN + "B");
        boolean keepRate = f.ext != null && !f.ext.isEmpty();
        return (f.status + f.time + f.productId + f.deviceId + f.accountId
                + f.accountType + "0000000000000000"
                + (keepRate ? f.rate1 + f.rate2 : "0000000000000000")
                + f.mac + f.ext).toUpperCase();
    }

    public static List<Labeled> clearCandidates(String collect, String clearCode,
                                                String full, String prefer) {
        List<Labeled> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        addCandidate(out, seen, "云端clearCode", clearCode);

        List<Labeled> local = new ArrayList<>();
        String c = normHex(collect);
        if (!c.isEmpty()) {
            int n = c.length() / 2;
            for (int size : CLEAR_SIZES) {
                int take = size == -1 ? n : Math.min(size, n);
                if (take > 0) {
                    local.add(new Labeled(size == -1 ? "采集全长" : "采集前" + size + "B",
                            c.substring(0, take * 2)));
                }
            }
        }
        String hf = normHex(full);
        if (!hf.isEmpty()) {
            local.add(new Labeled("含status全长", hf));
            int base = Math.min(FLOW_BASE_LEN, hf.length() / 2);
            if (base > 0) local.add(new Labeled("含status基础" + FLOW_BASE_LEN + "B",
                    hf.substring(0, base * 2)));
        }
        if (prefer != null && !prefer.isEmpty()) {
            local.sort((a, b) -> (a.name.equals(prefer) ? 0 : 1) - (b.name.equals(prefer) ? 0 : 1));
        }
        for (Labeled x : local) addCandidate(out, seen, x.name, x.payload);
        return out;
    }

    private static void addCandidate(List<Labeled> out, LinkedHashSet<String> seen,
                                     String name, String payload) {
        String p = normHex(payload);
        if (p.isEmpty() || !seen.add(p)) return;
        out.add(new Labeled(name, p));
    }

    private static String sub(String s, int a, int b) {
        if (s == null || a >= s.length()) return "";
        int end = Math.min(b, s.length());
        if (end <= a) return "";
        return s.substring(a, end);
    }

    private static int parseIntHex(String s) {
        try {
            return Integer.parseInt(s, 16);
        } catch (Exception e) {
            return -1;
        }
    }

    private static byte[] hexToBytes(String s) {
        String h = normHex(s);
        if ((h.length() & 1) != 0) throw new IllegalArgumentException("odd hex");
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static final class Frame {
        public final String raw, control, funCode, extend, dataArea, cs;
        public final boolean valid;
        Frame(String raw, String control, String funCode, String extend,
              String dataArea, String cs, boolean valid) {
            this.raw = raw; this.control = control; this.funCode = funCode;
            this.extend = extend; this.dataArea = dataArea; this.cs = cs;
            this.valid = valid;
        }
    }

    public static final class Query {
        public String dataArea, returnCode, productId, deviceID, accountId, mac;
        public String commMode, macTime, tacTimes, onCharge, macType, chargeMethod;
        public int deviceIdInt() {
            try { return Integer.parseInt(deviceID, 16); } catch (Exception e) { return 0; }
        }
        public int projectInt() {
            try { return Integer.parseInt(productId, 16); } catch (Exception e) { return 0; }
        }
        public String statusText() {
            switch (onCharge == null ? "" : onCharge) {
                case "00": return "空闲";
                case "01": return "放水中";
                case "02": return "刷卡";
                case "03": return "结算中";
                default: return "未知(" + onCharge + ")";
            }
        }
    }

    public static final class Flow {
        public boolean ok;
        public int len;
        public String raw, status, time, productId, deviceId, accountId, accountType;
        public String useCount, prepay, rate1, rate2, mac, ext;
        public int useCountI, prepayI, rate1I, rate2I;
        public String brief() {
            if (!ok) return len + "B 太短";
            return len + "B 次数=" + useCountI + " 预扣=" + prepayI
                    + " 费率=" + rate1I + "/" + rate2I;
        }
    }

    public static final class Labeled {
        public final String name, payload;
        public Labeled(String name, String payload) {
            this.name = name; this.payload = payload;
        }
    }
}
