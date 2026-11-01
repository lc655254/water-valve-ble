# -*- coding: utf-8 -*-
"""看门狗：主程序退出、心跳超时或重启后，负责零用量关阀。

空闲时不加载 bleak / 水表核心，只有真正需要收尾才 import。
"""
from __future__ import annotations

import argparse
import asyncio
import ctypes
import json
import os
import sys
import time
from ctypes import wintypes

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import valve_safety as vs  # noqa: E402

_MUTEX = None
_CONSOLE = None
_WV = None


def _log_writer(d):
    path = vs.guard_log_path(d)

    def log(msg):
        try:
            if os.path.getsize(path) > 2 * 1024 * 1024:
                os.replace(path, path + ".1")
        except Exception:
            pass
        try:
            with open(path, "a", encoding="utf-8") as f:
                f.write(time.strftime("%Y-%m-%d %H:%M:%S  ") + str(msg) + "\n")
        except Exception:
            pass

    return log


def _load_wv():
    global _WV
    if _WV is None:
        import WaterValveBLE_v4 as wv
        _WV = wv
    return _WV


def _claim(d, log):
    global _MUTEX
    if vs.IS_WIN:
        try:
            k = ctypes.windll.kernel32
            k.CreateMutexW.argtypes = [ctypes.c_void_p, wintypes.BOOL,
                                       wintypes.LPCWSTR]
            k.CreateMutexW.restype = wintypes.HANDLE
            h = k.CreateMutexW(None, True, vs.guard_mutex_name(d))
            err = k.GetLastError()
            if not h:
                log("创建互斥体失败")
                return False
            if err == 183:
                k.CloseHandle(h)
                log("已有看门狗实例")
                return False
            _MUTEX = h
        except Exception as e:
            log(f"互斥体异常：{e}")
    try:
        with open(vs.guard_pid_path(d), "w", encoding="utf-8") as f:
            f.write(str(os.getpid()))
            f.flush()
            os.fsync(f.fileno())
    except Exception:
        pass
    return True


def _release(d):
    global _MUTEX
    try:
        os.remove(vs.guard_pid_path(d))
    except Exception:
        pass
    try:
        if _MUTEX:
            ctypes.windll.kernel32.CloseHandle(_MUTEX)
            _MUTEX = None
    except Exception:
        pass


def _ledger_add(d, st, res, why, log):
    """看门狗关阀后补一笔省钱账本（主程序已死，只能由看门狗写）。"""
    try:
        flow = (res or {}).get("flow") or {}
        start = float(st.get("opened_ts") or st.get("started_ts") or time.time())
        end = time.time()
        seconds = int(max(0, end - start))
        if flow.get("ok"):
            count = flow.get("useCount_i")
            if count is None:
                return
            rate = flow.get("rate2_i") or flow.get("rate1_i") or 25
            use_l = round(max(0.0, count * 0.5), 3)
            basis = "看门狗关阀，按 0.5 升/计数折算"
        elif st.get("state") == "open":
            count = None
            rate = 25
            use_l = round(max(0.0, seconds / 60.0 * 12.0), 3)
            basis = "看门狗关阀，按 12 L/min 估算"
        else:
            return
        by_use = round(max(0.0, float(use_l) * float(rate) / 1000.0), 4)
        charge = round(max(by_use, 0.025), 4)
        rec = {"ts": end, "start": start, "seconds": seconds,
               "count": count, "cloud_use_count": None,
               "liters": use_l, "basis": basis,
               "rate_per_ton": int(rate), "by_use_yuan": by_use,
               "min_charge": 0.025, "official_yuan": charge,
               "paid_yuan": 0.0, "saved_yuan": charge,
               "estimated": True, "auto": True, "reason": why,
               "source": "guard"}
        path = os.path.join(d, "usage_stats.json")
        data = {}
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}
        sessions = data.get("sessions") if isinstance(data, dict) else []
        sessions = sessions if isinstance(sessions, list) else []
        for x in sessions[-5:]:
            if x.get("source") == "guard" and abs(float(x.get("ts") or 0) - end) < 180:
                return
        if sessions and abs(float(sessions[-1].get("ts") or 0) - end) < 60:
            return
        sessions.append(rec)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"sessions": sessions[-2000:]}, f, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
        log(f"账本已记一笔（看门狗）：{use_l} 升 · 省 ¥{charge:.3f}（{why}）")
    except Exception as e:  # noqa: BLE001
        log(f"账本写入失败：{type(e).__name__}: {e}")


