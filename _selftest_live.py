# -*- coding: utf-8 -*-
"""对真实运行中的程序做一轮黑盒测试（走 HTTP，等同界面点击）。

只做安全动作：读状态/偏好/统计、查账户信息与账单、扫描蓝牙、查询状态。
不做：开阀、关阀、上传、结算。
"""
import json
import os
import sys
import time

import requests

BASE = "http://127.0.0.1:18801"
FAIL = []


def check(name, cond, detail=""):
    print(("  [PASS] " if cond else "  [FAIL] ") + name + (f"  {detail}" if detail else ""))
    if not cond:
        FAIL.append(name)


def get(path, **kw):
    return requests.get(BASE + path, timeout=15, **kw).json()


def post(path, **kw):
    return requests.post(BASE + path, timeout=20, **kw).json()


def log_lines(since=0):
    d = get(f"/api/logs?since={since}")
    out = []
    for ln in d.get("lines") or []:
        # 日志行是 [level, text, display, n]
        if isinstance(ln, (list, tuple)):
            out.append(str(ln[2] if len(ln) > 2 else ln[0]))
        else:
            out.append(str(ln))
    return out, d.get("next", 0)


def wait_idle(timeout=120):
    t = time.time() + timeout
    while time.time() < t:
        st = get("/api/state")
        if not st.get("busy"):
            return True
        time.sleep(1)
    return False


st = None
for _ in range(150):
    try:
        st = get("/api/state")
        break
    except Exception:
        time.sleep(0.2)
if st is None:
    print("服务没起来")
    sys.exit(1)

print("[A] 基础状态")
check("ok", st.get("ok") is True)
check("免激活", st.get("need_activation") is False, f"license={st.get('license')}")
check("已登录", st.get("account") == "13800000000", str(st.get("account")))
check("余额", st.get("balance") is not None, str(st.get("balance")))
check("设备档案", bool((st.get("device_info") or {}).get("place")),
      str((st.get("device_info") or {}).get("place")))

print("\n[B] 偏好 / 统计 / 首页")
pr = get("/api/prefs")
check("prefs", pr.get("ok") is True, json.dumps(pr.get("prefs"), ensure_ascii=False))
check("stats", "count" in get("/api/stats"), "")
page = requests.get(BASE + "/", timeout=15)
check("首页 200", page.status_code == 200 and len(page.content) > 50000,
      f"{page.status_code} / {len(page.content)//1024} KB")

print("\n[C] 账户信息 + 本月账单（云端只读）")
wait_idle(60)
n0 = get("/api/logs?since=0").get("next", 0)
post("/api/account/info")
post("/api/bill")
lines = []
deadline = time.time() + 40
while time.time() < deadline:
    time.sleep(2)
    got, _ = log_lines(n0)
    lines = got
    if any("本月扣费" in x or "本月无账单" in x for x in got):
        break
for line in lines:
    if any(k in line for k in ("账户信息", "可用金额", "本月账单", "消费",
                               "本月扣费", "本月无账单")):
        print("      " + line.strip()[:110])
check("账单出结果", any(("本月扣费" in x or "本月无账单" in x) for x in lines))
check("账单里能看到消费", any("消费者" in x or "消费" in x for x in lines))

print("\n[D] 蓝牙扫描（1 轮 15 秒，只收广播）")
wait_idle(30)
post("/api/device/scan", json={"rounds": 1})
sr = {}
deadline = time.time() + 130
while time.time() < deadline:
    time.sleep(3)
    sr = get("/api/device/scan_result")
    if sr.get("state") in ("done", "error"):
        break
sr = get("/api/device/scan_result")
devs = sr.get("devices") or []
check("扫描完成", sr.get("state") == "done", f"state={sr.get('state')} err={sr.get('err')}")
print(f"      扫到 {len(devs)} 台，疑似水表 {sum(1 for d in devs if d.get('suspected'))} 台")
for d in devs:
    if d.get("suspected") or d.get("is_saved"):
        print(f"      {d.get('rssi')}dBm  {d.get('name')}  {d.get('mac')}"
              f"{'  ← 已保存' if d.get('is_saved') else ''}")

print("\n[E] 查询状态（连不上必须优雅失败，不能开阀）")
wait_idle(60)
n1 = get("/api/logs?since=0").get("next", 0)
post("/api/status/query")
deadline = time.time() + 90
act = {}
while time.time() < deadline:
    time.sleep(3)
    st2 = get("/api/state")
    act = st2.get("last_action") or {}
    if act.get("ts") and not st2.get("busy"):
        break
check("查询给出结论", bool(act.get("ts")), f"ok={act.get('ok')} msg={act.get('msg')}")
got, _ = log_lines(n1)
for line in got[-5:]:
    print("      " + line.strip()[:110])

print("\n[F] 不应新增结算记录（黑盒测试不开阀）")
p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "settle_log.json")
if os.path.exists(p):
    rows = json.load(open(p, encoding="utf-8"))
    print(f"      settle_log 现有 {len(rows)} 笔（本次测试前为 0）")
else:
    print("      settle_log.json 不存在 → 本轮没发生任何结算 ✅")

print("\n" + "=" * 60)
print("  结果：" + ("全部通过 ✅" if not FAIL else f"{len(FAIL)} 项未通过 → {FAIL}"))
print("=" * 60)
sys.exit(0 if not FAIL else 1)
