# -*- coding: utf-8 -*-
"""
蓝牙水库 · 水控 Web 版  WaterValveBLE_Web.py
=================================================
架构参考 D:\\Work\\BX\\Say（话术助手）：
    pywebview（无边框窗口） + Flask 本地 API + 单文件 HTML 前端

前端 web/index.html 提供：
    · 点击处圆形扩散的黑白主题切换（View Transitions API + clip-path）
    · 圆角卡片 / 徽章 / 渐变主按钮 / 分级着色日志（纯 CSS，60fps 合成层动画）

后端复用 v4.6 全部业务逻辑（WaterValveBLE_v4.py）：
    秘钥激活 · 短信验证码登录 · 多账号切换 · 开水(1) · 关水(C 零扣费结算)
    查询状态 · 选择设备 · 账户信息 · 本月账单 · 切换控制方式

运行：  python WaterValveBLE_Web.py            （或双击 3-启动UI客户端(Web版).bat）
依赖：  pywebview / flask / requests / pycryptodome / bleak
"""
import asyncio
import hashlib
import json
import logging
import os
import queue
import re
import sys
import threading
import time
import uuid
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

if "--guard" in sys.argv:
    # 打包后主 exe 兼作看门狗入口：不启动 Flask / 窗口
    sys.argv = [a for a in sys.argv if a != "--guard"]
    from valve_guard import main as _guard_main
    raise SystemExit(_guard_main())

import WaterValveBLE_v4 as wv          # 复用 v4.6 业务逻辑（不启动它的命令行）
import valve_safety as vsafety          # 防呆安全层：会话落盘 / 看门狗 / 关机拦截

from flask import Flask, jsonify, request, send_from_directory

def _res_dir():
    """页面资源目录：
    · 加密打包版：启动器把资源解密到私有临时目录，用环境变量指过来
    · 普通 exe：sys._MEIPASS
    · 源码运行：脚本目录
    """
    return os.environ.get("WV_RES_DIR") or getattr(sys, "_MEIPASS", HERE)


APP_DIR = wv._data_dir()      # 只读安装位置自动回退 %LOCALAPPDATA%\蓝牙水库
WEB_DIR = os.path.join(_res_dir(), "web")
LIC_FILE = os.path.join(APP_DIR, "license.json")
# 许可服务器地址：界面上不显示、也不用手填 —— 内置几个候选，谁通用谁。
#   ① 公网隧道（异地也能激活/续期）  ② 本机直连（隧道没开时兜底）
LIC_ENDPOINTS = [
    # 顺序：先本机（连不上是毫秒级失败），再公网隧道 —— 远端用户只有隧道那条能通
    "http://127.0.0.1:26921",                  # 本机（隧道转发目标端口）
    "http://127.0.0.1:8900",                   # 本机（老端口，兼容）
    "http://7fa4ee7e0404.ofalias.com:46700",   # 内网穿透：公网 → 本机 26921
    "http://26.10.23.68:26921",                # Radmin VPN 里的服务器（隧道挂了也能用）
]
DEFAULT_SERVER = LIC_ENDPOINTS[-1]
PORT = 18800
API_PORT = int(os.environ.get("WV_API_PORT", "18801"))


# ================= 秘钥许可（与 Tk 版一致） =================
def machine_id():
    h = hashlib.sha256(f"wvkey|{uuid.getnode()}".encode()).hexdigest()[:16].upper()
    return "-".join(h[i:i + 4] for i in range(0, 16, 4))


def load_lic():
    try:
        with open(LIC_FILE, encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) and d.get("key") else None
    except Exception:
        return None


def save_lic(d):
    with open(LIC_FILE, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)


def _ping(server, timeout=4):
    """探一下这个许可服务器通不通（/api/ping），返回 (ok, 延迟秒)"""
    import requests
    t0 = time.time()
    try:
        r = requests.get(server.rstrip("/") + "/api/ping", timeout=timeout)
        if r.status_code == 200:
            return True, round(time.time() - t0, 3)
    except Exception:
        pass
    return False, None


def pick_server(preferred="", log=print):
    """挑一个能用的许可服务器：优先上次用过的，其次内置候选（隧道 → 本机）。

    界面不显示地址，也不用手填；这里自动选。
    """
    cands = []
    for s in [preferred] + LIC_ENDPOINTS:
        s = (s or "").rstrip("/")
        if s and s not in cands:
            cands.append(s)
    for s in cands:
        ok, ms = _ping(s)
        if ok:
            if s != preferred:
                log(f"[*] 许可服务器已选：{s}（{ms}s）")
            return s
    return ""


def check_license(server, key):
    import requests
    try:
        r = requests.post(server.rstrip("/") + "/api/verify",
                          json={"key": key, "machine": machine_id()}, timeout=8)
        d = r.json()
    except Exception as e:
        return False, 0, f"连不上许可服务器（{e}）"
    if d.get("code") == 0:
        return True, d["data"]["days_left"], ""
    return False, 0, d.get("message", "校验失败")


def check_license_any(preferred, key):
    """在所有候选地址里找一个能通的来校验（隧道优先，本机兜底）"""
    srv = pick_server(preferred)
    if not srv:
        return False, 0, "连不上许可服务器（公网隧道与本机都没响应，请确认秘钥服务器已启动）", ""
    ok, days, msg = check_license(srv, key)
    return ok, days, msg, srv


# ================= 用水统计上报（只报统计，不报账号密码） =================
REPORT_LAST = [0.0]


def report_usage(force=False):
    """本地自用版：不再把账本上报给任何服务器（原逻辑已停用）。

    保留函数签名，避免其它调用点报错。
    """
    return False


LIC_DAYS = [0]          # 剩余天数（列表便于闭包修改）
LIC_SERVER = [DEFAULT_SERVER]
LIC_CHECKING = [False]      # 许可校验进行中（前端据此稍微等一下，别闪秘钥页）
LIC_READY = [False]         # 校验是否已经出结果


def license_boot():
    """本地自用版：直接放行，不连任何许可服务器、不校验秘钥。

    原逻辑（联网校验 / 3 天离线宽限 / 过期弹激活页）已整体停用。
    """
    LIC_DAYS[0] = 9999
    LIC_READY[0] = True
    LIC_CHECKING[0] = False
    print("[*] 本地自用版：已跳过秘钥校验（无限期）")
    return True


# ================= 工作线程（asyncio + BLE，串行执行） =================
class Worker:
    def __init__(self):
        self.q = queue.Queue()
        self.loop = asyncio.new_event_loop()
        self.valve = None
        self.t = threading.Thread(target=self._run, daemon=True, name="wv-worker")
        self.t.start()

    def _run(self):
        asyncio.set_event_loop(self.loop)
        while True:
            item = self.q.get()
            if item is None:
                break
            job, fut = item
            try:
                r = job(self)
                if asyncio.iscoroutine(r):
                    r = self.loop.run_until_complete(r)
                if not fut.done():
                    fut.set_result(r)
            except Exception as e:
                if not fut.done():
                    fut.set_exception(e)

    def submit(self, job):
        fut = _Future()
        self.q.put((job, fut))
        return fut

    def get_valve(self):
        if self.valve is None:
            self.valve = wv.WaterValveBLE()
        self.valve.mac, self.valve.name = wv.CFG.get("mac", ""), wv.CFG.get("name", "")
        return self.valve


class _Future:
    """极简 future（避免与 asyncio/线程池混淆）"""
    def __init__(self):
        self._ev = threading.Event()
        self._r = None
        self._e = None
        self._done = False

    def done(self):
        return self._done

    def set_result(self, r):
        self._r, self._done = r, True
        self._ev.set()

    def set_exception(self, e):
        self._e, self._done = e, True
        self._ev.set()

    def result(self, timeout=None):
        self._ev.wait(timeout)
        if self._e:
            raise self._e
        return self._r


WORKER = Worker()
BUSY = {"label": None, "n": 0}           # n = 已提交未跑完的任务数（见 run_async）
# 最近一次操作的真实结果：响应丢包时前端靠它判断「指令到底执行没执行」，
# 避免把「没连上水表」说成「本地服务无响应」，也不再把失败报成「完成」。
ACT_RESULT = {"action": None, "ok": None, "msg": "", "ts": 0.0}
# 出水状态：None=还不知道 / True=正在放水（计费中）/ False=空闲
# 来源有两处：① 开水·关水的执行结果；② 设备回帧里的 onCharge（最权威）
WATER = {"running": None, "ts": 0.0, "src": ""}


def set_water(running, src=""):
    WATER.update(running=bool(running), ts=time.time(), src=src)


def set_result(action, ok, msg=""):
    ACT_RESULT.update(action=action, ok=bool(ok), msg=msg or "", ts=time.time())
SESSION = {"s": None, "bal": None}       # 当前会话（dict）与钱包刷新出的余额（元）
SAFETY = None                            # valve_safety.Safety，main() 里初始化
ACTIONS = {}                             # 动作去重时间戳（同一动作短时间内只受理一次）

# 设备名：本地别名（自己起的名）+ 云端档案名（官方 App 同款接口）
ALIAS_FILE = os.path.join(APP_DIR, "device_alias.json")
DEV_INFO_CACHE = {}
_LAST_INFO_LOG = {}          # 档案日志去重时间戳
_LAST_SIMPLE = {}            # 人话文案最近出现时间（简洁模式防刷屏）

# 界面偏好（主题 / 主题色）：存后端文件，换实例、重装都记得住
PREFS_FILE = os.path.join(APP_DIR, "ui_prefs.json")

# ================= 省钱账本 =================
# 官方（正常走云端的）计费方式：按设备上的计费字段计费
#   · 用量：85 采集流水里的 useCount（单位 升；官方账单按 吨 显示，1000 升 = 1 吨）
#   · 单价：同一份流水里的 rate（元/吨），也就是云端下发 downData 里的费率
#   · 官方应扣 = 用量(升) × 单价(元/吨) / 1000
# 本程序用「零用量流水」结算，实际扣费恒为 0，所以省下的钱 = 官方应扣。
# 全程只读设备与本地记录：不会下单、不会上传真实用量、不会产生任何扣费。
STATS_FILE = os.path.join(APP_DIR, "usage_stats.json")
_LEDGER = {"sessions": [], "loaded": False}


def load_ledger():
    if _LEDGER["loaded"]:
        return _LEDGER
    try:
        with open(STATS_FILE, encoding="utf-8") as f:
            d = json.load(f)
        if isinstance(d, dict) and isinstance(d.get("sessions"), list):
            _LEDGER["sessions"] = d["sessions"][-2000:]
    except Exception:
        pass
    _LEDGER["loaded"] = True
    return _LEDGER


def save_ledger():
    try:
        with open(STATS_FILE, "w", encoding="utf-8") as f:
            json.dump({"sessions": _LEDGER["sessions"][-2000:]}, f,
                      ensure_ascii=False, indent=2)
    except Exception:
        pass


def add_session(rec):
    load_ledger()
    _LEDGER["sessions"].append(rec)
    _LEDGER["sessions"] = _LEDGER["sessions"][-2000:]
    save_ledger()


def official_yuan(use_l, rate_per_ton):
    """官方按用量计的金额（元）= 用量(升) × 单价(元/吨) ÷ 1000"""
    try:
        return round(float(use_l) * float(rate_per_ton) / 1000.0, 4)
    except Exception:
        return 0.0


# 官方起步价（元/次）：实测「花洒不开水、开了立马关」也是固定扣 0.025
# （设备流水 计数=2、云端下单返回 rate=25 元/吨、minChargeUnit=10）
MIN_CHARGE_YUAN = 0.025


def official_charge(use_l, rate_per_ton):
    """官方实际会扣的钱 = max(按用量计费, 起步价 0.025)"""
    return round(max(official_yuan(use_l, rate_per_ton), MIN_CHARGE_YUAN), 4)


# 设备流水里的「计数」→ 计费用量(升)
# 首选口径（只用两个实测计数，不做任何猜测）：
#   本次用量 = 设备流水计数（结算时读到） − 云端下单时的 useCount
#   实测无水流一次开/关：设备计数 2、云端 useCount 1 → 增量 1 → 1 升 → 25 元/吨 = 0.025 元 ✓
# 拿不到云端 useCount 时的退路：按系数折算（这个 0.5 是按上面那组数据反推的）
COUNT_TO_LITER = 0.5