def _close_once(loop, st, log, d, why="auto"):
    wv = _load_wv()
    wv.load_config()
    mac = (st.get("mac") or wv.CFG.get("mac") or "").upper()
    if not mac:
        log("没有水表 MAC")
        return False
    session = wv.CFG.get("session") or None
    offline = not bool(session)
    valve = wv.WaterValveBLE()
    valve.mac, valve.name = mac, wv.CFG.get("name", "")
    log(f"连接 {mac}（{'离线' if offline else '在线'}）")
    try:
        if not loop.run_until_complete(valve.connect()):
            log("连接失败，稍后重试")
            return False
        try:
            st0 = loop.run_until_complete(valve.query_status())
            if (st0 or {}).get("onCharge") == "00":
                if wv.pending_read():
                    log("设备空闲，补清账")
                    r = loop.run_until_complete(
                        wv.ble_reclear(valve, session, zero_usage=True))
                    _ledger_add(d, st, r, why, log)
                    return bool(r.get("ok") or r.get("onCharge") == "00")
                log("设备已空闲")
                _ledger_add(d, st, {}, why, log)
                return True
        except Exception as e:
            log(f"预查失败：{type(e).__name__}")
        r = loop.run_until_complete(
            wv.ble_settle(valve, session, offline=offline,
                          zero_usage=True, tag="看门狗"))
        log(f"结算 ok={r.get('ok')} onCharge={r.get('onCharge')} "
            f"upload={r.get('upload')} clear={r.get('clear_by')}")
        if r.get("ok") or r.get("onCharge") == "00":
            _ledger_add(d, st, r, why, log)
            return True
        try:
            st2 = loop.run_until_complete(valve.query_status())
            if (st2 or {}).get("onCharge") == "00":
                _ledger_add(d, st, r, why, log)
                return True
            return False
        except Exception:
            return False
    except Exception as e:
        log(f"关阀异常：{type(e).__name__}: {e}")
        return False
    finally:
        try:
            if valve.client:
                loop.run_until_complete(valve.client.disconnect())
        except Exception:
            pass


def _reclear(loop, log):
    wv = _load_wv()
    wv.load_config()
    mac = (wv.CFG.get("mac") or "").upper()
    if not mac:
        return False
    valve = wv.WaterValveBLE()
    valve.mac, valve.name = mac, wv.CFG.get("name", "")
    try:
        if not loop.run_until_complete(valve.connect()):
            return False
        r = loop.run_until_complete(
            wv.ble_reclear(valve, wv.CFG.get("session"), zero_usage=True))
        log(f"补清账 onCharge={r.get('onCharge')} upload={r.get('upload')}")
        return bool(r.get("ok") or r.get("onCharge") == "00")
    except Exception as e:
        log(f"补清账异常：{type(e).__name__}: {e}")
        return False
    finally:
        try:
            if valve.client:
                loop.run_until_complete(valve.client.disconnect())
        except Exception:
            pass


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=HERE)
    ap.add_argument("--once", action="store_true")
    args = ap.parse_args()
    d = os.path.abspath(args.data_dir)
    os.makedirs(d, exist_ok=True)

    global _CONSOLE
    try:
        if sys.stdout is None or sys.stderr is None:
            _CONSOLE = open(os.path.join(d, "valve_guard.console.log"),
                            "a", encoding="utf-8", buffering=1)
            sys.stdout = sys.stdout or _CONSOLE
            sys.stderr = sys.stderr or _CONSOLE
    except Exception:
        pass

    log = _log_writer(d)
    if not _claim(d, log):
        return 0
    log(f"看门狗启动 pid={os.getpid()}")
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    last = 0.0
    try:
        while True:
            try:
                now = time.time()
                st = vs.read_journal(d)
                if vs.needs_close(st):
                    pid = st.get("pid")
                    alive = vs.pid_alive(pid)
                    fresh = vs.heartbeat_age(d, st) < 25
                    reboot = abs(float(st.get("boot_ts") or 0) - vs.boot_ts()) > 120
                    deadline = float(st.get("deadline_ts") or 0)
                    overdue = bool(deadline and now > deadline + 60)
                    if (not alive) or (not fresh) or reboot or overdue:
                        if now - last >= 8:
                            last = now
                            why = ("超时" if overdue else
                                   "主进程退出" if not alive else
                                   "心跳超时" if not fresh else "重启")
                            log(f"收尾（{why}）state={st.get('state')} pid={pid}")
                            if _close_once(loop, st, log, d, why):
                                vs.write_journal(d, state="closed",
                                                 closed_ts=time.time(),
                                                 closed_by="guard",
                                                 close_reason=why)
                                try:
                                    os.remove(vs.heartbeat_path(d))
                                except Exception:
                                    pass
                                log("收尾完成")
                            else:
                                vs.write_journal(
                                    d, state="closing",
                                    last_error="guard_failed",
                                    guard_attempts=int(st.get("guard_attempts") or 0) + 1,
                                    heartbeat_ts=now)
                elif os.path.exists(os.path.join(d, "pending_settle.json")):
                    pid = st.get("pid") if st else 0
                    if not vs.pid_alive(pid) and now - last >= 10:
                        last = now
                        log("pending 且主进程退出，补清账")
                        if _reclear(loop, log):
                            try:
                                _load_wv().pending_clear()
                            except Exception:
                                pass
            except Exception as e:
                log(f"循环异常：{type(e).__name__}: {e}")
            if args.once:
                break
            time.sleep(3)
    finally:
        _release(d)
        log("看门狗退出")
    return 0


if __name__ == "__main__":
    sys.exit(main())
