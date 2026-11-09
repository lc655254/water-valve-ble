package com.yg.watervalve;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 凯路云接口：短信登录、BLE 下单、零用量流水上传。 */
public final class Cloud {
    public static final String BASE = "https://v3-api.china-qzxy.cn";
    private static final String UA = "okhttp/4.2.2";
    private static final int BIG_TYPE_ID = 4;
    private static final int SMALL_TYPE_ID = 1;

    private Cloud() {}

    /** 会话失效（errorCode=8）时回调一次，让上层清掉死会话。 */
    public interface SessionWatcher {
        void onExpired();
    }

    private static volatile SessionWatcher sessionWatcher;
    private static volatile long lastExpiredNotify;

    public static void watchSession(SessionWatcher w) {
        sessionWatcher = w;
    }

    /** 云端用 HTTP 200 + errorCode=8 表示登录失效，这里统一识别，5 秒内只通知一次。 */
    private static void checkSession(JSONObject d) {
        if (d == null || d.optInt("errorCode", 0) != 8) return;
        long now = System.currentTimeMillis();
        if (now - lastExpiredNotify < 5000) return;
        lastExpiredNotify = now;
        SessionWatcher w = sessionWatcher;
        if (w != null) w.onExpired();
    }

    public static String smsSecret(String phone) {
        return Protocol.md5(phone.substring(0, 3) + phone.substring(7) + "klcx");
    }