def _record_session():
    """关水成功后记一笔：真实用量来自设备流水，单价取同一份流水里的费率。

    ⚠️ 只做本地记录与计算：不上报真实用量、不下单，不产生任何扣费。
    """
    try:
        count = LAST_FLOW.get("use")
        rate = LAST_FLOW.get("rate")
        if count is None:
            print("[*] 账本：本次没取到设备流水计数，这笔不记账")
            return
        if not rate:
            rate = int(wv.CFG.get("rate") or 0) or 25      # 兜底：配置/默认 25 元每吨
        cloud_count = LAST_ORDER.get("useCount")           # 云端下单时它自己的计数
        if isinstance(cloud_count, int) and 0 <= cloud_count <= count:
            units = count - cloud_count                    # 实测增量 = 本次用量（升）
            basis = "设备计数增量（设备 %d − 云端 %d）" % (count, cloud_count)
        else:
            units = round(count * COUNT_TO_LITER, 3)
            basis = "按 %.1f 升/计数 折算" % COUNT_TO_LITER
        use_l = round(max(0.0, float(units)), 3)
        end = time.time()
        start = WATER.get("start_ts") or end
        by_use = official_yuan(use_l, rate)                # 按用量算出来的钱
        charge = official_charge(use_l, rate)              # 官方实际会扣（含起步价）
        rec = {"ts": end, "start": start, "seconds": int(max(0, end - start)),
               "count": count,                    # 设备原始计数（实测值）
               "cloud_use_count": cloud_count,    # 云端下单返回的计数（实测值）
               "liters": use_l,                   # 本次用量（升）
               "basis": basis,                    # 用量是怎么算出来的（写清楚，便于核对）
               "rate_per_ton": int(rate),         # 官方单价（元/吨）
               "by_use_yuan": by_use,             # 按用量计费
               "min_charge": MIN_CHARGE_YUAN,     # 官方起步价
               "official_yuan": charge,           # 官方实际会扣
               "paid_yuan": 0.0,                  # 零扣费结算：实际扣费恒为 0
               "saved_yuan": charge}
        add_session(rec)
        report_usage(force=True)          # 每关一次水就顺手把账本报给服务器做统计
        print(f"[*] 账本已记一笔：设备计数 {rec['count']} − 云端 {cloud_count} = "
              f"{rec['liters']} 升 · {rec['seconds']} 秒 · 单价 {rec['rate_per_ton']} 元/吨 · "
              f"官方会扣 ¥{rec['official_yuan']:.3f} · 实扣 ¥0.00"
              f"（省 ¥{rec['saved_yuan']:.3f}）")
    except Exception as e:
        print(f"[!] 账本记账失败（不影响使用）：{type(e).__name__}: {e}")


