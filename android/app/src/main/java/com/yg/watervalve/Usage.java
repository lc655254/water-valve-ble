package com.yg.watervalve;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从云端账单里抠出「这一单用了多少水」。
 *
 * 官方是按用水量计费的，所以账单里一定有水量；字段名各家版本不一样，
 * 这里按「明确的字段名 → 带单位的描述文本」两级去取，取不到就返回 0，
 * 由上层继续用估算值。
 */
public final class Usage {

    /** 可能的水量字段（升）。命中第一个大于 0 的就用。 */
    private static final String[] KEYS = {
            "useWater", "useWaterNum", "useWaterAmount", "waterNum", "waterAmount",
            "consumeWater", "consumeWaterNum", "useNum", "consumeNum", "useVolume",
            "waterVolume", "water", "volume", "litre", "liter", "useLitre", "useLiter",
            "useAmount", "waterUse", "useQuantity", "quantity", "num"
    };

    /** 「3.87L」「3.87升」「3870ml」「0.00387吨」这类描述 */
    private static final Pattern DESC = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(升|L|l|ml|mL|毫升|吨|t)");

    private Usage() {}

    /**
     * @return 用水量（升）；0 表示没取到
     */
    public static double litersFrom(JSONObject bill) {
        if (bill == null) return 0;
        // 1) 明确字段
        for (String k : KEYS) {
            if (!bill.has(k)) continue;
            double v = num(bill.opt(k));
            if (v > 0) return normalize(v, k);
        }
        // 2) 描述文本
        for (String k : new String[]{"description", "typeName", "remark", "memo", "detail"}) {
            String s = bill.optString(k, "");
            double v = fromText(s);
            if (v > 0) return v;
        }
        return 0;
    }

    /** 描述里带单位的数字：「消费:热水 3.87升」 */
    public static double fromText(String text) {
        if (text == null || text.isEmpty()) return 0;
        Matcher m = DESC.matcher(text);
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                String unit = m.group(2).toLowerCase(Locale.ROOT);
                if (unit.startsWith("ml") || unit.equals("毫升")) v = v / 1000.0;
                else if (unit.equals("吨") || unit.equals("t")) v = v * 1000.0;
                if (v > 0) return v;
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /** 数字字段可能是字符串，也可能单位是毫升/吨 */
    private static double num(Object o) {
        if (o == null || o == JSONObject.NULL) return 0;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static double normalize(double v, String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.contains("ml") || k.contains("milli")) return v / 1000.0;
        if (k.contains("ton")) return v * 1000.0;
        // 没有单位线索：数值特别大就当成毫升（3.87 升不会写成 3870 以外的样子）
        return v > 1000 ? v / 1000.0 : v;
    }

    /** 把账单里所有「像水量」的字段列出来，写进调试日志用。 */
    public static String dump(JSONObject bill) {
        if (bill == null) return "null";
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = bill.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = bill.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            String s = String.valueOf(v);
            if (s.length() > 40) s = s.substring(0, 40) + "…";
            sb.append(k).append('=').append(s).append("  ");
        }
        return sb.toString();
    }
}
