package com.yg.watervalve;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 计费自检：拿云端账单和本地账本对一对，判断「零用量结算到底有没有扣钱」，
 * 从而决定要不要打开「零用量连费率一起清零」这个实验开关。
 *
 * 判断依据（按可信度排序）：
 * 1. 本地账本最后一笔记了实扣金额 > 0 → 云端确实扣了，需要开；
 * 2. 云端最近一笔消费的时间晚于上次结算、金额 > 起步价 → 需要开；
 * 3. 云端最近一笔消费金额为 0 或没有新消费 → 不需要开。
 */
public final class BillingCheck {

    /** 起步价，低于这个数就不算「被扣钱」 */
    private static final double EPS = 0.03;

    public static final class Result {
        public boolean known;          // 能不能给出结论
        public boolean needZeroRate;   // 是否建议开启实验
        public String verdict = "";    // 一句话结论
        public String detail = "";     // 明细（多行）
        public double lastBillMoney;   // 最近一笔云端消费金额（元）
        public String lastBillTime = "";
    }

    private BillingCheck() {}

    public static Result run(Context ctx, Cloud.Session s) {
        Result r = new Result();
        SharedPreferences p = ctx.getSharedPreferences("water_valve", Context.MODE_PRIVATE);
        StringBuilder d = new StringBuilder();

        // ── 本地账本最后一笔 ───────────────────────────────
        long ledgerTs = 0;
        int ledgerSeconds = 0;
        double ledgerPaid = 0;
        double ledgerOfficial = 0;
        try {
            JSONArray arr = new JSONArray(p.getString("ledger", "[]"));
            JSONObject last = arr.length() == 0 ? null : arr.optJSONObject(arr.length() - 1);
            if (last != null) {
                ledgerTs = last.optLong("ts", 0);
                ledgerSeconds = last.optInt("seconds", 0);
                ledgerPaid = last.optDouble("paid", 0);
                ledgerOfficial = last.optDouble("official", 0);
                d.append("本地最后一笔：")
                        .append(fmt(ledgerTs)).append(" · ").append(ledgerSeconds).append(" 秒")
                        .append(" · 应扣 ¥").append(String.format(Locale.CHINA, "%.3f", ledgerOfficial))
                        .append(" · 实扣 ¥").append(String.format(Locale.CHINA, "%.3f", ledgerPaid))
                        .append('\n');
            } else {
                d.append("本地最后一笔：还没有账本记录\n");
            }
        } catch (Exception e) {
            d.append("本地账本读取失败：").append(e).append('\n');
        }

        // ── 云端账单 ──────────────────────────────────────
        String month = new SimpleDateFormat("yyyy-MM", Locale.CHINA).format(new Date());
        double bestMoney = -1;
        String bestTime = "";
        String bestOrder = "";
        String bestDesc = "";
        long bestStamp = 0;
        try {
            JSONArray bills = Cloud.bills(s, month, 1);
            for (int i = 0; i < bills.length(); i++) {
                JSONObject b = bills.optJSONObject(i);
                if (b == null || b.optInt("billType", 0) != 2) continue;
                JSONObject c = b.optJSONObject("consumeBillDTO");
                if (c == null) continue;
                double money = c.optDouble("consumeMoney", 0);
                String time = c.optString("consumeDate", "");
                long stamp = parseStamp(time);
                if (stamp >= bestStamp) {
                    bestStamp = stamp;
                    bestMoney = money;
                    bestTime = time;
                    bestOrder = c.optString("orderId", "");
                    bestDesc = c.optString("description", "");
                }
            }
            d.append("云端 ").append(month).append(" 账单：")
                    .append(bills.length()).append(" 条\n");
            if (bestMoney >= 0) {
                d.append("最近一笔消费：").append(bestTime)
                        .append(" · ¥").append(String.format(Locale.CHINA, "%.3f", bestMoney))
                        .append(bestOrder.isEmpty() ? "" : " · 订单 " + bestOrder)
                        .append('\n');
                if (!bestDesc.isEmpty()) d.append("  说明：").append(bestDesc).append('\n');
            } else {
                d.append("最近一笔消费：本月还没有\n");
            }
        } catch (Exception e) {
            d.append("云端账单查询失败：").append(msg(e)).append('\n');
            r.detail = d.toString();
            r.verdict = (e instanceof Cloud.ApiException && ((Cloud.ApiException) e).code == 8)
                    ? "登录已失效，先在登录页重新登录，再回来自检"
                    : "账单没查成，先把这条报错发我（也可能只是这个月还没消费）";
            return r;
        }

        r.lastBillMoney = Math.max(bestMoney, 0);
        r.lastBillTime = bestTime;
        r.detail = d.toString();

        // ── 结论 ─────────────────────────────────────────
        boolean switchOn = p.getBoolean("zero_rate", false);
        if (ledgerPaid > EPS) {
            r.known = true;
            r.needZeroRate = true;
            r.verdict = "上次结算云端真扣了 ¥" + String.format(Locale.CHINA, "%.3f", ledgerPaid)
                    + "，这台设备的计费跟着费率走 → 建议开启实验开关";
            return r;
        }
        if (bestMoney > EPS && bestStamp >= ledgerTs - 10 * 60 * 1000L && ledgerTs > 0) {
            r.known = true;
            r.needZeroRate = true;
            r.verdict = "云端在 " + bestTime + " 记了一笔 ¥"
                    + String.format(Locale.CHINA, "%.3f", bestMoney)
                    + "，时间就在你上次结算之后 → 零用量没生效，建议开启实验开关";
            return r;
        }
        if (bestMoney > 0.001 && bestMoney <= EPS) {
            r.known = true;
            r.needZeroRate = true;
            r.verdict = "云端最近一笔只扣了 ¥"
                    + String.format(Locale.CHINA, "%.3f", bestMoney)
                    + "（起步价级别），说明零用量只清掉一部分 → 建议开着实验再测一次";
            return r;
        }
        if (bestMoney >= 0 && bestMoney <= 0.001) {
            r.known = true;
            r.needZeroRate = false;
            r.verdict = "最近一笔云端消费是 ¥0，零用量结算没有扣钱 → 不用开这个开关";
            return r;
        }
        if (bestMoney < 0) {
            r.known = false;
            r.verdict = "本月还没有消费记录，判断不了。可以点「实测一次」，放水 8 秒再结算，用余额差直接验证";
            return r;
        }
        r.known = false;
        r.verdict = "账单时间对不上本地结算，判断不了。可以点「实测一次」直接看余额差";
        return r;
    }

    /** 「2026-11-09 23:41:05」这类时间转毫秒，解析不了就返回 0。 */
    private static long parseStamp(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        String t = s.trim().replace('T', ' ');
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss"};
        for (String pat : patterns) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(pat, Locale.CHINA);
                f.setLenient(true);
                Date d = f.parse(t);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static String fmt(long ts) {
        if (ts <= 0) return "-";
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ts));
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
