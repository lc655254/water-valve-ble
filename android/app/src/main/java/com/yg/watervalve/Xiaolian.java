package com.yg.watervalve;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 智慧笑联校园账号：官方 App 的宿舍名来自学校设备库，按 BLE MAC 查询。
 * 这里只保存 token，不保存密码。
 */
public final class Xiaolian {
    private static final String PREF = "water_valve";
    private static final String K_ACCESS = "xl_access";
    private static final String K_REFRESH = "xl_refresh";

    private static final String LOGIN = "https://api.xiaolianhb.com/m/mp/h5/login";
    private static final String USER = "https://api.xiaolianhb.com/m/mp/user/one";
    private static final String DEVICE =
            "https://appwx-netapi.xiaolianhb.com/b/stu/netDevice/getDeviceByMac";

    private Xiaolian() {}

    public static final class LoginResult {
        public final boolean ok;
        public final String message;
        LoginResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    public static boolean hasToken(Context c) {
        return !prefs(c).getString(K_ACCESS, "").isEmpty();
    }

    public static void clear(Context c) {
        prefs(c).edit().remove(K_ACCESS).remove(K_REFRESH).apply();
    }

    public static synchronized LoginResult login(Context c, String mobile, String password) {
        try {
            JSONObject body = new JSONObject();
            body.put("mobile", mobile);
            body.put("password", password);
            Resp r = post(LOGIN, body, "", "");
            JSONObject j = r.json;
            if (j == null) return new LoginResult(false, "登录失败：HTTP " + r.status);
            if (!j.optBoolean("result", false)) {
                return new LoginResult(false, j.optString("msg", "登录失败"));
            }
            JSONObject data = j.optJSONObject("data");
            String at = data == null ? "" : data.optString("accessToken", "");
            String rt = data == null ? "" : data.optString("refreshToken", "");
            if (at.isEmpty()) at = r.access;
            if (rt.isEmpty()) rt = r.refresh;
            if (at.isEmpty()) return new LoginResult(false, "登录成功但没有返回 token");
            save(c, at, rt);
            return new LoginResult(true, "已同步官方宿舍名");
        } catch (Exception e) {
            return new LoginResult(false, "登录失败：" + e.getMessage());
        }
    }

    public static synchronized String deviceName(Context c, String mac) {
        if (mac == null || mac.isEmpty()) return "";
        String at = prefs(c).getString(K_ACCESS, "");
        String rt = prefs(c).getString(K_REFRESH, "");
        if (at.isEmpty()) return "";
        try {
            Resp r = queryDevice(mac, at, rt);
            applyTokens(c, r);
            if (!ok(r)) {
                if (r.status == 401 || r.code == 10001) {
                    if (refresh(c, at, rt)) {
                        at = prefs(c).getString(K_ACCESS, "");
                        rt = prefs(c).getString(K_REFRESH, "");
                        r = queryDevice(mac, at, rt);
                        applyTokens(c, r);
                    }
                }
            }
            if (!ok(r) || r.json == null) return "";
            Object data = r.json.opt("data");
            if (data instanceof JSONArray) {
                JSONArray arr = (JSONArray) data;
                data = arr.length() == 0 ? null : arr.opt(0);
            }
            if (!(data instanceof JSONObject)) return "";
            JSONObject d = (JSONObject) data;
            String n = d.optString("deviceName", "");
            if (n.isEmpty()) n = d.optString("name", "");
            return n.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static Resp queryDevice(String mac, String at, String rt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("macAddress", mac);
        return post(DEVICE, body, at, rt);
    }

    private static boolean ok(Resp r) {
        return r.json != null && r.json.optBoolean("result", false);
    }

    private static synchronized boolean refresh(Context c, String at, String rt) {
        try {
            Resp r = post(USER, new JSONObject(), at, rt);
            applyTokens(c, r);
            return ok(r);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void applyTokens(Context c, Resp r) {
        if (r.access != null && !r.access.isEmpty()) {
            prefs(c).edit().putString(K_ACCESS, r.access).apply();
        }
        if (r.refresh != null && !r.refresh.isEmpty()) {
            prefs(c).edit().putString(K_REFRESH, r.refresh).apply();
        }
    }

    private static void save(Context c, String at, String rt) {
        prefs(c).edit().putString(K_ACCESS, at).putString(K_REFRESH, rt).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static final class Resp {
        int status;
        int code;
        JSONObject json;
        String access = "";
        String refresh = "";
    }

    private static Resp post(String url, JSONObject body, String at, String rt) throws Exception {
        Resp out = new Resp();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "okhttp/4.9.3");
        if (at != null && !at.isEmpty()) c.setRequestProperty("accessToken", at);
        if (rt != null && !rt.isEmpty()) c.setRequestProperty("refreshToken", rt);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(payload);
        }
        out.status = c.getResponseCode();
        InputStream is = out.status >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = read(is);
        DebugLog.line("http", "POST " + url + " | HTTP " + out.status + " | 小联/宿舍名");
        if (text != null && !text.isEmpty()) {
            DebugLog.line("http", "  ← " + (text.length() > 4000
                    ? text.substring(0, 4000) + "…(共 " + text.length() + " 字符)" : text));
        }
        out.access = header(c, "accessToken");
        out.refresh = header(c, "refreshToken");
        try {
            out.json = new JSONObject(text);
            out.code = out.json.optInt("code", 0);
            if (out.access.isEmpty()) out.access = out.json.optString("accessToken", "");
            if (out.refresh.isEmpty()) out.refresh = out.json.optString("refreshToken", "");
        } catch (Exception ignored) {
        }
        c.disconnect();
        return out;
    }

    private static String header(HttpURLConnection c, String name) {
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                    && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return "";
    }

    private static String read(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        is.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
