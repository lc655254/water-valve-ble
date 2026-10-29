# -*- coding: utf-8 -*-
"""安全开关水（自用）：python 开关水.py open | close | status

重要变化（2026-10-29）：
  以前 open 是“直连水表 → 开阀 → 进程退出”，人一走、电脑一关，阀门没人管。
  现在 open/close 都通过本机安全控制台（127.0.0.1:18801）执行：
    · 控制台常驻，带会话日志、看门狗、关机/休眠拦截；
    · 控制台没在跑就先把它拉起来；
    · 控制台起不来 → 拒绝开阀（绝不退回“开完就退出”的裸开模式）。
  close 在控制台起不来时，才允许一次直连零用量关阀兜底（只会关，不会开）。
"""
import json
import os
import subprocess
import sys
import time

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
API_PORT = 18801
API = f"http://127.0.0.1:{API_PORT}"
APP = os.path.join(HERE, "WaterValveBLE_Web.py")


def _pythonw() -> str:
    exe = sys.executable or "python.exe"
    if exe.lower().endswith("python.exe"):
        cand = exe[:-10] + "pythonw.exe"
        if os.path.exists(cand):
            return cand
    return exe


def _flags() -> int:
    f = 0
    for n in ("DETACHED_PROCESS", "CREATE_NEW_PROCESS_GROUP", "CREATE_NO_WINDOW"):
        f |= getattr(subprocess, n, 0)
    return f


def get_state(timeout=2.0):
    try:
        r = requests.get(API + "/api/state", timeout=timeout)
        if r.ok:
            return r.json()
    except Exception:
        pass
    return None


def api_running() -> bool:
    return get_state(1.0) is not None


def start_console(wait=40.0) -> bool:
    if api_running():
        return True
    print("[*] 安全控制台未运行，正在启动（窗口会自己出来）...")
    try:
        subprocess.Popen([_pythonw(), APP], cwd=HERE, creationflags=_flags(),
                         close_fds=True, stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception as e:  # noqa: BLE001
        print(f"[!] 启动控制台失败：{type(e).__name__}: {e}")
        return False
    t0 = time.time()
    while time.time() - t0 < wait:
        if api_running():
            print("[OK] 安全控制台已就绪")
            return True
        time.sleep(0.5)
    print("[!] 安全控制台 40 秒内没起来")
    return False


def wait_login(timeout=40.0) -> bool:
    """等控制台恢复会话（config.json 里的登录态）。"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        st = get_state()
        if st and st.get("account"):
            return True
        time.sleep(0.5)
    return False


def _wait_action(name, want_running, timeout=150.0, since=0.0):
    """等最近一次动作落地：返回 (ok, state)。只看 since 之后的新动作。"""
    t0 = time.time()
    last = None
    while time.time() - t0 < timeout:
        st = get_state()
        if st:
            act = st.get("last_action") or {}
            last = act
            if act.get("action") and name in str(act.get("action")) \
                    and float(act.get("ts") or 0) >= since:
                if act.get("ok") is not None:
                    wr = st.get("water_running")
                    if act.get("ok") and (want_running is None or wr == want_running):
                        return True, st
                    if act.get("ok") is False:
                        return False, st
        time.sleep(0.6)
    return False, {"last_action": last}


def open_water() -> int:
    if not start_console():
        print("[!] 已中止：安全控制台不可用，绝不会退回无人监管的裸开阀。")
        return 1
    if not wait_login():
        print("[!] 控制台没有登录会话：请先在界面里用短信验证码登录。")
        return 1
    try:
        t0 = time.time()
        r = requests.post(API + "/api/water/open", timeout=8)
        print("[*] 开水指令：", r.text[:200])
    except Exception as e:  # noqa: BLE001
        print(f"[!] 调用控制台失败：{e}")
        return 1
    ok, st = _wait_action("开水", True, timeout=150, since=t0)
    act = (st or {}).get("last_action") or {}
    print(f"[{'OK' if ok else '!'}] {act.get('msg') or ('已开始放水' if ok else '开阀未确认')}")
    if ok:
        print("[*] 控制台会一直监管；关水请运行 关水.bat，或直接关窗口（会先零用量关阀）。")
        print("[*] 不会出现“开完就退出、阀门没人管”的情况。")
    return 0 if ok else 1


def direct_close() -> int:
    """兜底：控制台起不来时，直连做一次零用量关阀（只关不开）。"""
    print("[*] 控制台不可用，尝试直连零用量关阀兜底 ...")
    try:
        import asyncio
        import WaterValveBLE_v4 as wv
    except Exception as e:  # noqa: BLE001
        print(f"[!] 加载核心失败：{e}")
        return 1
    wv.load_config()
    s = wv.CFG.get("session")
    valve = wv.WaterValveBLE()

    async def job():
        if not await valve.connect():
            return False
        try:
            return await wv.ble_close(valve, s, zero_usage=True)
        finally:
            try:
                await valve.disconnect()
            except Exception:
                pass

    try:
        ok = asyncio.run(job())
    except Exception as e:  # noqa: BLE001
        print(f"[!] 直连关阀异常：{type(e).__name__}: {e}")
        ok = False
    print("[OK] 已零用量关阀" if ok else "[!] 直连关阀未确认，请靠近水表重试")
    return 0 if ok else 1


def close_water() -> int:
    if not start_console():
        return direct_close()
    if not wait_login():
        print("[!] 控制台没有登录会话，改走直连零用量关阀 ...")
        return direct_close()
    try:
        t0 = time.time()
        r = requests.post(API + "/api/water/close", timeout=8)
        print("[*] 关水指令：", r.text[:200])
    except Exception as e:  # noqa: BLE001
        print(f"[!] 调用控制台失败：{e}")
        return direct_close()
    ok, st = _wait_action("关水", False, timeout=180, since=t0)
    act = (st or {}).get("last_action") or {}
    print(f"[{'OK' if ok else '!'}] {act.get('msg') or ('已零用量关阀' if ok else '关阀未确认')}")
    return 0 if ok else 1


def status() -> int:
    st = get_state(2.0)
    if st:
        print(json.dumps({
            "account": st.get("account"), "device": st.get("device"),
            "mac": st.get("device_mac"), "connected": st.get("connected"),
            "water_running": st.get("water_running"),
            "last_action": st.get("last_action"),
        }, ensure_ascii=False, indent=2))
        return 0
    print("[!] 安全控制台未运行；开/关水请用 开水.bat / 关水.bat（会自动拉起控制台）")
    return 1


def main() -> int:
    cmd = (sys.argv[1] if len(sys.argv) > 1 else "status").lower()
    if cmd in ("help", "-h", "--help"):
        print("用法：python 开关水.py open | close | status")
        return 0
    if cmd == "open":
        return open_water()
    if cmd == "close":
        return close_water()
    return status()


if __name__ == "__main__":
    raise SystemExit(main())