    public static void sendSms(String phone) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("phoneSystem", "ios");
        p.put("platform", "1");
        p.put("secret", smsSecret(phone));
        p.put("telephone", phone);
        p.put("typeId", "3");
        p.put("version", "6.5.26");
        JSONObject d = get("/user/verification/code/get", p);
        int code = d.optInt("errorCode", -1);
        if (code != 0) throw new ApiException(code, d.optString("errorMessage", "发码失败"));
    }

    public static Session loginByCode(String phone, String smsCode) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("phoneSystem", "ios");
        p.put("smsCode", smsCode);
        p.put("telephone", phone);
        p.put("type", "5");
        p.put("version", "6.5.26");
        JSONObject d = postForm("/user/registerAndLogin", p);
        int code = d.optInt("errorCode", -1);
        if (code != 0) throw new ApiException(code, d.optString("errorMessage", "登录失败"));
        JSONObject data = d.getJSONObject("data");
        JSONObject ua = data.getJSONObject("userAccount");
        Session s = new Session();
        s.loginCode = safeString(data, "loginCode");
        s.userId = safeString(data, "userId");
        s.projectId = safeString(ua, "projectId");
        s.accountId = safeString(ua, "accountId");
        s.telephone = safeString(data, "telephone");
        if (s.telephone.isEmpty()) s.telephone = phone;
        s.userName = safeString(ua, "name");
        s.balance = moneyText(ua.opt("accountRealMoney"));
        if (s.loginCode.isEmpty() || s.userId.isEmpty()) {
            throw new ApiException(-1, "登录返回缺少 loginCode/userId");
        }
        return s;
    }

    public static String fmtMoney(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return "";
        try {
            double v = Double.parseDouble(s);
            if (s.contains(".")) return String.format(java.util.Locale.CHINA, "%.3f", v);
            return String.format(java.util.Locale.CHINA, "%.3f", v / 1000.0);
        } catch (Exception e) {
            return s;
        }
    }

    private static String safeString(JSONObject o, String key) {
        if (o == null) return "";
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return "";
        String s = String.valueOf(v);
        return "null".equalsIgnoreCase(s) ? "" : s;
    }

    private static String moneyText(Object v) {
        if (v == null || v == JSONObject.NULL) return "";
        if (v instanceof Number) {
            return String.format(java.util.Locale.CHINA, "%.3f",
                    ((Number) v).doubleValue() / 1000.0);
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return "";
        try {
            return s.contains(".") ? s
                    : String.format(java.util.Locale.CHINA, "%.3f", Double.parseDouble(s) / 1000.0);
        } catch (Exception e) {
            return s;
        }
    }

    public static String refreshBalance(Session s) throws Exception {
        if (s == null) return null;
        Map<String, String> p = new LinkedHashMap<>();
        p.put("loginCode", s.loginCode);
        p.put("userId", s.userId);
        p.put("accountId", s.accountId);
        p.put("projectId", s.projectId);
        p.put("telephone", s.telephone);
        p.put("phoneSystem", "ios");
        p.put("version", "6.5.26");
        p.put("telPhone", s.telephone);
        JSONObject d = get("/account/wallet", p);
        if (d.optInt("errorCode", -1) != 0) return null;
        JSONObject data = d.optJSONObject("data");
        if (data == null) return null;
        for (String k : new String[]{"accountRealMoneyStr", "money", "accountRealMoney"}) {
            Object v = data.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            String m = moneyText(v);
            if (!m.isEmpty()) return m;
        }
        return null;
    }

    public static DeviceInfo queryDevice(Session s, String mac) throws Exception {
        if (s == null || mac == null || mac.trim().isEmpty()) return null;
        Map<String, String> p = new LinkedHashMap<>();
        p.put("projectId", s.projectId);
        p.put("isNew", "1");
        p.put("macAddress", mac.trim());
        p.put("loginCode", s.loginCode);
        p.put("userId", s.userId);
        p.put("accountId", s.accountId);
        p.put("telephone", s.telephone);
        JSONObject d = get("/device/info/mac", p);
        if (d.optInt("errorCode", -1) != 0) return null;
        Object data = d.opt("data");
        if (data instanceof JSONArray) {
            JSONArray arr = (JSONArray) data;
            data = arr.length() == 0 ? null : arr.opt(0);
        }
        if (!(data instanceof JSONObject)) {
            DeviceInfo empty = new DeviceInfo();
            empty.found = false;
            return empty;
        }
        JSONObject o = (JSONObject) data;
        DeviceInfo di = new DeviceInfo();
        di.found = true;
        di.deviceName = safeString(o, "deviceName");
        if (di.deviceName.isEmpty()) di.deviceName = safeString(o, "DevName");
        di.deviceId = safeString(o, "deviceId");
        di.snCode = safeString(o, "snCode");
        di.buildingName = safeString(o, "buildingName");
        if (di.buildingName.isEmpty()) di.buildingName = safeString(o, "LDName");
        di.floorName = safeString(o, "floorName");
        if (di.floorName.isEmpty()) di.floorName = safeString(o, "LCName");
        di.roomName = safeString(o, "roomName");
        if (di.roomName.isEmpty()) di.roomName = safeString(o, "FJName");
        di.online = o.optBoolean("isOnline", false);
        return di;
    }

    public static RateOrder rateOrder(Session s, int deviceId, String randomNumber,
                                      String commMode, String mac) throws Exception {
        String sig = Protocol.signature(
                "deviceId" + deviceId + "randomNumber" + randomNumber
                        + "telephone" + s.telephone + "xfModel0", s.loginCode);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("xfModel", "0");
        p.put("loginCode", s.loginCode);
        p.put("protocolType", commMode);
        p.put("randomNumber", randomNumber);
        p.put("projectId", s.projectId);
        p.put("orderAuthCode", "ble_" + System.currentTimeMillis());
        p.put("deviceId", String.valueOf(deviceId));
        p.put("smallTypeId", String.valueOf(SMALL_TYPE_ID));
        p.put("signature", sig);
        p.put("telephone", s.telephone);
        p.put("macAddress", mac);
        p.put("bigTypeId", String.valueOf(BIG_TYPE_ID));
        p.put("userId", s.userId);
        p.put("accountId", s.accountId);
        JSONObject d = postForm("/order/ali/downRate/bluetooth/rateOrder", p);
        int code = d.optInt("errorCode", -1);
        if (code != 0) throw new ApiException(code, d.optString("errorMessage", "下单失败"));
        JSONObject data = d.optJSONObject("data");
        RateOrder r = new RateOrder();
        if (data != null) {
            r.downData = data.optString("downData", "");
            r.rate = data.optInt("rate", 0);
            // 下单时云端记录的用量计数：关阀结算时用它做差，才是「本次用了多少」
            r.useCount = data.optInt("useCount", -1);
            r.raw = data.toString();
        }
        if (r.downData.isEmpty()) throw new ApiException(-1, "云端没有返回 downData");
        return r;
    }

    /**
     * 本月云端账单（只读）。消费单在 consumeBillDTO 里，金额单位是元。
     * 用来核对「零用量上传之后云端到底扣没扣钱」。
     */
    public static JSONArray bills(Session s, String month, int page) throws Exception {
        if (s == null) throw new ApiException(-1, "还没登录");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("telephone", s.telephone);
        p.put("curNum", String.valueOf(page));
        p.put("billRequestType", "0");
        p.put("month", month);
        p.put("loginCode", s.loginCode);
        p.put("projectId", s.projectId);
        p.put("userId", s.userId);
        p.put("accountId", s.accountId);
        JSONObject d = get("/order/query/account/bill/list", p);
        int code = d.optInt("errorCode", -1);
        if (code != 0) throw new ApiException(code, d.optString("errorMessage", "账单查询失败"));
        JSONArray arr = d.optJSONArray("data");
        return arr == null ? new JSONArray() : arr;
    }

    public static String upload(Session s, String xfData, String randomNumber,
                                String commMode) throws Exception {        String sig = Protocol.signature(
                "loginCode" + s.loginCode + "telephone" + s.telephone
                        + "xfData" + xfData, s.loginCode);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("loginCode", s.loginCode);
        p.put("protocolType", commMode);
        p.put("telephone", s.telephone);
        p.put("userId", s.userId);
        p.put("accountId", s.accountId);
        p.put("xfData", xfData);
        p.put("randomNumber", randomNumber);
        p.put("signature", sig);
        p.put("projectId", s.projectId);
        for (int i = 0; i < 3; i++) {
            JSONObject d = postForm("/order/upload/bluetooth/data", p);
            int code = d.optInt("errorCode", -1);
            if (code == 531) {
                Thread.sleep(3000L + i * 3000L);
                continue;
            }
            if (code != 0 && code != 206 && code != 7) {
                throw new ApiException(code, d.optString("errorMessage", "上传失败"));
            }
            JSONObject data = d.optJSONObject("data");
            return data == null ? null : data.optString("clData", null);
        }
        throw new ApiException(531, "上传连续被限流");
    }

    private static JSONObject get(String path, Map<String, String> params) throws Exception {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(URLEncoder.encode(e.getKey(), "UTF-8"));
            q.append('=');
            q.append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        String url = BASE + path + (q.length() > 0 ? "?" + q : "");
        return request("GET", url, null);
    }

    private static JSONObject postForm(String path, Map<String, String> params) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), "UTF-8"));
            body.append('=');
            body.append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        return request("POST", BASE + path, body.toString());
    }

    private static JSONObject request(String method, String url, String body) throws Exception {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        int code = -1;
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            if ("POST".equals(method)) {
                byte[] b = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(b);
                }
            }
            code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    char[] buf = new char[2048];
                    int n;
                    while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
                }
            }
            String note = "net=" + DebugLog.netType()
                    + (c.getHeaderField("Content-Type") == null ? ""
                    : " ct=" + c.getHeaderField("Content-Type"));
            DebugLog.http(method, url, body, code, sb.toString(),
                    System.currentTimeMillis() - t0, note, null);
            if (sb.length() == 0) throw new ApiException(code, "云端返回空响应 HTTP " + code);
            JSONObject d = new JSONObject(sb.toString());
            checkSession(d);
            return d;
        } catch (Exception e) {
            DebugLog.http(method, url, body, code, null,
                    System.currentTimeMillis() - t0, "net=" + DebugLog.netType(), e);
            throw e;
        } finally {
            c.disconnect();
        }
    }

    public static final class ApiException extends Exception {
        public final int code;
        public ApiException(int code, String msg) {
            super(msg == null || msg.isEmpty() ? ("errorCode=" + code) : msg);
            this.code = code;
        }
    }

    public static final class RateOrder {
        public String downData = "";
        public int rate;
        public int useCount = -1;
        public String raw = "";
    }

    public static final class DeviceInfo {
        public boolean found;
        public String deviceName = "";
        public String deviceId = "";
        public String snCode = "";
        public String buildingName = "";
        public String floorName = "";
        public String roomName = "";
        public boolean online;
    }

    public static final class Session {
        public String loginCode = "";
        public String userId = "";
        public String projectId = "";
        public String accountId = "";
        public String telephone = "";
        public String userName = "";
        public String balance = "";

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("loginCode", loginCode);
                o.put("userId", userId);
                o.put("projectId", projectId);
                o.put("accountId", accountId);
                o.put("telephone", telephone);
                o.put("userName", userName);
                o.put("balance", balance);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Session fromJson(String raw) {
            if (raw == null || raw.isEmpty()) return null;
            try {
                JSONObject o = new JSONObject(raw);
                Session s = new Session();
                s.loginCode = o.optString("loginCode", "");
                s.userId = o.optString("userId", "");
                s.projectId = o.optString("projectId", "");
                s.accountId = o.optString("accountId", "");
                s.telephone = o.optString("telephone", "");
                s.userName = clean(o.optString("userName", ""));
                s.balance = clean(o.optString("balance", ""));
                return s.loginCode.isEmpty() ? null : s;
            } catch (Exception e) {
                return null;
            }
        }

        private static String clean(String s) {
            return s == null || "null".equalsIgnoreCase(s) ? "" : s;
        }
    }
}