def stats_snapshot():
    """省钱账本汇总：累计 / 本次 / 日均·周均·月均·年均（不足周期的不给数）"""
    load_ledger()
    ss = _LEDGER["sessions"]
    now = time.time()
    first_ts = ss[0]["ts"] if ss else 0
    span_days = (now - first_ts) / 86400.0 if ss else 0.0
    total_saved = round(sum(float(x.get("saved_yuan") or 0) for x in ss), 4)
    total_liters = int(sum(int(x.get("liters") or 0) for x in ss))
    total_seconds = int(sum(int(x.get("seconds") or 0) for x in ss))
    last = ss[-1] if ss else None

    # 只有「数据真的覆盖了一个周期」才给均值，避免刚用两天就算月均
    periods = [("day", "日均", 1), ("week", "周均", 7),
               ("month", "月均", 30), ("year", "年均", 365)]
    avgs = {}
    for key, name, days in periods:
        if not ss:
            avgs[key] = {"name": name, "ok": False, "need_days": days,
                         "have_days": round(span_days, 1), "value": None}
            continue
        if total_liters <= 0:
            avgs[key] = {"name": name, "ok": False, "need_days": days,
                         "have_days": round(span_days, 1), "value": None,
                         "reason": "还没有真实用量（放水时长太短或没取到流水）"}
            continue
        # 按「完整周期数」平均：不足一个周期不给
        n = int(span_days // days)
        avgs[key] = {"name": name, "ok": n >= 1,
                     "need_days": days, "have_days": round(span_days, 1),
                     "value": round(total_saved / n, 4) if n >= 1 else None}
    return {"ok": True, "count": len(ss), "total_saved": total_saved,
            "total_liters": total_liters, "total_seconds": total_seconds,
            "first_ts": first_ts, "span_days": round(span_days, 2),
            "last": last, "avgs": avgs, "sessions": ss[-30:],
            # 标定信息：界面要如实说明这些数字是怎么来的
            "calib": {"count_to_liter": COUNT_TO_LITER,
                      "source": "设备流水计数字段",
                      "cloud": dict(LAST_ORDER) if LAST_ORDER else None}}


def load_prefs():
    try:
        with open(PREFS_FILE, encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except Exception:
        return {}


def save_prefs(d):
    try:
        with open(PREFS_FILE, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def load_alias():
    try:
        with open(ALIAS_FILE, encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except Exception:
        return {}


def save_alias(mac, name):
    """给设备设置/清除本地别名（按 MAC 存，存在 device_alias.json）"""
    d = load_alias()
    mac_u = (mac or "").upper()
    if name:
        d[mac_u] = name
    else:
        d.pop(mac_u, None)
    try:
        with open(ALIAS_FILE, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=2)
    except Exception:
        pass
    return d.get(mac_u, "")


def _mac_candidates(mac):
    """云端查询用的 MAC 候选列表。

    关键：BLE 随机静态地址的最高 2 位被强制置 1，所以**广播地址 ≠ 设备 SN**。
    实测：广播地址 AA:BB:CC:DD:EE:FF → 云端查不到；清掉高 2 位得
    00:BB:CC:DD:EE:FF → 就能查到完整档案（含房间号）。
    因此按「帧内 MAC → 广播地址 → 清高2位 → 首字节00」依次尝试。
    """
    out = []
    m = (mac or "").upper().replace("-", ":").strip()
    if not m:
        return out
    frame = (wv.CFG.get("frame_mac") or "").upper()
    if frame:
        out.append(frame)
    parts = m.split(":")
    if len(parts) == 6:
        try:
            b0 = int(parts[0], 16)
            cands = ["%02X" % (b0 & 0x3F), "00", "C0", "02"]
            for c in cands:
                cand = ":".join([c] + parts[1:])
                if cand not in out and cand != m:
                    out.append(cand)
        except ValueError:
            pass
    if m not in out:
        out.insert(0, m)
    return out


# ================= 设备地址保存策略 =================
# v4.8 起：本机保存设备地址（mac/name），启动时可直接直连（免扫描，秒级）。
# 历史策略曾「不落盘 MAC」（每次启动重新扫描找表），实测水表广播极稀疏
# （空闲时 45 秒才 1 条），扫描找表动辄十几秒，与「3 秒连上」矛盾，
# 故按用户决策改回本机保存。发布包本来就不含 config.json，不受影响。
_DEVICE_KEYS = ("mac", "name", "frame_mac")


def _scrub_device_keys():
    """（v4.8 起不再调用）把 config.json 里的蓝牙地址相关字段删掉"""
    try:
        path = wv.config_path()
        if not os.path.exists(path):
            return
        with open(path, "r", encoding="utf-8") as f:
            d = json.load(f)
        changed = False
        for k in _DEVICE_KEYS:
            if k in d and d.get(k):
                d[k] = ""
                changed = True
        if changed:
            tmp = path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(d, f, ensure_ascii=False, indent=2)
            os.replace(tmp, path)
    except Exception as e:
        print(f"[!] 清理蓝牙地址字段失败：{e}")


# v4.8：不再包装 save_config —— 设备地址随配置正常落盘


def find_my_meter(w, max_rounds=3, timeout=10):
    """扫几轮，挑出「本账号在云端有档案」的那台水表。

    · 完全不依赖本机保存的地址 —— 谁是「我的表」由账号的云端档案决定
    · 每扫到一批设备就立刻查云端档案，命中立即停（不再干等整轮）
    · 命中即返回 (记录, 档案)；找不到返回 None
    """
    seen = {}
    found = {}
    SCAN["hit"] = False          # 清掉上次扫描留下的"已命中"标记

    def on_chunk(_rnd, s):
        """每 4 秒把新扫到的设备拿去云端比对一次，命中就标记停扫"""
        if found:
            return
        cands = sorted(s.values(),
                       key=lambda v: (0 if _mark_suspected(dict(v), "")["suspected"] else 1,
                                      -v.get("rssi", -999)))
        for c in cands[:8]:
            info = cloud_device_info(c["mac"].upper())
            if info:
                found["c"], found["info"] = c, info
                SCAN["hit"] = True          # 让扫描循环尽快停下
                return

    for rnd in range(1, max_rounds + 1):
        if scan_cancelled() or found:
            break
        print(f"[*] 找水表：第 {rnd}/{max_rounds} 轮扫描（每轮 {timeout} 秒）…")
        try:
            out = w.loop.run_until_complete(
                _scan_raw(rounds=1, timeout=timeout, on_chunk=on_chunk))
        except Exception as e:
            print(f"[!] 扫描失败：{e}")
            return None
        for k, v in (out or {}).items():
            seen[k] = v
        if found:
            break
        print(f"[*] 第 {rnd} 轮没发现属于本账号的水表…")
    if found:
        c, info = found["c"], found["info"]
        nm = info.get("deviceName") or ""
        place = "".join(str(x) for x in (info.get("buildingName"),
                                         info.get("floorName"),
                                         info.get("roomName")) if x)
        print(f"[*] 找到本账号的水表：{c['mac']} {nm}｜{place}")
        return c, info
    return None


# 打包成 exe 后，核心模块的 settle_log 路径会指向解压出来的临时目录 —— 挪到 exe 同目录
try:
    if getattr(sys, "frozen", False):
        wv.SETTLE_LOG = os.path.join(APP_DIR, "settle_log.json")
except Exception:
    pass


def cloud_device_info(mac, force=False, allow_network=True):
    """按 MAC 查云端设备档案（官方接口 device/info/mac + macAddress）

    会自动尝试多个 MAC 变体（广播地址 ↔ 设备 SN 的高 2 位差异）。
    allow_network=False 时只读缓存 —— 供 /api/state 这类高频轮询使用，
    避免网络 I/O 堵住本地 API 线程。
    """
    mac_u = (mac or "").upper()
    if not mac_u:
        return None
    if not force and mac_u in DEV_INFO_CACHE:
        return DEV_INFO_CACHE[mac_u] or None
    if not allow_network:
        return None
    s = SESSION.get("s")
    if not s:
        return None
    info = {}
    for cand in _mac_candidates(mac_u):
        d = wv.api("GET", "/device/info/mac", {
            "projectId": s.get("projectId"), "loginCode": s.get("loginCode"),
            "userId": s.get("userId"), "accountId": s.get("accountId"),
            "telephone": s.get("telephone"),
            "macAddress": cand, "isNew": "1"})
        data = (d or {}).get("data")
        if isinstance(data, list) and data:
            data = data[0]
        if isinstance(data, dict) and data:
            info = data
            print(f"[*] 云端档案命中（查询用 MAC={cand}）")
            break
    if info:
        now = time.time()
        if now - _LAST_INFO_LOG.get(mac_u, 0) > 30:      # 同一台表 30 秒内不重复刷日志
            _LAST_INFO_LOG[mac_u] = now
            name = info.get("deviceName") or info.get("DevName") or ""
            place = "".join(str(x) for x in (info.get("buildingName"), info.get("floorName"),
                                             info.get("roomName")) if x)
            print(f"[*] 设备档案：{name}｜位置 {place}｜SN {info.get('snCode') or ''}")
    DEV_INFO_CACHE[mac_u] = info
    return info or None


def device_display_info(force=False, allow_network=True):
    """当前设备的展示信息：本地别名 > 云端档案名 > 广播名 > MAC

    allow_network=False（默认给 /api/state 用）时只读缓存，不做网络请求。
    """
    mac = (wv.CFG.get("mac") or "").upper()
    alias = load_alias().get(mac, "")
    cloud = cloud_device_info(mac, force=force, allow_network=allow_network) or {}
    cloud_full = cloud.get("deviceName") or cloud.get("DevName") or ""
    place = "".join(str(x) for x in (
        cloud.get("buildingName") or cloud.get("LDName") or "",
        cloud.get("floorName") or cloud.get("LCName") or "",
        cloud.get("roomName") or cloud.get("FJName") or "") if x)
    # 云端长名太啰嗦（热水表25-学校-X12公寓-3层-330房），压成「类型 · 位置」
    kind = cloud.get("smallTypeName") or cloud.get("bigTypeName") or ""
    cloud_short = " · ".join(x for x in (kind, place) if x) if place else cloud_full
    ble_name = wv.CFG.get("name") or ""
    return {"mac": mac, "alias": alias,
            "cloud_name": cloud_short, "cloud_full": cloud_full,
            "place": place, "ble_name": ble_name,
            "frame_mac": (wv.CFG.get("frame_mac") or ""),
            "sn": cloud.get("snCode") or "",
            "room": cloud.get("roomName") or "",
            "building": cloud.get("buildingName") or "",
            "floor": cloud.get("floorName") or "",
            "display": alias or cloud_short or ble_name or mac,
            "has_cloud": bool(cloud)}


def refresh_balance(s):
    """从钱包接口刷新余额（返回「元」字符串；失败返回 None）

    钱包接口返回的是已经格式化的元字符串（如 "15.275"），
    比登录会话里的 accountRealMoney（单位厘）更实时准确。
    """
    if not s:
        return None
    try:
        d = wv.api("GET", "/account/wallet", {
            "loginCode": s.get("loginCode"), "userId": s.get("userId"),
            "accountId": s.get("accountId"), "projectId": s.get("projectId"),
            "telephone": s.get("telephone"),
            "phoneSystem": "ios", "version": "6.5.26",
            "telPhone": s.get("telephone")})
    except Exception:
        return None
    if not d or d.get("errorCode") != 0:
        return None
    w = d.get("data") or {}
    for k in ("accountRealMoneyStr", "money", "accountRealMoney"):
        v = w.get(k)
        if v is None or v == "":
            continue
        return fmt_money(v) if isinstance(v, (int, float)) else str(v)
    return None


# ================= 日志采集（供前端轮询） =================
LOGS = deque(maxlen=4000)
LOG_N = [0]                 # 累计行号（deque 溢出时仍递增）
_log_buf = [""]
ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


def _classify(line):
    if "✗" in line or "失败" in line:
        return "err"
    if "✓" in line or "[OK]" in line or "成功" in line or "完成" in line or "✅" in line:
        return "ok"
    if "[!]" in line or "警告" in line or "提示" in line or "超时" in line:
        return "warn"
    if "★" in line or "[*]" in line:
        return "info"
    return ""


# 技术细节行（简洁模式下隐藏）：原始帧、随机数、校验等
TECH_MARKERS = ("[TX]", "[RX]", "raw=", "dataArea=", "CS=", "funCode",
                "downData=", "randomNumber=", "commMode=", "deviceId=",
                "tacTimes", "MAC=", "扩展段", "old=", "0xFF", "特征 0x",
                "write-without-response", "value_handle", "uuid",
                "本地 API:", "窗口启动中", "WebView2", "查询用 MAC", "自报 MAC")

# 简洁模式（不勾「详细」）里不出现的行：纯实现细节，人话模式下没必要看
SIMPLE_HIDE = [
    r"^账户：|^数据库",
    r"前缀",
    r"本轮也没有 .* 的设备",
    r"扫描到 \d+ 个设备",
    r"搜索到 \d+ 个蓝牙设备",
    r"已订阅上报通道|写特征",
    r"^\s*\[[+-]\]\s*(已订阅|写特征|读特征|通知)",
    r"加密|解密密钥|会话密钥|签名|tac|TAC",
    r"^\s*\[[*!+]\]\s*$",
    # 开阀/结算过程中的实现细节：云端查不到就退回帧内信息、清账码、下发 downData、
    # 设备型号与流水长度、上次清账方式……这些都属于「详细」里看的东西
    r"改用 BLE 帧内设备信息",
    r"clearCode=",
    r"clData",
    r"BLE 下发 \d+",
    r"设备档案：型号 ",
    r"上次清账方式「",
    r"downData",
]


def _friendly_archive_name(full):
    """热水表25-淮安信息职业技术学院-X12公寓-3层-330房 → 热水表25 · X12公寓3层330房"""
    parts = [p.strip() for p in str(full).split("-") if p.strip()]
    if len(parts) >= 4:
        return parts[0] + " · " + "".join(parts[-3:])
    return str(full).strip()

# 关键动作 → 人话文案（简洁模式显示；详细模式仍可看原文）
FRIENDLY_MAP = [
    # ── 控制台「菜单 [x] / 按 [x]」口径 → Web 界面按钮口径 ──
    (r"未获取到短信验证码|请用菜单 \[5\] 账号管理做验证码登录|请用菜单 \[5\] 做验证码登录",
     "未登录：请点「重登」或「新增」用短信验证码登录"),
    (r"已保存的会话【已失效】", "账号会话已过期，请点「重登」"),
    (r"请用菜单 \[5\] 切换到项目 (\d+) 的账号", r"请切换到项目 \1 对应的账号"),
    (r"未选择设备，请先用菜单 \[4\]|请先执行菜单 \[4\]|未选择设备（本程序不内置默认设备）",
     "未选择设备：请点「选择设备」"),
    (r"设备未连接：先按 \[4\] 选设备，或按 \[1\] 触发连接|设备未连接：请用 \[6\] 切到蓝牙方式",
     "设备未连接：请先点「选择设备」"),
    (r"互联网方式同样需要设备 MAC|扫描需靠近设备", "请靠近水表后再点「选择设备」"),
    (r"请用菜单 \[6\]|请先按 \[6\] 切到蓝牙方式|帧格式探测只在蓝牙方式下可用",
     "请先用「切换控制方式」切到蓝牙方式"),
    (r"\[4\] 搜索蓝牙设备", "搜索蓝牙设备"),
    (r"菜单 \[C\]", "关水 · 零扣费结算"),
    (r"菜单 \[D\]", "补清账"),
    (r"菜单 \[E\]", "上传诊断"),
    (r"菜单 \[9\]|手动指令台", "手动指令"),
    (r"python _cleanup_state\.py|跑一次 `python .*`", "可在详细模式下查看处理建议"),
    # ── 业务流程 → 人话 ──
    (r"\[1\] 开阀（蓝牙方式）", "开始开阀（蓝牙）"),
    (r"rateOrder 失败", "云端下单失败"),
    (r"云端 BLE 下单|rateOrder（预扣", "向云端下单（预扣 5 元）…"),
    (r"拿到 downData", "云端已返回费率数据"),
    (r"设备回 80 —— 费率已接受|费率已接受", "设备已接受费率"),
    (r"onCharge=01|热水阀开启|放水中", "设备开始放水 ✅"),
    (r"设备状态: .*onCharge=00", "设备当前空闲（没有在计费）"),
    (r"\[TX\] 发送 0x23", "· 查询设备状态"),
    (r"\[TX\] 发送 0x21", "· 下发费率"),
    (r"\[TX\] 发送 0x22", "· 结束用水（关阀）"),
    (r"\[TX\] 发送 0x85", "· 读取用水流水"),
    (r"\[TX\] 发送 0x86", "· 清除设备账务"),
    (r"\[TX\] 发送 0x17|\[TX\] 发送 0x31", "· 下发费率（离线方式）"),
    (r"22 结束回帧", "已发送『结束用水』指令"),
    (r"85 采集成功", "已读取设备用水流水"),
    (r"云端上传账单|upload/bluetooth/data", "正在上传账单…"),
    (r"账单解密", "账单已确认（云端计费完成）"),
    (r"86 清账成功", "设备账务已清除"),
    (r"结算完成，设备已回空闲", "结算完成，设备已恢复空闲 ✅"),
    (r"零扣费|zero_usage", "零扣费模式：上报 0 用量，计费 0 元"),
    (r"正在扫描附近蓝牙设备", "正在扫描附近蓝牙设备（约 15 秒）…"),
    (r"识别到 \d+/\d+ 个疑似水表", "已识别到疑似水表"),
    (r"水表已连接（(.+?)第 (\d+) 次尝试）", r"设备已连接（\1第 \2 次就成功）"),
    (r"\[\+\]\s*已连接\s*$", "蓝牙链路已建立"),
    (r"已连接|连接成功", "设备已连接"),
    # 单轮没扫到 ≠ 设备不在——后面还会按前缀/名称继续找，措辞不能吓人
    (r"本轮没有 .* —— 设备当前不在广播范围", "本轮没扫到它（水表广播很稀疏），换个方式继续找…"),
    # 单次尝试失败只是重试过程，别写成终局失败
    (r"连接失败：TimeoutError:", "这次连接超时（水表广播稀疏时常见），1.5 秒后换一次重试…"),
    (r"连接失败：([A-Za-z_]+):", r"这次没连上（\1），1.5 秒后换一次重试…"),
    (r"连续 3 次连接失败", "连续 3 次都没连上"),
    (r"已保存的会话【(.+?)】", lambda m: "已保存的账号：会话" + ("有效 ✅" if m.group(1) == "有效" else "已失效，请点「重登」")),
    (r"会话有效[（(](\d{11})", r"账号 \1 会话有效"),
    (r"会话有效[，,]\s*余额 ([\d.]+)", r"账号会话有效，余额 \1 元"),
    (r"会话有效", "账号会话有效"),
    (r"登录成功", "登录成功"),
    (r"上传候选 randomNumber", "准备上传账单"),
    (r"设备处于「结算中」", "设备有待结算流水，正在自动补结算…"),
    (r"费率 rate=\d+ 厘（云端 ([\d.]+) 元/升）", r"费率：\1 元/升"),
    (r"费率 rate=(\d+) 厘", r"费率：\1 厘/升"),
    (r"结算\s+在线结算：22 结束", "· 开始关阀结算（结束 → 采集 → 清账 → 确认）"),
    (r"结算\s+离线结算（不上报云端）", "· 开始离线结算（不上报云端）"),
    # ── 简洁模式专用人话（放在最后，避免抢掉上面更精确的文案） ──
    (r"设备档案[:：]\s*([^-|｜\s]+(?:-[^-|｜\s]+){2,})",
     lambda m: "设备档案：" + _friendly_archive_name(m.group(1))),
    (r"自动连接水表", "正在自动连接水表…"),
    (r"自动连接失败", "这次没连上水表（信号弱 / 不在附近 / 被手机占用），可点「搜寻水表」重试"),
    (r"已连接，无需重连", "水表本来就是连接状态"),
    (r"正在连接 \(第 \d+/\d+ 次\)", "正在连接水表…"),
    (r"找到设备", "已经发现水表 ✅"),
    (r"实际连接设备 MAC 与配置不同|已自动切换并保存目标设备", "水表广播地址变了，已自动改用新地址"),
    (r"搜寻水表[：:]\s*第 (\d+)/(\d+) 轮", r"正在搜寻水表…（第 \1/\2 轮）"),
    (r"第 \d+/\d+ 轮扫描（\d+ 秒）", "· 扫描中…"),
    (r"听到水表[：:]\s*\S+\s+\S+\s+(-?\d+)\s*dBm", r"已经听到水表 ✅（\1 dBm）"),
    (r"搜寻结束[：:]\s*始终没听到水表", "一直没搜寻到水表（信号太弱 / 被手机占用 / 蓝牙干扰）"),
    (r"扫描完成：共 (\d+) 台设备，疑似水表 (\d+) 台，已保存的水表出现",
     r"扫描完成：发现 \1 台设备（疑似水表 \2 台），你的水表已出现 ✅"),
    (r"扫描完成：共 (\d+) 台设备，疑似水表 (\d+) 台，已保存的水表本轮未出现",
     r"扫描完成：发现 \1 台设备（疑似水表 \2 台），你的水表没出现"),
    (r"扫描完成：共 (\d+) 台设备", r"扫描完成：发现 \1 台设备"),
    # 界面点了什么（排查「点了没反应」用）
    (r"收到指令 /api/water/open", "· 收到「开水」指令"),
    (r"收到指令 /api/water/close", "· 收到「关水 · 零扣费结算」指令"),
    (r"收到指令 /api/status/query", "· 收到「查询状态」指令"),
    (r"收到指令 /api/account/info", "· 收到「账户信息」指令"),
    (r"收到指令 /api/bill", "· 收到「本月账单」指令"),
    (r"收到指令 /api/device/scan", "· 收到「选择设备（扫描）」指令"),
    (r"收到指令 /api/device/hunt", "· 收到「选择设备（搜寻）」指令"),
    (r"收到指令 /api/device/cancel", "· 收到「取消扫描」指令"),
    (r"已请求取消扫描", "正在停止扫描…"),
    (r"扫描已取消（取消前已发现 (\d+) 台设备）", r"扫描已取消（取消前已发现 \1 台设备）"),
    (r"搜寻已取消（取消前已发现 (\d+) 台设备）", r"搜寻已取消（取消前已发现 \1 台设备）"),
    (r"^扫描已取消$|^搜寻已取消$", "扫描已取消"),
    (r"收到指令 /api/device/select", "· 收到「连接该设备」指令"),
    (r"收到指令 /api/device/rename", "· 收到「重命名」指令"),
    (r"收到指令 /api/account/switch", "· 收到「切换账号」指令"),
    (r"收到指令 /api/account/delete", "· 收到「删除账号」指令"),
    (r"收到指令 /api/(\S+)", r"· 收到指令（\1）"),
]


def _map_line(line):
    """把控制台原文映射成 (显示文案, 简洁模式是否显示)

    · 命中关键动作 → 返回人话文案，简洁模式显示（详细模式看原文）
    · 纯技术细节（帧/随机数/校验/扫描过程） → 简洁模式隐藏，勾「详细」才看得到
    · 其他 → 原样显示
    """
    for pat, friendly in FRIENDLY_MAP:
        m = re.search(pat, line)
        if m:
            if callable(friendly):            # 需要现场加工的（如设备档案名）
                try:
                    return friendly(m), 1
                except Exception:
                    return line, 1
            # 支持 \1 反向引用（如“切换到项目 1036 对应的账号”）
            try:
                return m.expand(friendly), 1
            except re.error:
                return friendly, 1
    # 兜底：把残留的“菜单 [x] / 按 [x]”口径清理成界面说法
    line2 = re.sub(r"请用菜单 \[[0-9A-Z]\]\s*", "请在界面中", line)
    line2 = re.sub(r"先按 \[[0-9A-Z]\]\s*", "请先", line2)
    line2 = re.sub(r"\[[0-9A-Z]\]\s*(搜索|账号管理|查询状态|补清账)", r"\1", line2)
    # 简洁模式不该看见控制台那套 [*]/[+]/[!] 前缀（详细模式仍保留原文）
    line2 = re.sub(r"^\s*\[(?:\*|\+|!|OK|完成|提示)\]\s*", "", line2).strip()
    # 简洁模式：扫描过程 / 特征值 / 密钥之类的实现细节一律不出现（详细模式照旧）
    if any(re.search(p, line) for p in SIMPLE_HIDE):
        return line2, 0
    if any(mk in line for mk in TECH_MARKERS):
        return line2, 0
    return line2, 1


class LogWriter:
    def __init__(self, real):
        self.real = real

    def write(self, s):
        # Flask/click 有时写入 bytes，统一转成字符串（UTF-8，坏字节替换）
        if isinstance(s, (bytes, bytearray)):
            s = bytes(s).decode("utf-8", "replace")
        try:
            if self.real:
                self.real.write(s)
        except Exception:
            pass
        if not s:
            return
        text = _log_buf[0] + s
        while "\n" in text:
            line, text = text.split("\n", 1)
            line = ANSI_RE.sub("", line)          # 去掉 ANSI 颜色码
            line = line.rstrip()
            if not line or "Werkzeug" in line or "flask.cli" in line:
                continue
            friendly, show_simple = _map_line(line)
            _observe(line)                        # 旁听「找到设备/已连接」，刷新最近扫到时间
            # 连续重复行去重（轮询/未登录提示会反复打印）
            if LOGS and LOGS[-1][2] == line and LOGS[-1][1] == friendly:
                continue
            # 同一句人话 20 秒内不重复刷屏（简洁模式）；详细模式照样保留原文
            if show_simple:
                key = friendly.strip()
                now = time.time()
                if len(key) >= 4 and now - _LAST_SIMPLE.get(key, 0) < 20:
                    show_simple = 0
                _LAST_SIMPLE[key] = now
            LOGS.append([_classify(line), friendly, line, show_simple])
            LOG_N[0] += 1
        _log_buf[0] = text
        if len(_log_buf[0]) > 4000:          # 防止无换行输出撑爆缓冲
            chunk = _log_buf[0][:4000]
            LOGS.append(["", chunk, chunk, 0])
            LOG_N[0] += 1
            _log_buf[0] = _log_buf[0][4000:]

    def flush(self):
        try:
            if self.real:
                self.real.flush()
        except Exception:
            pass


# ================= 状态快照 =================
def fmt_money(v):
    """云端余额单位是「厘」，显示成元并保留 3 位小数。

    例：15275 → "15.275"；"6.225"（钱包接口已是元的字符串）原样返回。
    """
    if v is None:
        return None
    if isinstance(v, str):
        s = v.strip()
        if not s:
            return None
        if "." in s:            # 已经是元的字符串（钱包接口 accountRealMoneyStr）
            return s
        try:
            v = float(s)
        except ValueError:
            return s
    if isinstance(v, (int, float)):
        return f"{v / 1000:.3f}"
    return None


def snapshot():
    s = SESSION["s"]
    accounts = []
    for phone, acct, cur in wv.list_accounts():
        accounts.append({"phone": phone,
                         "project": (acct or {}).get("projectId"),
                         "current": cur})
    conn = bool(WORKER.valve and WORKER.valve.client
                and WORKER.valve.client.is_connected)
    bal = None
    if s:
        # 优先用钱包接口刷新的实时余额；否则用会话里的值（单位厘 → 元）
        bal = SESSION.get("bal") or fmt_money(s.get("accountRealMoney"))
    return {
        "ok": True,
        "account": wv.CFG.get("active_phone") or "",
        "project": s.get("projectId") if s else None,
        "balance": bal,
        "device": wv.CFG.get("name") or "",
        "device_mac": wv.CFG.get("mac") or "",
        "device_last_seen": _history_of(wv.CFG.get("mac")),
        # 注意：轮询接口里不做网络查询，只读缓存（避免堵住本地 API）
        "device_info": device_display_info(allow_network=False),
        "mode": wv.CFG.get("mode"),
        "connected": conn,
        "busy": BUSY["label"] is not None,
        "busy_label": BUSY["label"],
        "last_action": dict(ACT_RESULT),          # 最近一次操作的真实结果
        "water_running": WATER["running"],        # 出水状态：True 正在放水 / False 空闲 / None 未知
        "water_age": (int(time.time() - WATER["ts"]) if WATER["ts"] else None),
        "license": 9999,
        # 本地自用版：不再回填任何旧秘钥
        "last_key": "",
        "accounts": accounts,
        "need_activation": False,
        "lic_checking": False,
        "lic_ready": True,
        "server": "",
    }


def run_async(label, fn, wait=False, timeout=180):
    """把任务丢给工作线程；wait=True 时等结果（用于需要即时反馈的操作）

    用计数而不是单个布尔：启动时会连着提交好几个任务（自动连接 / 设备信息 / 会话恢复），
    只要有一个先结束就把忙状态清掉，界面就会在「自动连接水表」还在重试时提前弹出
    「结束·还没连上」——所以必须等队列彻底跑空才算不忙。
    """
    BUSY["label"] = label
    BUSY["n"] = BUSY.get("n", 0) + 1

    def wrapped(w):
        try:
            return fn(w)
        finally:
            BUSY["n"] = max(0, BUSY.get("n", 1) - 1)
            if BUSY["n"] == 0:          # 队列里还有任务时，不要提前把忙状态清掉
                BUSY["label"] = None

    fut = WORKER.submit(wrapped)
    if wait:
        try:
            return fut.result(timeout), None
        except Exception as e:
            return None, str(e)
    return None, None


def action_dup(key, busy_labels, window=3.0):
    """判断是否为重复指令（用于让前端可安全重试）

    BLE 操作会让工作线程长时间占用 GIL，本地 HTTP 线程偶发响应超时，
    前端把请求掐掉时**指令其实可能已入队**。所以：
      · 同一动作在 window 秒内重复到达 → 视为重复，直接返回已受理
      · 工作线程正在执行同类操作 → 也视为重复
    这样前端可以放心重试，不会重复开阀/关阀。
    """
    now = time.time()
    if BUSY["label"] and BUSY["label"] in busy_labels:
        return True
    if now - ACTIONS.get(key, 0) < window:
        return True
    ACTIONS[key] = now
    return False


# ================= Flask API =================
app = Flask(__name__, static_folder=WEB_DIR, static_url_path="")


@app.after_request
def _no_cache_html(resp):
    """界面文件禁止缓存。

    WebView2 会把 index.html 缓存住，重启程序后仍加载旧页面 —— 改了界面却「没生效」，
    排查半天。这里直接告诉浏览器别存。
    """
    try:
        ct = (resp.headers.get("Content-Type") or "").lower()
        if "html" in ct or "javascript" in ct or "css" in ct:
            resp.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
            resp.headers["Pragma"] = "no-cache"
            resp.headers["Expires"] = "0"
    except Exception:
        pass
    return resp


@app.before_request
def _log_action_request():
    """把「界面点了什么」记进日志（只记动作类 POST，不含轮询）。

    这条是给排查用的：以后再有「点了没反应」，一眼就能看出指令到底有没有送到后端。
    """
    try:
        if request.method == "POST" and request.path.startswith("/api/"):
            if request.path in ("/api/prefs",):
                return
            print(f"[*] 收到指令 {request.path}")
    except Exception:
        pass


@app.route("/")
def index():
    return send_from_directory(WEB_DIR, "index.html")


@app.route("/api/prefs", methods=["GET", "POST"])
def api_prefs():
    """界面偏好：主题（深/浅）与主题色。存 ui_prefs.json，重启后仍然记得。"""
    if request.method == "POST":
        d = request.get_json(silent=True) or {}
        p = load_prefs()
        if d.get("theme") in ("light", "dark"):
            p["theme"] = d["theme"]
        a = d.get("accent")
        if isinstance(a, str) and a.startswith("#") and len(a) in (4, 7):
            p["accent"] = a
        if isinstance(d.get("onboarded"), bool):     # 新手教程是否已看过
            p["onboarded"] = d["onboarded"]
        if isinstance(d.get("seen_changelog"), str):  # 更新日志看过哪个版本（看过一次就不再弹）
            p["seen_changelog"] = d["seen_changelog"][:16]
        save_prefs(p)
        return jsonify({"ok": True, "prefs": p})
    return jsonify({"ok": True, "prefs": load_prefs()})


@app.route("/api/stats")
def api_stats():
    """省钱账本：累计 / 本次 / 日均·周均·月均·年均（周期没满就不给数）"""
    return jsonify(stats_snapshot())


@app.route("/api/state")
def api_state():
    return jsonify(snapshot())


@app.route("/api/copy", methods=["POST"])
def api_copy():
    data = request.get_json(silent=True) or {}
    return jsonify({"ok": bool(copy_text(data.get("text", "")))})


@app.route("/api/logs")
def api_logs():
    try:
        since = int(request.args.get("since", 0))
    except ValueError:
        since = 0
    total = LOG_N[0]
    start = max(0, total - len(LOGS))
    since = max(since, start)
    lines = list(LOGS)[since - start:]
    return jsonify({"lines": lines, "next": total})


LAST_KEY_FILE = os.path.join(APP_DIR, "last_key.json")


def save_last_key(key, server=""):
    """记下最近一次激活成功的秘钥（license.json 被删/过期后，激活页还能预填）"""
    try:
        with open(LAST_KEY_FILE, "w", encoding="utf-8") as f:
            json.dump({"key": key, "server": server, "ts": time.time()},
                      f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def load_last_key():
    try:
        with open(LAST_KEY_FILE, encoding="utf-8") as f:
            return (json.load(f) or {}).get("key") or ""
    except Exception:
        return ""


@app.route("/api/activate", methods=["POST"])
def api_activate():
    """本地自用版：不需要秘钥，前端万一调用也直接返回成功。"""
    LIC_DAYS[0] = 9999
    LIC_READY[0] = True
    return jsonify({"ok": True, "days": 9999})


@app.route("/api/login/send_code", methods=["POST"])
def api_send_code():
    d = request.get_json(silent=True) or {}
    phone = d.get("phone", "").strip()
    if len(phone) != 11 or not phone.isdigit():
        return jsonify({"ok": False, "msg": "手机号需为 11 位数字"})
    ok, err = run_async("发送验证码", lambda w: wv.send_sms_code(phone), wait=True, timeout=30)
    return jsonify({"ok": bool(ok), "msg": err or ("验证码已发送" if ok else "发送失败")})


@app.route("/api/login/verify", methods=["POST"])
def api_verify():
    d = request.get_json(silent=True) or {}
    phone, code = d.get("phone", "").strip(), d.get("code", "").strip()

    def job(w):
        s = wv.login_by_code(phone, code)
        if s:
            wv.set_active_session(s, phone=phone)
        return s
    s, err = run_async("验证码登录", job, wait=True, timeout=60)
    if s:
        SESSION["s"] = s
        SESSION["bal"] = refresh_balance(s)      # 刷新余额（元）
        cloud_device_info((wv.CFG.get("mac") or "").upper(),
                          force=True)            # 登录后补查设备档案
        return jsonify({"ok": True, "project": s.get("projectId"),
                        "balance": SESSION["bal"]})
    return jsonify({"ok": False, "msg": err or "登录失败（验证码错误或已过期）"})


@app.route("/api/account/switch", methods=["POST"])
def api_switch():
    phone = (request.get_json(silent=True) or {}).get("phone", "")

    def job(w):
        wv.set_active_account(phone)
        s = wv.login_session()
        SESSION["s"] = s
        SESSION["bal"] = refresh_balance(s) if s else None
        if s:
            cloud_device_info((wv.CFG.get("mac") or "").upper(), force=True)
        return bool(s)
    ok, err = run_async(f"切换账号 {phone}", job, wait=True, timeout=60)
    return jsonify({"ok": bool(ok), "msg": err or ("" if ok else "该账号会话已失效，请重登"),
                    "balance": SESSION["bal"]})


@app.route("/api/account/delete", methods=["POST"])
def api_delete():
    phone = (request.get_json(silent=True) or {}).get("phone", "")

    def job(w):
        wv.drop_account(phone)
        SESSION["s"] = None
        return True
    run_async(f"删除账号 {phone}", job, wait=True, timeout=20)
    return jsonify({"ok": True})


@app.route("/api/water/open", methods=["POST"])
def api_water_open():
    if not SESSION["s"]:
        return jsonify({"ok": False, "msg": "未登录，请先登录账号"})
    if action_dup("water_open", ("开水",)):
        return jsonify({"ok": True, "already": True, "msg": "开水指令已在执行"})

    def job(w):
        s = SESSION["s"]
        valve = w.get_valve()
        # ★ 防呆：开阀前先落盘（opening），并确认看门狗在跑。
        #   只要这一步没过，宁可不开，也不留下“人走了阀门还开着”的可能。
        if SAFETY:
            ok_safe, why = SAFETY.before_open(
                wv.CFG.get("mac", ""), wv.CFG.get("last_dev_id"), "online")
            if not ok_safe:
                set_result("开水", False, why)
                return False
        if wv.CFG["mode"] == "tcp":
            ok = bool(wv.tcp_open(s))
            set_result("开水", ok, "已开始放水（联网方式）" if ok else "云端下单失败，没开水")
            if SAFETY:
                if ok:
                    SAFETY.mark_open()
                else:
                    SAFETY.mark_open_failed("云端下单失败")
            return ok
        if not ensure_connected(w, valve, rounds=2, label="开水"):
            rssi = (_history_of(wv.CFG.get("mac")) or {}).get("rssi")
            set_result("开水", False,
                       "水表没连上（信号 %s dBm，太弱或不在广播范围），本次没开水；"
                       "请靠近水表后重试" % (rssi if rssi is not None else "未知"))
            if SAFETY:
                # 连接都没建立，肯定没下发开阀命令：直接标记关闭，避免卡住下一次开阀
                SAFETY.mark_closed("水表没连上，未下发开阀")
            return False
        ok = bool(w.loop.run_until_complete(wv.ble_open(valve, s)))
        set_result("开水", ok, "已开始放水 ✅" if ok else "设备未接受指令，没开水")
        if ok:
            set_water(True, "开水")
            WATER["start_ts"] = time.time()          # 记账用：本次放水开始时间
            if SAFETY:
                SAFETY.mark_open(WATER["start_ts"])
        elif SAFETY:
            # 设备可能已执行 21 但没来得及确认：按“可能开着”处理，立刻补关
            SAFETY.mark_open_failed("开阀未确认")
        return ok
    run_async("开水", job)
    return jsonify({"ok": True})


@app.route("/api/water/close", methods=["POST"])
def api_water_close():
    if not SESSION["s"]:
        return jsonify({"ok": False, "msg": "未登录，请先登录账号"})
    if action_dup("water_close", ("关水 · 零扣费结算",)):
        return jsonify({"ok": True, "already": True, "msg": "关水指令已在执行"})

    def job(w):
        s = SESSION["s"]
        valve = w.get_valve()
        if SAFETY:
            SAFETY.mark_closing("用户关水")
        if wv.CFG["mode"] == "tcp":
            using = wv.tcp_using(s)
            order_no = (using or {}).get("orderNo") if using else ""
            ok = bool(wv.tcp_close(s, order_no)) if order_no else False
            set_result("关水 · 零扣费结算", ok, "已关水并结算" if ok else "没有进行中的订单，未关水")
            if SAFETY and ok:
                SAFETY.mark_closed("用户关水")
            return ok
        if not ensure_connected(w, valve, rounds=2, label="关水"):
            rssi = (_history_of(wv.CFG.get("mac")) or {}).get("rssi")
            set_result("关水 · 零扣费结算", False,
                       "水表没连上（信号 %s dBm），本次没关水；请靠近水表后重试"
                       % (rssi if rssi is not None else "未知"))
            return False
        offline = (wv.CFG.get("last_open_mode") == "offline")
        LAST_FLOW.update(use=None, rate=None, ts=0.0)   # 只认这次结算采到的流水
        ok = w.loop.run_until_complete(
            wv.ble_close(valve, s, offline=offline, zero_usage=True))
        if ok:
            wv.CFG["last_open_mode"] = ""
            wv.save_config()
            _record_session()
        set_result("关水 · 零扣费结算", ok,
                   "已关水并完成零扣费结算 ✅" if ok else "设备未响应，未确认关水")
        if ok:
            set_water(False, "关水")
            if SAFETY:
                SAFETY.mark_closed("用户关水")
        return ok
    run_async("关水 · 零扣费结算", job)
    return jsonify({"ok": True})


@app.route("/api/status/query", methods=["POST"])
def api_query():
    def job(w):
        valve = w.get_valve()
        if wv.CFG["mode"] == "tcp":
            wv.tcp_query(SESSION["s"])
            set_result("查询状态", True, "已读取设备状态")
            return True
        if not (valve.client and valve.client.is_connected) and wv.CFG.get("mac"):
            ensure_connected(w, valve, rounds=2, label="查询前连接")
        if valve.client and valve.client.is_connected:
            st = w.loop.run_until_complete(valve.query_status())
            # 记下设备自报的 MAC（云端档案要用它查，广播地址查不到）
            try:
                fm = wv.fmt_mac_hex((st or {}).get("mac") or "")
                if fm and fm != (wv.CFG.get("frame_mac") or ""):
                    wv.CFG["frame_mac"] = fm
                    wv.save_config()
                    DEV_INFO_CACHE.pop((wv.CFG.get("mac") or "").upper(), None)
                    print(f"[*] 设备自报 MAC = {fm}（用它在云端查档案）")
                # 在工作线程里把档案查好并缓存，界面轮询直接读缓存
                cloud_device_info(wv.CFG.get("mac") or "")
            except Exception:
                pass
        else:
            rssi = (_history_of(wv.CFG.get("mac")) or {}).get("rssi")
            set_result("查询状态", False,
                       "没连上水表（信号 %s dBm），读不到设备状态"
                       % (rssi if rssi is not None else "未知"))
            return False
        set_result("查询状态", True, "已读取设备状态")
        return True
    run_async("查询状态", job)
    return jsonify({"ok": True})


SCAN = {"state": "idle", "devices": [], "err": None, "ts": 0, "rounds": 0, "cancel": False}
SCAN_HISTORY = {}   # mac -> {name, mac, rssi, ts}：水表时有时无，记住最近一次
HUNT = {"running": False, "rounds": 0, "heard": 0, "started": 0}


def scan_cancelled():
    """界面点了「取消」→ 让正在跑的扫描尽快退出（最多等一个扫描小块，约 4 秒）"""
    return bool(SCAN.get("cancel"))


def _remember(devices):
    """把本轮扫到的设备记进历史（保留最强信号与最近时间）"""
    now = time.time()
    for d in devices:
        h = SCAN_HISTORY.get(d["mac"])
        if h is None or d["rssi"] > h["rssi"]:
            SCAN_HISTORY[d["mac"]] = {"name": d["name"], "mac": d["mac"],
                                      "rssi": d["rssi"], "ts": now}
        else:
            h["ts"] = now


def _history_of(mac):
    """已保存设备最近一次被扫到的信息（含距今秒数）"""
    if not mac:
        return None
    h = SCAN_HISTORY.get(mac.upper())
    if not h:
        return None
    rssi = h.get("rssi")
    return {"name": h["name"], "mac": h["mac"],
            "rssi": rssi if isinstance(rssi, (int, float)) and rssi > -999 else None,
            "age": int(time.time() - h["ts"])}


# 连接流程（核心代码里的 find_device/connect）也要算「扫到过」，
# 否则界面会显示「本次运行还没扫到过」，而日志里刚刚才「找到设备 → 已连接」——自相矛盾。
_RX_FOUND = re.compile(r"(?:找到设备|按前缀发现)[^\(]*\(?\s*"
                       r"((?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2})")
_RX_RSSI = re.compile(r"RSSI\s*=\s*(-?\d+)|信号偏弱[（(](-?\d+)\s*dBm")
_RX_ONCHARGE = re.compile(r"onCharge=(\d{2})")
# 85 采集流水摘要：… 次数=25 预扣=5000 费率=25/25 MAC=…
_RX_FLOW = re.compile(r"次数=(\d+).*?费率=(\d+)/(\d+)")
_OBS = {"rssi": None, "ts": 0.0}      # 最近一次量到的信号强度（给下一条「找到设备」用）
LAST_FLOW = {"use": None, "rate": None, "ts": 0.0}   # 最近一次采集到的真实用量/单价
LAST_ORDER = {}          # 云端下单接口返回的原始计费参数（真实数据，只读记录）
ORDER_INFO_FILE = os.path.join(APP_DIR, "cloud_order_info.json")


def _install_rate_order_probe():
    """包一层 wv.ble_rate_order：把云端返回的**真实计费参数**记下来。

    开阀本来就会调这个接口（预扣 5 元、结算时退回），这里只是把响应里的字段
    打印并落盘，不额外发起任何请求、不产生任何额外扣费。
    """
    orig = getattr(wv, "ble_rate_order", None)
    if orig is None or getattr(orig, "_wv_probed", False):
        return

    def probed(*a, **kw):
        down, info = orig(*a, **kw)
        try:
            if isinstance(info, dict) and info:
                LAST_ORDER.clear()
                LAST_ORDER.update(info)
                keys = ("rate", "useCount", "minChargeUnit", "minMoney", "minTime",
                        "perMoney", "realMoney", "givenMoney", "chargeMethod",
                        "autoDisConTime", "consumeDate", "downData")
                shown = " ".join(f"{k}={info.get(k)}" for k in keys if k in info)
                print(f"[*] 云端计费参数（下单接口原样返回）：{shown or '（无已知字段）'}")
                with open(ORDER_INFO_FILE, "w", encoding="utf-8") as f:
                    json.dump(info, f, ensure_ascii=False, indent=2)
        except Exception:
            pass
        return down, info

    probed._wv_probed = True
    wv.ble_rate_order = probed


def _note_seen(mac, name=None, rssi=None):
    """记下「刚刚看到过这台表」——不依赖是否走的是界面扫描"""
    if not mac:
        return
    key = mac.upper().replace("-", ":")
    now = time.time()
    h = SCAN_HISTORY.get(key)
    if h is None:
        SCAN_HISTORY[key] = {"name": name or key, "mac": key,
                             "rssi": rssi if isinstance(rssi, (int, float)) else -999,
                             "ts": now}
        return
    h["ts"] = now
    if name:
        h["name"] = name
    if isinstance(rssi, (int, float)) and rssi > h.get("rssi", -999):
        h["rssi"] = rssi


def _observe(line):
    """从核心的输出里旁听「找到设备 / 已连接 / 是否在放水」（不改核心代码）"""
    try:
        now = time.time()
        # 设备回帧里的 onCharge：01=正在计费放水，00=空闲。这是最权威的出水状态。
        oc = _RX_ONCHARGE.search(line)
        if oc:
            set_water(oc.group(1) == "01", "设备回帧")
        fl = _RX_FLOW.search(line)          # 85 采集流水里的真实用量与单价
        if fl:
            # 费率字段可能是“历史/累计 1925 / 当前 25”，取后一个当前费率
            LAST_FLOW.update(use=int(fl.group(1)), rate=int(fl.group(3) or fl.group(2)),
                             ts=time.time())
        m = _RX_RSSI.search(line)
        if m:
            v = m.group(1) or m.group(2)
            if v:
                _OBS["rssi"] = int(v)
                _OBS["ts"] = now
        # 刚量到的信号强度（20 秒内）跟着「找到设备/已连接」一起记下
        rssi = _OBS["rssi"] if (_OBS["rssi"] is not None
                                and now - _OBS["ts"] <= 20) else None
        m = _RX_FOUND.search(line)
        if m:
            _note_seen(m.group(1), None, rssi)
        elif "水表已连接" in line or "连接成功" in line:
            _note_seen(wv.CFG.get("mac"), wv.CFG.get("name"), rssi)
    except Exception:
        pass


def _attach_cloud_names(devices, limit=8):
    """给疑似设备补上云端档案名（房间/楼层），便于按房间确认是哪台表"""
    for d in devices:
        if d.get("cloud_name"):
            continue
        if not (d.get("suspected") or d.get("is_saved")):
            continue
        if limit <= 0:
            break
        limit -= 1
        info = cloud_device_info(d["mac"])
        if not info:
            # 缓存里可能留着一个空档（上次查的时候会话还没就绪），强制重查一次
            info = cloud_device_info(d["mac"], force=True)
        if not info:
            continue
        place = "".join(str(x) for x in (info.get("buildingName") or info.get("LDName") or "",
                                         info.get("floorName") or info.get("LCName") or "",
                                         info.get("roomName") or info.get("FJName") or "") if x)
        kind = info.get("smallTypeName") or info.get("bigTypeName") or ""
        d["cloud_name"] = " · ".join(x for x in (kind, place) if x)
        d["room"] = info.get("roomName") or ""
        sn = (info.get("snCode") or "").replace(":", "").upper()
        if sn and sn == (d.get("mac") or "").replace(":", "").upper():
            d["is_saved"] = True
    return devices


def _absorb(items, seen, rnd):
    """把一次扫描结果并进累积表（按 MAC 合并，保留最强信号与出现轮次）"""
    for d, adv in items:
        addr = (getattr(d, "address", "") or "").upper()
        if not addr:
            continue
        wv._cache_put(d, adv)      # 同步进核心设备缓存：选完设备连接时免重扫
        name = getattr(d, "name", None) or "(未命名)"
        rssi = getattr(adv, "rssi", None) if adv is not None else getattr(d, "rssi", None)
        if not isinstance(rssi, (int, float)):
            rssi = -999
        svcs = [str(s).lower() for s in (getattr(adv, "service_uuids", None) or [])] \
            if adv is not None else []
        cur = seen.get(addr)
        if cur is None:
            seen[addr] = {"name": name, "mac": addr, "rssi": int(rssi),
                          "svcs": svcs, "rounds": [rnd]}
        else:
            if rnd not in cur["rounds"]:
                cur["rounds"].append(rnd)
            if rssi > cur["rssi"]:
                cur["rssi"] = int(rssi)
                if name and name != "(未命名)":
                    cur["name"] = name
            if svcs and not cur["svcs"]:
                cur["svcs"] = svcs


async def _discover(total, seen, rnd, chunk=4.0, on_chunk=None):
    """扫 total 秒：单个常驻扫描器连续收广播（不再每 4 秒重启一次 discover()）。

    旧版每 chunk 秒起停一次 BleakScanner —— Windows 上每次启停都有 1 秒级开销，
    且重启瞬间会漏广播；常驻扫描器边收边攒，结果更多、更快。
    每 chunk 秒回调 on_chunk 把中间结果推给界面；「取消」最多等 0.25 秒生效。

    返回 True=扫完，False=被取消 / 已听到目标。
    """
    from bleak import BleakScanner

    saved = (wv.CFG.get("mac") or "").upper()

    def _cb(d, adv):
        _absorb([(d, adv)], seen, rnd)
        # 已保存的水表一出现立即标记（不等 4 秒回调）：搜寻/扫描马上收工
        if saved and (getattr(d, "address", "") or "").upper() == saved:
            SCAN["hit"] = True

    scanner = BleakScanner(detection_callback=_cb)
    await scanner.start()
    try:
        left = float(total)
        since = 0.0
        while left > 1e-3:
            if scan_cancelled() or SCAN.get("hit"):     # 用户取消 / 已经听到目标水表
                return False
            step = min(0.25, left)
            await asyncio.sleep(step)
            left -= step
            since += step
            if on_chunk and since >= chunk:
                since = 0.0
                try:
                    on_chunk(rnd, seen)
                except Exception:
                    pass
        if on_chunk:
            try:
                on_chunk(rnd, seen)
            except Exception:
                pass
        return True
    finally:
        try:
            await scanner.stop()
        except Exception:
            pass


async def _scan_raw(rounds=3, timeout=15, progress=None, on_chunk=None):
    """多轮 BLE 扫描：按 MAC 合并，保留最强信号与出现轮次，并标注疑似水表。

    progress(rnd, total, seen) 每轮开始时回调 —— 让界面能显示
    「第几轮 / 共几轮」和「已发现几台」，而不是等全部扫完才更新。
    每轮一个常驻扫描器连续收；on_chunk 每 4 秒推一次中间结果，
    取消 / 听到目标 0.25 秒内生效。
    """
    seen = {}
    for rnd in range(1, max(1, rounds) + 1):
        if scan_cancelled():
            print("[*] 扫描已取消")
            return seen
        if progress:
            try:
                progress(rnd, rounds, seen)
            except Exception:
                pass
        print(f"[*] 第 {rnd}/{rounds} 轮扫描（{timeout} 秒）…")
        if not await _discover(timeout, seen, rnd, on_chunk=on_chunk):
            if SCAN.get("hit"):
                print("[*] 已听到目标水表，扫描提前结束")
            else:
                print("[*] 扫描已取消")
            return seen
    return seen


def _mark_suspected(dev, saved_mac=""):
    """标注疑似水表：名称关键字 ∪ 厂商 OUI 前缀 ∪ 广播服务特征（与 v4.6 规则一致）"""
    up = (dev["name"] or "").upper()
    addr = (dev["mac"] or "").upper()
    reasons = []
    if wv.DEFAULT_NAME.upper() in up:
        reasons.append("名称匹配")
    elif any(k in up for k in wv.DEVICE_KEYWORDS):
        reasons.append("名称关键字")
    if any(addr.startswith(p) for p in wv._oui_prefixes()):
        reasons.append("厂商前缀")
    if any(h in s for s in dev["svcs"] for h in wv.ADV_SVC_HINT):
        reasons.append("广播特征")
    dev["suspected"] = bool(reasons)
    dev["reason"] = "/".join(reasons)
    dev["is_saved"] = bool(saved_mac) and addr == saved_mac.upper()
    return dev


@app.route("/api/device/scan", methods=["POST"])
def api_scan():
    """开始扫描（立即返回，结果由 /api/device/scan_result 轮询）

    扫描要 15~60 秒：不能让 HTTP 干等 —— 浏览器对单个请求约 30 秒就断，
    会误报“服务无响应”。改成「下达指令 + 轮询结果」。
    """
    if SCAN["state"] == "running":
        return jsonify({"ok": True, "running": True})
    d = request.get_json(silent=True) or {}
    try:
        rounds = max(1, min(6, int(d.get("rounds", 2))))
    except (TypeError, ValueError):
        rounds = 2
    SCAN.update(state="running", devices=[], err=None, ts=time.time(),
                rounds=rounds, round=1, cancel=False, hit=False)

    def job(w):
        saved = (wv.CFG.get("mac") or "").upper()

        def on_round(rnd, total, seen):
            """每轮开始就把进度推给界面（当前轮次 + 已发现设备）"""
            SCAN["round"] = rnd
            if seen:
                devs = [_mark_suspected(dict(v), saved) for v in seen.values()]
                devs.sort(key=lambda x: (0 if x["suspected"] else 1, -x["rssi"]))
                SCAN.update(devices=devs, ts=time.time())

        try:
            seen = w.loop.run_until_complete(
                _scan_raw(rounds=rounds, progress=on_round))
            out = [_mark_suspected(v, saved) for v in seen.values()]
            # 疑似优先，其次信号强
            out.sort(key=lambda x: (0 if x["suspected"] else 1, -x["rssi"]))
            _remember(out)
            if SCAN.get("cancel"):                 # 被取消：不弹选择列表，只把结果丢掉
                print(f"[*] 扫描已取消（取消前已发现 {len(out)} 台设备）")
                SCAN.update(state="idle", devices=[], err=None, ts=time.time())
                return None
            _attach_cloud_names(out)
            hit = [x for x in out if x["is_saved"]]
            print(f"[*] 扫描完成：共 {len(out)} 台设备，"
                  f"疑似水表 {sum(1 for x in out if x['suspected'])} 台"
                  + (f"，已保存的水表出现（{hit[0]['name']} {hit[0]['rssi']}dBm）"
                     if hit else "，已保存的水表本轮未出现"))
            SCAN.update(state="done", devices=out, round=rounds, ts=time.time())
            return out
        except Exception as e:
            SCAN.update(state="error", err=f"{type(e).__name__}: {e}",
                        ts=time.time())
            return None

    run_async("扫描蓝牙设备", job)
    return jsonify({"ok": True, "running": True})


@app.route("/api/device/cancel", methods=["POST"])
def api_scan_cancel():
    """取消扫描/搜寻。

    原来界面只是把弹窗关掉，后台那一轮还在跑（15 秒后还在扫、还在刷日志）。
    现在这里置一个取消标记，扫描循环每 4 秒检查一次，最多 4 秒就真的停下。
    """
    SCAN["cancel"] = True
    SCAN["hit"] = False
    HUNT["running"] = False
    SCAN.update(state="idle", devices=[], err=None, ts=time.time(), round=0)
    print("[*] 已请求取消扫描（最多 4 秒内停止）")
    return jsonify({"ok": True})


@app.route("/api/device/hunt", methods=["POST"])
def api_hunt():
    """专门搜寻已保存的水表：连续多轮，直到听到或超时

    水表广播稀疏（-84dBm 级），单次 15 秒窗口经常整轮扫空；
    连续多轮累积命中率高得多。结果同样由 /api/device/scan_result 轮询。
    """
    if SCAN["state"] == "running" or HUNT["running"]:
        return jsonify({"ok": True, "running": True})
    d = request.get_json(silent=True) or {}
    try:
        rounds = max(2, min(12, int(d.get("rounds", 6))))
    except (TypeError, ValueError):
        rounds = 6
    HUNT.update(running=True, rounds=rounds, heard=0, started=time.time())
    SCAN.update(state="running", devices=[], err=None, ts=time.time(),
                rounds=rounds, round=0, cancel=False, hit=False)

    def job(w):
        saved = (wv.CFG.get("mac") or "").upper()
        best = None
        try:
            for rnd in range(1, rounds + 1):
                if scan_cancelled() or SCAN.get("hit"):    # 取消 / 已听到目标表
                    break
                SCAN["round"] = rnd
                print(f"[*] 搜寻水表：第 {rnd}/{rounds} 轮（每轮 12 秒，听到就停）…")

                def pub(seen2, _saved=saved):
                    """每扫完 4 秒就把已发现的设备推进 SCAN：
                    界面可以立刻把列表弹出来，不用干等整轮 12 秒。"""
                    devs2 = [_mark_suspected(dict(v), _saved) for v in seen2.values()]
                    devs2.sort(key=lambda x: (0 if x["suspected"] else 1, -x["rssi"]))
                    SCAN.update(devices=devs2, ts=time.time())
                    if any(x["is_saved"] for x in devs2):
                        SCAN["hit"] = True          # 已经听到目标水表 → 本轮到此为止
                        _remember(devs2)

                seen = w.loop.run_until_complete(
                    _scan_raw(rounds=1, timeout=12, progress=None,
                              on_chunk=lambda _r, s2: pub(s2)))
                devs = [_mark_suspected(v, saved) for v in seen.values()]
                devs.sort(key=lambda x: (0 if x["suspected"] else 1, -x["rssi"]))
                _remember(devs)
                SCAN.update(devices=devs, ts=time.time())
                hit = [x for x in devs if x["is_saved"]] or \
                      [x for x in devs if x["suspected"]]
                if hit:
                    HUNT["heard"] += 1
                    best = hit[0]
                    _attach_cloud_names([best], limit=1)   # 立刻补云端档案名（缓存后不再联网）
                    print(f"[*] 听到水表：{best['name']} {best['mac']} "
                          f"{best['rssi']}dBm（第 {rnd} 轮）")
                    if best["is_saved"]:
                        break
            if scan_cancelled():                     # 取消：不弹选择列表
                print(f"[*] 搜寻已取消（取消前已发现 {len(SCAN['devices'] or [])} 台设备）")
                SCAN.update(state="idle", devices=[], err=None, ts=time.time())
                return None
            out = SCAN["devices"] or []
            if best:
                out = [best] + [x for x in out if x["mac"] != best["mac"]]
            # 搜寻路径也要补档案名，否则列表里只显示广播名 KLCXKJ-Water
            _attach_cloud_names(out, limit=3)
            SCAN.update(state="done", devices=out, ts=time.time())
            if not best:
                print("[!] 搜寻结束：始终没听到水表（信号太弱 / 被占用 / 蓝牙干扰）")
            return out
        except Exception as e:
            SCAN.update(state="error", err=f"{type(e).__name__}: {e}",
                        ts=time.time())
            return None
        finally:
            HUNT["running"] = False

    run_async("搜寻水表", job)
    return jsonify({"ok": True, "running": True})


@app.route("/api/device/rename", methods=["POST"])
def api_rename():
    """给当前设备起个本地名字（显示优先级：本地名字 > 云端档案名 > 广播名 > MAC）"""
    d = request.get_json(silent=True) or {}
    name = (d.get("name") or "").strip()
    mac = (wv.CFG.get("mac") or "").upper()
    if not mac:
        return jsonify({"ok": False, "msg": "尚未选择设备"})
    if len(name) > 24:
        return jsonify({"ok": False, "msg": "名字最多 24 个字符"})
    saved = save_alias(mac, name)
    print(f"[*] 设备名字已{'更新为「' + saved + '」' if saved else '清除'}")
    return jsonify({"ok": True, "name": saved})


@app.route("/api/device/cloud_name", methods=["POST"])
def api_cloud_name():
    """重新从云端拉一次设备档案（官方 App 同款接口）"""
    mac = (wv.CFG.get("mac") or "").upper()
    info = cloud_device_info(mac, force=True)
    if not info:
        return jsonify({"ok": False, "msg": "云端没有这台设备（该 MAC 未建档）"})
    return jsonify({"ok": True, "info": info})


@app.route("/api/device/scan_result")
def api_scan_result():
    saved = (wv.CFG.get("mac") or "").upper()
    devs = SCAN["devices"]
    return jsonify({"ok": True, "state": SCAN["state"],
                    "rounds": SCAN.get("rounds"),
                    "round": SCAN.get("round"),
                    "devices": devs, "msg": SCAN["err"],
                    "saved_mac": saved,
                    "saved_found": any(d.get("is_saved") for d in devs),
                    "saved_history": _history_of(saved),
                    "suspected_count": sum(1 for d in devs if d.get("suspected")),
                    "hunt": {"running": HUNT["running"], "rounds": HUNT["rounds"],
                             "round": SCAN.get("round") or 1,
                             "heard": HUNT["heard"],
                             "elapsed": int(time.time() - HUNT["started"])
                                        if HUNT["started"] else 0}})


@app.route("/api/device/select", methods=["POST"])
def api_select():
    """选择并连接设备（同样立即返回，连接结果看状态里的「蓝牙已连接」）"""
    d = request.get_json(silent=True) or {}
    mac, name = d.get("mac", ""), d.get("name", "")

    def job(w):
        wv.CFG["mac"] = mac
        wv.CFG["name"] = name if name and name != "(未命名)" else mac
        wv.save_config()
        DEV_INFO_CACHE.pop((mac or "").upper(), None)      # 换设备了，旧档案缓存先清掉
        valve = w.get_valve()
        ok = w.loop.run_until_complete(valve.connect())
        if ok:
            # 连上后读一次状态：设备自报的 MAC 才是云端档案能查到的那个
            try:
                st = w.loop.run_until_complete(valve.query_status())
                fm = wv.fmt_mac_hex((st or {}).get("mac") or "")
                if fm:
                    wv.CFG["frame_mac"] = fm
                    DEV_INFO_CACHE.pop((mac or "").upper(), None)
                    print(f"[*] 设备自报 MAC = {fm}（用它查云端档案）")
            except Exception:
                pass
        # ★ 不管连没连上，都去云端查一次档案 —— 这样界面立刻就能显示「宿舍 / 房间名」
        #   （以前只有连上后手动点「查询状态」或重启才会查，所以第一次选完设备名字一直是广播名）
        info = cloud_device_info((wv.CFG.get("mac") or "").upper(), force=True)
        if info:
            print(f"[*] 设备档案：{info.get('deviceName')}")
            did = info.get("deviceId")
            if did:
                # 记住这块表的 deviceId：开阀前会拿它和设备自报值比对，
                # 防止连到同型号的隔壁表还照开不误
                wv.CFG["last_dev_id"] = int(did)
                wv.save_config()
                print(f"[*] 已认定 deviceId={did}"
                      f"（{info.get('roomName') or info.get('buildingName') or ''}）")
        else:
            print("[*] 云端没查到这台表的档案（确认登录的账号是不是这个项目的？）")
        return ok

    run_async(f"连接设备 {mac}", job)
    return jsonify({"ok": True})


@app.route("/api/account/info", methods=["POST"])
def api_info():
    if not SESSION["s"]:
        return jsonify({"ok": False, "msg": "未登录"})

    def job(w):
        wv.account_info(SESSION["s"])                      # 打印姓名/学号/余额明细
        SESSION["bal"] = refresh_balance(SESSION["s"]) or SESSION.get("bal")
        if SESSION.get("bal"):
            print(f"[*] 当前余额 {SESSION['bal']} 元")
        return True
    run_async("账户信息", job)
    return jsonify({"ok": True})


@app.route("/api/bill", methods=["POST"])
def api_bill():
    if not SESSION["s"]:
        return jsonify({"ok": False, "msg": "未登录"})
    run_async("本月账单", lambda w: wv.bill_query(SESSION["s"]))
    return jsonify({"ok": True})


# ================= 入口 =================
_WIN = {"w": None}


def minimize_window():
    if _WIN["w"]:
        _WIN["w"].minimize()


def close_window():
    """关窗：先零用量关阀；关不掉就**不退出**，让看门狗继续重试。

    原版关不掉也 os._exit，人一走阀门还开着，最后按真实流量扣费。
    现在宁可窗口关不掉，也不让程序在阀门未收尾时消失。
    """
    try:
        need = bool(WATER["running"]) or bool(wv.pending_read())
        if SAFETY and SAFETY.needs_close_now():
            need = True
        if need:
            print("[!] 关窗前检测到阀门/结算未收尾，正在零用量关阀（最多 120 秒）...")
            ok = False
            if SAFETY:
                ok = SAFETY.request_close_sync("关窗", timeout=120)
            else:
                def job(w):
                    valve = w.get_valve()
                    try:
                        if ensure_connected(w, valve, rounds=2, label="关窗结算"):
                            return w.loop.run_until_complete(
                                wv.ble_close(valve, SESSION.get("s"), zero_usage=True))
                    except Exception as e:           # noqa: BLE001
                        print(f"[!] 关窗结算失败：{type(e).__name__}: {e}")
                    return False

                res, _ = run_async("关窗结算", job, wait=True, timeout=60)
                ok = bool(res)
            if not ok:
                msg = ("水阀未确认关闭！\n\n"
                       "程序不会退出，安全看门狗会继续自动重试关阀。\n"
                       "请靠近水表，或稍等它连上后自动零用量关阀。")
                print("[!] " + msg.replace("\n", " "))
                try:
                    import ctypes
                    ctypes.windll.user32.MessageBoxW(0, msg, "安全保护", 0x10)
                except Exception:
                    pass
                return                          # 关键：不销毁窗口、不退出
            set_water(False, "关窗")
    except Exception as e:                       # noqa: BLE001
        print(f"[!] 关窗检查异常：{e}")
        return                                  # 异常也不退出，交给看门狗
    try:
        if _WIN["w"]:
            _WIN["w"].destroy()
    finally:
        os._exit(0)


def copy_text(text):
    """写系统剪贴板（Win32 直写，不依赖 pyperclip；WebView2 剪贴板 API 常受限）。"""
    s = str(text or "")
    if not s:
        return False
    try:
        import ctypes
        from ctypes import wintypes
        k = ctypes.windll.kernel32
        u = ctypes.windll.user32
        CF_UNICODETEXT = 13
        GMEM_MOVEABLE = 0x0002
        k.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
        k.GlobalAlloc.restype = wintypes.HGLOBAL
        k.GlobalLock.argtypes = [wintypes.HGLOBAL]
        k.GlobalLock.restype = wintypes.LPVOID
        k.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
        k.GlobalUnlock.restype = wintypes.BOOL
        k.GlobalFree.argtypes = [wintypes.HGLOBAL]
        u.OpenClipboard.argtypes = [wintypes.HWND]
        u.OpenClipboard.restype = wintypes.BOOL
        u.EmptyClipboard.restype = wintypes.BOOL
        u.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
        u.SetClipboardData.restype = wintypes.HANDLE
        u.CloseClipboard.restype = wintypes.BOOL
        opened = False
        for _ in range(6):
            if u.OpenClipboard(None):
                opened = True
                break
            time.sleep(0.05)
        if not opened:
            return False
        try:
            if not u.EmptyClipboard():
                return False
            data = s.encode("utf-16-le") + b"\x00\x00"
            h = k.GlobalAlloc(GMEM_MOVEABLE, len(data))
            if not h:
                return False
            p = k.GlobalLock(h)
            if not p:
                k.GlobalFree(h)
                return False
            try:
                ctypes.memmove(p, data, len(data))
            finally:
                k.GlobalUnlock(h)
            if not u.SetClipboardData(CF_UNICODETEXT, h):
                k.GlobalFree(h)
                return False
            return True
        finally:
            u.CloseClipboard()
    except Exception:
        return False


def start_flask():
    """启动本地 API 服务

    用 waitress（生产级 WSGI，纯 Python）替代 Flask 自带的开发服务器：
    多线程、连接队列更大，长时间操作（扫描 15s、开关水十几秒）期间
    轮询 /api/state、/api/logs 不会被拖垮或偶发拒连。
    """
    logging.getLogger("werkzeug").disabled = True
    try:
        from waitress import serve
        serve(app, host="127.0.0.1", port=API_PORT, threads=8,
              connection_limit=128, channel_timeout=300, _quiet=True)
        return
    except ImportError:
        pass
    try:
        import flask.cli
        flask.cli.show_server_banner = lambda *a, **k: None
    except Exception:
        pass
    app.run(host="127.0.0.1", port=API_PORT, debug=False,
            use_reloader=False, threaded=True)


def ensure_connected(w, valve, rounds=2, label="连接水表"):
    """确保设备已连接：未连接则多轮尝试（水表广播稀疏，单次扫常整轮扫空）"""
    if valve.client and valve.client.is_connected:
        return True
    for rnd in range(1, max(1, rounds) + 1):
        try:
            if w.loop.run_until_complete(valve.connect()):
                print(f"[OK] 水表已连接（{label} 第 {rnd} 次尝试）")
                _note_seen(wv.CFG.get("mac"), wv.CFG.get("name"))   # 连上也算「扫到过」
                return True
        except Exception as e:
            print(f"[!] 连接异常：{type(e).__name__}: {e}")
        if rnd < rounds:
            print(f"[*] 第 {rnd} 次未连上，2 秒后重试 …")
            time.sleep(2)
    return False


def safety_log(msg):
    """安全层日志：同时写文件，界面/控制台没了也能事后查。"""
    line = time.strftime("%Y-%m-%d %H:%M:%S") + "  " + str(msg)
    try:
        path = os.path.join(wv._data_dir(), "valve_safety.log")
        try:
            if os.path.getsize(path) > 2 * 1024 * 1024:
                os.replace(path, path + ".1")
        except Exception:
            pass
        with open(path, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass
    try:
        print(msg)
    except Exception:
        pass


def safety_close_sync(reason="安全关阀", timeout=90):
    """安全层专用：同步零用量关阀（关窗/关机/超时/看门狗自愈都走这里）。

    与「用户点关水」共用同一套 ble_close：zero_usage=True 恒定零扣费；
    会话失效时自动降级为离线清账（不上传 → 不会产生账单）。
    """
    def job(w):
        s = SESSION.get("s")
        valve = w.get_valve()
        if wv.CFG.get("mode") == "tcp":
            try:
                using = wv.tcp_using(s) if s else None
                order_no = (using or {}).get("orderNo") if using else ""
                ok = bool(wv.tcp_close(s, order_no)) if (s and order_no) else False
            except Exception as e:  # noqa: BLE001
                print(f"[安全] 联网关阀异常：{type(e).__name__}: {e}")
                ok = False
            if ok:
                set_water(False, reason)
            return ok
        if not ensure_connected(w, valve, rounds=2, label=reason):
            print(f"[安全] {reason}：水表没连上，稍后由看门狗继续重试")
            return False
        # 先确认设备到底开没开：onCharge=00 说明本来就没出水，
        # 不能因为“开阀失败”就把状态永久卡在 closing。
        try:
            st0 = w.loop.run_until_complete(valve.query_status())
            oc0 = (st0 or {}).get("onCharge")
            if oc0 == "00":
                if wv.pending_read():
                    print("[安全] 设备已空闲，但有待办流水 → 补一次零用量清账")
                    ok0 = w.loop.run_until_complete(
                        wv.ble_reclear(valve, s, zero_usage=True))
                    return bool(ok0)
                print("[安全] 设备已空闲（onCharge=00），无需关阀")
                return True
        except Exception as e:  # noqa: BLE001
            print(f"[安全] 关阀前查询失败（继续按关阀处理）：{type(e).__name__}")
        offline = (not s) or (wv.CFG.get("last_open_mode") == "offline")
        LAST_FLOW.update(use=None, rate=None, ts=0.0)
        try:
            ok = w.loop.run_until_complete(
                wv.ble_close(valve, s, offline=offline, zero_usage=True))
        except Exception as e:  # noqa: BLE001
            print(f"[安全] {reason}：关阀异常 {type(e).__name__}: {e}")
            return False
        if ok:
            wv.CFG["last_open_mode"] = ""
            wv.save_config()
            try:
                _record_session()
            except Exception:
                pass
            set_water(False, reason)
            print(f"[安全] {reason}：已零用量关阀并清账")
        else:
            print(f"[安全] {reason}：设备未确认关阀，看门狗会继续重试")
        return bool(ok)

    res, err = run_async(reason, job, wait=True, timeout=timeout)
    if err:
        print(f"[安全] {reason} 任务异常：{err}")
    return bool(res)


def _recover_mac_from_profiles():
    """从设备档案键恢复广播地址（旧版把 mac 字段抹了，但 profiles 键里留有

    key 形如 mac<12位hex>（device_profile_key 生成，hex 就是广播地址）。
    找到最近的 mac* 键就恢复，避免升级后第一次还要扫描找表。
    """
    profs = wv.CFG.get("profiles") or {}
    best = None
    for k, v in profs.items():
        if not (isinstance(k, str) and k.startswith("mac") and len(k) == 15):
            continue
        try:
            int(k[3:], 16)
        except ValueError:
            continue
        ts = (v or {}).get("ts") or 0
        if best is None or ts > best[1]:
            best = (k[3:].upper(), ts)
    if not best:
        return ""
    h = best[0]
    return ":".join(h[i:i + 2] for i in range(0, 12, 2))


def self_heal(w, valve):
    """连上设备后的自愈：上次结算没走完 / 设备还挂着流水 → 零用量补清账。

    这是「平时不扣、一扣把前面全扣了」的正面防线：设备端流水只要不清，
    用量就会一直累加；这里在任何人开阀之前先把它按 0 元了结掉。
    设备正在放水（onCharge=01）时不动它，避免打断正在用的水。
    """
    pend = wv.pending_read()
    st = None
    try:
        st = w.loop.run_until_complete(valve.query_status())
    except Exception as e:                       # noqa: BLE001
        print(f"[!] 自愈：查询状态失败（{type(e).__name__}）")
    oc = (st or {}).get("onCharge")
    if oc == "01":
        print("[*] 自愈：设备正在出水，跳过（等关水时再结算）")
        return False
    if not pend and oc == "00":
        return False
    print(f"[!] 自愈：{'发现未完成结算 ' if pend else ''}onCharge={oc}"
          f" → 先做零用量补清账，避免旧用量滚进下一次结算")
    wv.pending_mark(kind="self_heal", zero_usage=True)
    try:
        r = w.loop.run_until_complete(
            wv.ble_reclear(valve, SESSION.get("s"), zero_usage=True))
        print(f"[*] 自愈结果：onCharge={r.get('onCharge')} "
              f"upload={r.get('upload')} clear_by={r.get('clear_by')}")
        if r.get("onCharge") == "00":
            wv.pending_clear()
        return True
    except Exception as e:                       # noqa: BLE001
        print(f"[!] 自愈失败：{type(e).__name__}: {e}")
        return False


def auto_connect():
    """启动时自动连水表（每次打开都会试）

    设备地址已保存在本机（v4.8 起）：直接走系统设备库直连（免扫描，秒级）；
    查不到地址时先从设备档案恢复；仍没有才扫描（用「本账号云端有档案」认表）。
    """
    mac = (wv.CFG.get("mac") or "").upper()
    if not mac:
        recovered = _recover_mac_from_profiles()
        if recovered:
            mac = recovered
            wv.CFG["mac"] = recovered
            if not wv.CFG.get("name"):
                wv.CFG["name"] = wv.DEFAULT_NAME
            print(f"[*] 从设备档案恢复地址：{recovered}")
            wv.save_config()

    def job(w):
        nonlocal mac
        valve = w.get_valve()
        found_info = None
        if not mac:
            if not SESSION.get("s"):
                print("[*] 还没登录账号，也没保存过设备 —— 先跳过（登录后会自动找表）")
                return False
            print("[*] 第一次使用（还没保存设备地址），先扫一下找本账号的水表…")
            hit = find_my_meter(w, max_rounds=3, timeout=10)
            if not hit:
                print("[!] 这次没扫到属于本账号的水表（信号弱 / 不在附近），可点「选择设备」手动选")
                return False
            cand, info = hit
            mac = cand["mac"].upper()
            wv.CFG["mac"] = mac                      # 保存到本机：以后启动直连
            wv.CFG["name"] = cand.get("name") or mac
            wv.save_config()
            found_info = info
            valve = w.get_valve()                    # ★ 重新取一次：valve 的目标设备是从 CFG 拷的
            print(f"[*] 认定本机设备：{mac}（{info.get('deviceName') or ''}）")

        ok = False
        if valve.client and valve.client.is_connected:
            print("[*] 已连接，无需重连")
            ok = True
        else:
            print(f"[*] 自动连接水表 {mac}…")
            ok = ensure_connected(w, valve, rounds=2, label="自动连接")
            if ok:
                try:
                    st = w.loop.run_until_complete(valve.query_status())
                    fm = wv.fmt_mac_hex((st or {}).get("mac") or "")
                    if fm:
                        wv.CFG["frame_mac"] = fm
                        wv.save_config()
                        DEV_INFO_CACHE.pop(mac, None)
                except Exception:
                    pass
            else:
                print("[!] 自动连接失败（水表可能不在附近/被手机占用），可点「搜寻水表」")

            # ★ 启动自愈：上次结算没走完（掉线/被杀）或设备还挂着流水，
            #   先做一次零用量补清账，绝不让旧用量滚到下一次真实结算里。
            if ok:
                self_heal(w, valve)

        # 无论蓝牙连没连上，都去云端查一次档案并缓存（名字/位置来自账号）
        info = found_info or cloud_device_info(mac) or {}
        nm = info.get("deviceName")
        if nm:
            print(f"[OK] 设备档案：{nm}")
        return ok

    run_async("自动连接水表", job)


BILL_SEEN_FILE = os.path.join(APP_DIR, "bill_seen.json")


def check_new_bills(s):
    """启动对账：本月账单里有没有「程序之外」产生的新扣费，直接打到日志。

    本程序只会以零用量上报，所以任何新增的非零消费都不可能是它造成的；
    这里把订单号/用水时间/上传时间列出来，方便一眼判断是手机 App 还是设备自结算。
    第一次运行只建基线，不误报。
    """
    if not s:
        return
    try:
        month = time.strftime("%Y-%m")
        d = wv.api("GET", "/order/query/account/bill/list", {
            "telephone": s["telephone"], "curNum": 1, "billRequestType": "2",
            "month": month, "loginCode": s["loginCode"],
            "projectId": s["projectId"], "userId": s["userId"],
            "accountId": s["accountId"]})
        rows = (d or {}).get("data") or []
        items = [b.get("consumeBillDTO") or {} for b in rows]
        items = [c for c in items if c.get("orderId")]
        ids = [str(c["orderId"]) for c in items]

        seen = {"ids": []}
        if os.path.exists(BILL_SEEN_FILE):
            try:
                with open(BILL_SEEN_FILE, encoding="utf-8") as f:
                    seen = json.load(f) or seen
            except Exception:
                pass
        known = set(seen.get("ids") or [])
        fresh = [c for c in items if str(c["orderId"]) not in known]

        if not known:
            print(f"[*] 账单基线已建立：本月 {len(items)} 笔消费"
                  f"（以后有新增会在这里报警）")
        elif fresh:
            print(f"[!] 账单新增 {len(fresh)} 笔扣费（不是本程序产生的）：")
            total = 0.0
            for c in fresh[:5]:
                try:
                    total += float(c.get("consumeMoney") or 0)
                except ValueError:
                    pass
                print(f"    {c.get('consumeMoney')} 元  用水 {c.get('consumeDate')}  "
                      f"上传 {c.get('uploadDate')}  订单{c.get('orderId')}")
            print(f"[!] 合计 {total:.3f} 元 —— 请勿用手机 App 关水，"
                  f"那条路按真实用量结算，程序拦不住")
        elif known:
            print(f"[*] 账单对账：本月 {len(items)} 笔消费，无新增")

        merged = list(dict.fromkeys((seen.get("ids") or []) + ids))[-300:]
        with open(BILL_SEEN_FILE, "w", encoding="utf-8") as f:
            json.dump({"ids": merged, "ts": time.time()}, f, ensure_ascii=False)
    except Exception as e:                       # noqa: BLE001
        print(f"[!] 账单对账失败：{type(e).__name__}: {e}")


def _link_watchdog():
    """放水期间保持链路：掉线就悄悄重连，保证回来时能零用量关水。"""
    while True:
        time.sleep(15)
        try:
            if not WATER.get("running"):
                continue
            v = WORKER.valve
            if v is None or (v.client and v.client.is_connected):
                continue
            if BUSY.get("label"):
                continue
            print("[*] 放水中链路断开，后台自动重连 ...")
            run_async("保持连接",
                      lambda w: ensure_connected(w, w.get_valve(), rounds=1,
                                                 label="保持连接"))
        except Exception:
            pass


def restore_session():
    """启动时尝试恢复已保存账号的会话（失效则静默等待用户重登）"""
    def job(w):
        s = wv.login_session()
        SESSION["s"] = s
        SESSION["bal"] = refresh_balance(s) if s else None
        if s:
            print(f"[*] 会话有效，余额 {SESSION['bal']} 元")
            cloud_device_info((wv.CFG.get("mac") or "").upper(), force=True)
            check_new_bills(s)          # 对账：有没有程序之外的新扣费
        return bool(s)
    run_async("校验会话", job)


def _api_already_running():
    """本地 API 端口是否已被另一个实例占用（防重复启动）"""
    import socket
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", API_PORT)) == 0


def main():
    # 多个实例同时跑会互相抢蓝牙（实测：三个实例把水表连接全部拖成超时）
    if _api_already_running():
        print(f"[!] 已经有一个蓝牙水库在运行（本地 API 127.0.0.1:{API_PORT}）")
        print("    先关掉它再启动；多个实例同时连同一块水表只会互相打架")
        return

    # BLE 操作（扫描/连接）会让工作线程长时间占用 GIL，导致 HTTP 线程偶尔响应变慢；
    # 缩小线程切换间隔，让本地 API 在蓝牙忙时也能及时响应。
    try:
        sys.setswitchinterval(0.0005)
    except Exception:
        pass

    # 日志重定向（前端轮询 /api/logs）
    sys.stdout = LogWriter(sys.stdout)
    sys.stderr = LogWriter(sys.stderr)

    _T0 = time.time()

    def _mark(tag):
        print(f"[启动计时] {tag}: {int((time.time() - _T0) * 1000)} ms")

    wv.load_config()
    _mark("读配置")

    # ================= 防呆安全层 =================
    # 默认上限 20 分钟 / 预计 3 元；可在 config.json 改 max_open_minutes / max_cost_yuan。
    # 设 WV_SAFETY=0 可整体关闭（不推荐）。
    global SAFETY
    try:
        if "max_open_minutes" not in wv.CFG:
            wv.CFG["max_open_minutes"] = 20
            wv.CFG["max_cost_yuan"] = 3.0
            wv.save_config()
    except Exception:
        pass
    if os.environ.get("WV_SAFETY", "1") != "0":
        try:
            SAFETY = vsafety.Safety(
                wv._data_dir(), safety_close_sync,
                is_open_cb=lambda: bool(WATER.get("running")),
                log=safety_log,
                max_open_min=int(wv.CFG.get("max_open_minutes") or 20),
                max_cost_yuan=float(wv.CFG.get("max_cost_yuan") or 3.0),
                enabled=True,
            )
            SAFETY.start(window_title="蓝牙水库 · 水控")
            SAFETY.recover_if_needed()
            _mark("安全层就绪")
        except Exception as e:                   # noqa: BLE001
            print(f"[!] 安全层启动失败：{type(e).__name__}: {e}")
            SAFETY = None
    else:
        print("[!] WV_SAFETY=0：安全层已关闭（不推荐）")
    _install_rate_order_probe()      # 记录云端返回的真实计费参数（只读，不多花钱）
    # 固定走蓝牙方式：不再提供「切换控制方式」，配置里若残留 tcp 也强制改回
    if wv.CFG.get("mode") != "ble":
        wv.CFG["mode"] = "ble"
        wv.save_config()
        print("[*] 控制方式已固定为蓝牙（BLE）")
    # 许可校验放后台线程：先把窗口开出来（界面自己的开屏动画先跑），
    # 校验期间 /api/state 会带 lic_checking=true，前端等结果再决定要不要弹秘钥页。
    global LIC_CHECKING
    LIC_CHECKING[0] = True

    def _lic_job():
        try:
            allowed = license_boot()
            if allowed:
                print(f"[*] 许可有效，剩余 {LIC_DAYS[0]} 天")
                _mark("许可校验完成")
                # 许可 OK 才恢复会话 + 自动连水表（窗口这时早出来了）
                restore_session()
                time.sleep(0.8)
                auto_connect()
            else:
                print("[!] 未激活 —— 请在界面中输入秘钥激活")
        finally:
            LIC_CHECKING[0] = False
            LIC_READY[0] = True

    threading.Thread(target=_lic_job, daemon=True).start()
    # 放水期间的链路看门狗：掉线自动重连，保证「关水」还能零用量结算
    threading.Thread(target=_link_watchdog, daemon=True).start()
    # 注意：start_flask() 内部是 waitress.serve（阻塞），必须放线程里
    threading.Thread(target=start_flask, daemon=True).start()

    # 等本地 API 真正监听再开窗口：比固定 sleep(0.4) 快且不赌运气
    # （固定等待要么白等、要么在慢机器上窗口打开后接口还没起来）
    def _wait_api(timeout=5.0):
        import socket
        t0 = time.time()
        while time.time() - t0 < timeout:
            try:
                with socket.create_connection(("127.0.0.1", API_PORT), 0.15):
                    return True
            except OSError:
                time.sleep(0.02)
        return False

    if _wait_api():
        _mark("本地 API 就绪")
        print(f"[*] 本地 API: http://127.0.0.1:{API_PORT}")
    else:
        print("[!] 本地 API 5 秒内未就绪，仍尝试打开窗口 …")

    import webview
    window = webview.create_window(
        title="蓝牙水库 · 水控",
        # 带一个每次启动都不同的参数：WebView2 的磁盘缓存会留着旧页面，
        # 改了界面重启却还是老样子（排查过很多次），换个 URL 就一定是新的。
        url=f"http://127.0.0.1:{API_PORT}/?v={int(time.time())}",
        width=980, height=660,
        min_size=(860, 560),
        frameless=True,          # 无边框（自定义标题栏）
        easy_drag=False,         # 关闭“整窗拖拽”：否则会吃掉日志的滚动与文字选择
        on_top=False,
        resizable=True,
        background_color="#08090a",
    )
    _WIN["w"] = window
    _mark("窗口对象已创建")
    # 拖拽只由标题栏的 pywebview-drag-region 元素负责
    window.expose(minimize_window, close_window, copy_text)
    print("[*] 窗口启动中（WebView2）…")
    # WebView2 用持久缓存目录：第二次启动直接复用运行时缓存，明显更快；
    # private_mode=False + storage_path 指向 %LOCALAPPDATA%\蓝牙水库\webview2
    wv_cache = os.path.join(APP_DIR, "webview2")
    try:
        os.makedirs(wv_cache, exist_ok=True)
    except Exception:
        wv_cache = None
    webview.start(debug=False, private_mode=(wv_cache is None),
                  storage_path=wv_cache)


if __name__ == "__main__":
    main()
