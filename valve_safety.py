# -*- coding: utf-8 -*-
"""阀门会话安全层。

状态落在 valve_session.json；心跳单独用文件 mtime，避免定时重写 JSON。
对外主要暴露 Safety / WindowsHooks / ensure_guard / read_journal / needs_close。
"""
from __future__ import annotations

import atexit
import ctypes
import json
import os
import signal
import subprocess
import sys
import threading
import time
from ctypes import wintypes

IS_WIN = os.name == "nt"
OPEN_STATES = ("opening", "open", "closing")

WM_CLOSE = 0x0010
WM_QUERYENDSESSION = 0x0011
WM_ENDSESSION = 0x0016
WM_POWERBROADCAST = 0x0218
WM_SAFETY_PING = 0x8001
PBT_APMSUSPEND = 0x0004
BROADCAST_QUERY_DENY = 0xFFFFFFFF
GWLP_WNDPROC = -4
LRESULT = ctypes.c_ssize_t


def _now() -> float:
    return time.time()


def journal_path(d: str) -> str:
    return os.path.join(d, "valve_session.json")


def heartbeat_path(d: str) -> str:
    return os.path.join(d, "valve_heartbeat")


def guard_pid_path(d: str) -> str:
    return os.path.join(d, "valve_guard.pid")


def guard_log_path(d: str) -> str:
    return os.path.join(d, "valve_guard.log")


def _atomic_write(path: str, obj) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)
    try:
        fd = os.open(os.path.dirname(path) or ".", os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    except Exception:
        pass


def read_journal(d: str) -> dict:
    try:
        with open(journal_path(d), encoding="utf-8-sig") as f:
            v = json.load(f)
        return v if isinstance(v, dict) else {}
    except Exception:
        return {}


def write_journal(d: str, **kv) -> dict:
    st = read_journal(d)
    st.update(kv)
    st["schema"] = 1
    st["updated_ts"] = _now()
    _atomic_write(journal_path(d), st)
    return st


def touch_heartbeat(d: str) -> None:
    try:
        p = heartbeat_path(d)
        if not os.path.exists(p):
            with open(p, "wb"):
                pass
        os.utime(p, None)
    except Exception:
        pass


def heartbeat_age(d: str, st: dict | None = None) -> float:
    st = st if st is not None else read_journal(d)
    hb = float(st.get("heartbeat_ts") or 0)
    try:
        hb = max(hb, os.path.getmtime(heartbeat_path(d)))
    except Exception:
        pass
    return _now() - hb if hb else 1e9


def needs_close(st: dict) -> bool:
    return bool(st) and st.get("state") in OPEN_STATES and not st.get("closed_ts")


def pid_alive(pid) -> bool:
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return False
    if pid <= 0:
        return False
    if not IS_WIN:
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False
    SYNCHRONIZE = 0x00100000
    WAIT_TIMEOUT = 0x00000102
    h = ctypes.windll.kernel32.OpenProcess(SYNCHRONIZE, False, pid)
    if not h:
        return False
    try:
        return ctypes.windll.kernel32.WaitForSingleObject(h, 0) == WAIT_TIMEOUT
    finally:
        ctypes.windll.kernel32.CloseHandle(h)


def boot_ts() -> float:
    if not IS_WIN:
        return 0.0
    try:
        return _now() - ctypes.windll.kernel32.GetTickCount64() / 1000.0
    except Exception:
        return 0.0


def _script_dir() -> str:
    return os.path.dirname(os.path.abspath(__file__))


def guard_script() -> str:
    return os.path.join(_script_dir(), "valve_guard.py")


def _pythonw() -> str:
    exe = sys.executable or "python.exe"
    if exe.lower().endswith("python.exe"):
        p = exe[:-10] + "pythonw.exe"
        if os.path.exists(p):
            return p
    return exe


def guard_command(d: str) -> list[str]:
    if getattr(sys, "frozen", False):
        return [sys.executable, "--guard", "--data-dir", d]
    return [_pythonw(), guard_script(), "--data-dir", d]


def guard_mutex_name(d: str) -> str:
    import hashlib
    h = hashlib.md5(os.path.abspath(d).lower().encode("utf-8")).hexdigest()[:10]
    return f"Local\\WaterValveGuard_{h}"


def guard_running(d: str) -> bool:
    if IS_WIN:
        try:
            k = ctypes.windll.kernel32
            k.OpenMutexW.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR]
            k.OpenMutexW.restype = wintypes.HANDLE
            h = k.OpenMutexW(0x00100000, False, guard_mutex_name(d))
            if h:
                k.CloseHandle(h)
                return True
        except Exception:
            pass
    try:
        with open(guard_pid_path(d), encoding="utf-8") as f:
            pid = int((f.read() or "0").strip())
    except Exception:
        return False
    return pid_alive(pid)


def ensure_guard(d: str, log=print) -> bool:
    if not IS_WIN or os.environ.get("WV_NO_GUARD") == "1":
        return True
    if guard_running(d):
        return True
    if not getattr(sys, "frozen", False) and not os.path.exists(guard_script()):
        log("[安全] 缺少 valve_guard.py")
        return False
    flags = 0
    for n in ("DETACHED_PROCESS", "CREATE_NEW_PROCESS_GROUP", "CREATE_NO_WINDOW"):
        flags |= getattr(subprocess, n, 0)
    try:
        subprocess.Popen(guard_command(d), cwd=_script_dir(), creationflags=flags,
                         close_fds=True, stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception as e:
        log(f"[安全] 启动看门狗失败：{type(e).__name__}: {e}")
        return False
    for _ in range(40):
        time.sleep(0.1)
        if guard_running(d):
            log("[安全] 看门狗已启动")
            return True
    log("[安全] 看门狗未就绪")
    return False


def install_autostart(d: str, log=print) -> bool:
    if not IS_WIN or os.environ.get("WV_NO_AUTOSTART") == "1":
        return False
    startup = os.path.join(os.environ.get("APPDATA", ""),
                           r"Microsoft\Windows\Start Menu\Programs\Startup")
    bat = os.path.join(startup, "WaterValveGuard.bat")
    try:
        if os.path.exists(bat):
            with open(bat, encoding="utf-8", errors="ignore") as f:
                if "valve_guard.py" in f.read():
                    os.remove(bat)
    except Exception:
        pass
    try:
        import winreg
        cmd = subprocess.list2cmdline(guard_command(d))
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                            r"Software\Microsoft\Windows\CurrentVersion\Run",
                            0, winreg.KEY_SET_VALUE) as k:
            winreg.SetValueEx(k, "WaterValveGuard", 0, winreg.REG_SZ, cmd)
        log(f"[安全] 自启动：{cmd}")
        return True
    except Exception as e:
        log(f"[安全] 注册自启动失败：{type(e).__name__}: {e}")
        return False


class _WNDCLASSW(ctypes.Structure):
    _fields_ = [
        ("style", wintypes.UINT), ("lpfnWndProc", ctypes.c_void_p),
        ("cbClsExtra", ctypes.c_int), ("cbWndExtra", ctypes.c_int),
        ("hInstance", wintypes.HINSTANCE), ("hIcon", wintypes.HICON),
        ("hCursor", wintypes.HANDLE), ("hbrBackground", wintypes.HBRUSH),
        ("lpszMenuName", wintypes.LPCWSTR), ("lpszClassName", wintypes.LPCWSTR),
    ]


def _acquire_shutdown_privilege() -> bool:
    try:
        adv = ctypes.windll.advapi32
        k32 = ctypes.windll.kernel32
        TOKEN_ADJUST_PRIVILEGES = 0x20
        TOKEN_QUERY = 0x8
        SE_PRIVILEGE_ENABLED = 0x2

        class LUID(ctypes.Structure):
            _fields_ = [("LowPart", wintypes.DWORD), ("HighPart", wintypes.LONG)]

        class LAA(ctypes.Structure):
            _fields_ = [("Luid", LUID), ("Attributes", wintypes.DWORD)]

        class TP(ctypes.Structure):
            _fields_ = [("PrivilegeCount", wintypes.DWORD), ("Privileges", LAA * 1)]

        tok = wintypes.HANDLE()
        if not adv.OpenProcessToken(k32.GetCurrentProcess(),
                                    TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY,
                                    ctypes.byref(tok)):
            return False
        try:
            luid = LUID()
            if not adv.LookupPrivilegeValueW(None, "SeShutdownPrivilege",
                                              ctypes.byref(luid)):
                return False
            tp = TP(1, (LAA * 1)(LAA(luid, SE_PRIVILEGE_ENABLED)))
            return bool(adv.AdjustTokenPrivileges(tok, False, ctypes.byref(tp),
                                                   0, None, None))
        finally:
            k32.CloseHandle(tok)
    except Exception:
        return False


class WindowsHooks:
    def __init__(self, safety: "Safety", window_title="蓝牙水库 · 水控",
                 dry_run=False):
        self.s = safety
        self.title = window_title
        self.dry = dry_run
        self.log = safety.log
        self.u = ctypes.windll.user32 if IS_WIN else None
        self._keep = []
        self._old = None
        self._hwnd = None
        self._blocking = False
        if IS_WIN:
            self._declare()

    def _declare(self):
        u = self.u
        try:
            u.DefWindowProcW.argtypes = [wintypes.HWND, wintypes.UINT,
                                         wintypes.WPARAM, wintypes.LPARAM]
            u.DefWindowProcW.restype = LRESULT
            u.CallWindowProcW.argtypes = [ctypes.c_void_p, wintypes.HWND,
                                          wintypes.UINT, wintypes.WPARAM,
                                          wintypes.LPARAM]
            u.CallWindowProcW.restype = LRESULT
            u.FindWindowW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR]
            u.FindWindowW.restype = wintypes.HWND
            u.GetWindowLongPtrW.argtypes = [wintypes.HWND, ctypes.c_int]
            u.GetWindowLongPtrW.restype = ctypes.c_void_p
            u.SetWindowLongPtrW.argtypes = [wintypes.HWND, ctypes.c_int,
                                            ctypes.c_void_p]
            u.SetWindowLongPtrW.restype = ctypes.c_void_p
            u.CreateWindowExW.argtypes = [
                wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR,
                wintypes.DWORD, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                ctypes.c_int, wintypes.HWND, wintypes.HMENU,
                wintypes.HINSTANCE, wintypes.LPVOID]
            u.CreateWindowExW.restype = wintypes.HWND
            u.RegisterClassW.argtypes = [ctypes.POINTER(_WNDCLASSW)]
            u.RegisterClassW.restype = wintypes.ATOM
            u.ShutdownBlockReasonCreate.argtypes = [wintypes.HWND,
                                                     wintypes.LPCWSTR]
            u.ShutdownBlockReasonCreate.restype = wintypes.BOOL
            u.ShutdownBlockReasonDestroy.argtypes = [wintypes.HWND]
            u.ShutdownBlockReasonDestroy.restype = wintypes.BOOL
            u.ExitWindowsEx.argtypes = [wintypes.UINT, wintypes.DWORD]
            u.ExitWindowsEx.restype = wintypes.BOOL
            u.GetMessageW.argtypes = [ctypes.POINTER(wintypes.MSG),
                                      wintypes.HWND, wintypes.UINT,
                                      wintypes.UINT]
            u.GetMessageW.restype = wintypes.BOOL
            u.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT,
                                       wintypes.WPARAM, wintypes.LPARAM]
            u.PostMessageW.restype = wintypes.BOOL
        except Exception:
            pass

    def start(self):
        if IS_WIN:
            threading.Thread(target=self._install, daemon=True,
                             name="wv-win-hooks").start()

    def _install(self):
        hwnd = None
        for _ in range(60):
            try:
                hwnd = self.u.FindWindowW(None, self.title)
            except Exception:
                hwnd = None
            if hwnd:
                break
            time.sleep(0.2)
        if hwnd:
            try:
                self._subclass(hwnd)
                self.log("[安全] 已接管主窗口消息")
            except Exception as e:
                self.log(f"[安全] 子类化失败：{type(e).__name__}: {e}")
        try:
            self._hidden()
        except Exception as e:
            self.log(f"[安全] 隐藏窗口失败：{type(e).__name__}: {e}")

    def _subclass(self, hwnd):
        self._hwnd = hwnd
        proto = ctypes.WINFUNCTYPE(LRESULT, wintypes.HWND, wintypes.UINT,
                                   wintypes.WPARAM, wintypes.LPARAM)
        self._old = (self.u.GetWindowLongPtrW(hwnd, GWLP_WNDPROC)
                     if ctypes.sizeof(ctypes.c_void_p) == 8
                     else self.u.GetWindowLongW(hwnd, GWLP_WNDPROC))

        def proc(h, msg, wp, lp):
            try:
                r = self._handle(h, msg, wp, lp)
                if r is not None:
                    return r
            except Exception as e:
                self.log(f"[安全] 消息 {msg:#x} 异常：{e}")
            return self._call_old(h, msg, wp, lp)

        p = proto(proc)
        self._keep.append(p)
        if ctypes.sizeof(ctypes.c_void_p) == 8:
            self.u.SetWindowLongPtrW(hwnd, GWLP_WNDPROC,
                                     ctypes.cast(p, ctypes.c_void_p))
        else:
            self.u.SetWindowLongW(hwnd, GWLP_WNDPROC,
                                  ctypes.cast(p, ctypes.c_void_p).value)

    def _call_old(self, hwnd, msg, wp, lp):
        try:
            if self._old:
                return self.u.CallWindowProcW(ctypes.c_void_p(self._old),
                                              hwnd, msg, wp, lp)
        except Exception:
            pass
        return self.u.DefWindowProcW(hwnd, msg, wp, lp)

    def _hidden(self):
        proto = ctypes.WINFUNCTYPE(LRESULT, wintypes.HWND, wintypes.UINT,
                                   wintypes.WPARAM, wintypes.LPARAM)
        hinst = ctypes.windll.kernel32.GetModuleHandleW(None)
        name = "ValveSafetyWnd"

        def proc(h, msg, wp, lp):
            try:
                r = self._handle(h, msg, wp, lp)
                if r is not None:
                    return r
            except Exception:
                pass
            return self.u.DefWindowProcW(h, msg, wp, lp)

        p = proto(proc)
        self._keep.append(p)
        wc = _WNDCLASSW()
        wc.lpfnWndProc = ctypes.cast(p, ctypes.c_void_p)
        wc.hInstance = hinst
        wc.lpszClassName = name
        try:
            self.u.RegisterClassW(ctypes.byref(wc))
        except Exception:
            pass
        hwnd = self.u.CreateWindowExW(0, name, "ValveSafety", 0,
                                      -32000, -32000, 1, 1, None, None,
                                      hinst, None)
        if not hwnd:
            return

        def loop():
            msg = wintypes.MSG()
            while self.u.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
                self.u.TranslateMessage(ctypes.byref(msg))
                self.u.DispatchMessageW(ctypes.byref(msg))

        threading.Thread(target=loop, daemon=True, name="wv-win-msg").start()

    def _handle(self, hwnd, msg, wp, lp):
        if msg == WM_SAFETY_PING:
            return 0x5A5A
        if msg == WM_CLOSE:
            if not self.s.needs_close_now():
                return None
            if self._blocking:
                return 0
            self._blocking = True
            self.log("[安全] 关窗：先关阀")
            self._close_async(hwnd, "关闭窗口", 0, None)
            return 0
        if msg == WM_QUERYENDSESSION:
            if not self.s.needs_close_now():
                return 1
            if self._blocking:
                return 0
            self._blocking = True
            self.log("[安全] 关机/注销：先关阀")
            try:
                self.u.ShutdownBlockReasonCreate(hwnd, "正在关闭水阀，请稍候")
            except Exception:
                pass
            self._close_async(hwnd, "系统关机", int(lp or 0), hwnd)
            return 0
        if msg == WM_ENDSESSION:
            if wp and self.s.needs_close_now():
                self.log("[安全] 会话结束，但阀门未确认关闭")
            return 0
        if msg == WM_POWERBROADCAST:
            if wp == PBT_APMSUSPEND and self.s.needs_close_now():
                if self._blocking:
                    return BROADCAST_QUERY_DENY
                self._blocking = True
                self.log("[安全] 休眠：先关阀")
                self._close_async(hwnd, "系统休眠", 0, None)
                return BROADCAST_QUERY_DENY
            return 1
        return None

    def _close_async(self, hwnd, reason, flags, block_hwnd):
        def work():
            ok = False
            try:
                ok = self.s.request_close_sync(reason, 120)
            except Exception as e:
                self.log(f"[安全] {reason} 异常：{type(e).__name__}: {e}")
            self._blocking = False
            if block_hwnd:
                try:
                    self.u.ShutdownBlockReasonDestroy(block_hwnd)
                except Exception:
                    pass
            if not ok:
                self.log(f"[安全] {reason} 关阀失败，看门狗继续重试")
                return
            self.log(f"[安全] {reason} 已关阀")
            if self.dry:
                return
            if reason == "关闭窗口":
                try:
                    self.u.PostMessageW(hwnd, WM_CLOSE, 0, 0)
                except Exception:
                    pass
                return
            if reason == "系统休眠":
                try:
                    _acquire_shutdown_privilege()
                    ctypes.windll.powrprof.SetSuspendState(False, False, False)
                except Exception:
                    pass
                return
            _acquire_shutdown_privilege()
            ew = int(flags) & 0x1F
            if (ew & 0x0F) not in (0, 1, 2, 8):
                ew = (ew & 0x10) | 1
            try:
                self.u.ExitWindowsEx(ew | 0x10, 0)
            except Exception:
                pass

        threading.Thread(target=work, daemon=True, name="wv-win-close").start()


class Safety:
    def __init__(self, data_dir, close_cb, is_open_cb=None, log=print,
                 max_open_min=20, max_cost_yuan=3.0, enabled=True,
                 dry_run=False):
        self.dir = data_dir
        self.close_cb = close_cb
        self.log = log
        self.max_open = max(1, int(max_open_min or 20))
        self.max_cost = float(max_cost_yuan or 3.0)
        self.enabled = enabled
        self.dry = dry_run
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._closing = False
        self._recovering = False
        self._last_try = 0.0
        self._last_guard = 0.0
        self._started = False

    def state(self):
        return read_journal(self.dir)

    def needs_close_now(self):
        return needs_close(self.state())

    @property
    def recovery_needed(self):
        return self._recovering

    def start(self, with_guard=True, with_hooks=True,
              window_title="蓝牙水库 · 水控"):
        if self._started or not self.enabled:
            return
        self._started = True
        if with_guard:
            ensure_guard(self.dir, self.log)
            install_autostart(self.dir, self.log)
        if with_hooks and IS_WIN:
            WindowsHooks(self, window_title, self.dry).start()
        threading.Thread(target=self._heartbeat, daemon=True,
                         name="wv-hb").start()
        threading.Thread(target=self._watch, daemon=True,
                         name="wv-watch").start()
        atexit.register(self.on_exit)
        for sig in ("SIGINT", "SIGTERM", "SIGBREAK"):
            s = getattr(signal, sig, None)
            if s:
                try:
                    signal.signal(s, self._signal)
                except Exception:
                    pass

    def _signal(self, signum, frame):
        try:
            self.request_close_sync(f"信号{signum}", 90)
        finally:
            os._exit(0)

    def on_exit(self):
        try:
            if self.needs_close_now():
                self.request_close_sync("程序退出", 90)
        except Exception:
            pass

    def _heartbeat(self):
        while not self._stop.wait(2.0):
            try:
                if self.needs_close_now():
                    touch_heartbeat(self.dir)
            except Exception:
                pass

    def _rate(self):
        try:
            import WaterValveBLE_v4 as wv
            r = float(wv.CFG.get("rate") or 0.025)
            return r / 1000.0 if r > 1 else r
        except Exception:
            return 0.025

    def _watch(self):
        while not self._stop.wait(2.0):
            try:
                st = self.state()
                if not needs_close(st):
                    continue
                now = _now()
                if not self.dry and now - self._last_guard > 10:
                    self._last_guard = now
                    if not guard_running(self.dir):
                        self.log("[安全] 重新拉起看门狗")
                        ensure_guard(self.dir, self.log)
                age = heartbeat_age(self.dir, st)
                deadline = float(st.get("deadline_ts") or 0)
                started = float(st.get("started_ts") or now)
                reason = ""
                if st.get("state") == "opening" and now - started > 180:
                    reason = "开阀确认超时"
                elif deadline and now > deadline:
                    reason = f"超过 {self.max_open} 分钟"
                elif age > 25 and not self._closing:
                    reason = "心跳超时"
                elif st.get("state") == "open":
                    opened = float(st.get("opened_ts") or 0)
                    if opened < started:
                        opened = started
                    cost = max(0.0, (now - opened) / 60.0 * 12.0 * self._rate())
                    if self.max_cost and cost >= self.max_cost:
                        reason = f"预计费用达到 ¥{self.max_cost:.2f}"
                if reason:
                    self.log(f"[安全] {reason}：自动关阀")
                    self.request_close_async(reason)
            except Exception:
                pass

    def before_open(self, mac="", device_id=None, mode="online",
                    order_no=""):
        if not self.enabled:
            return True, ""
        if needs_close(self.state()):
            return False, "上次开阀未收尾，已阻止再次开阀"
        if self._recovering:
            return False, "正在处理上次未收尾的阀门"
        if not self.dry and not ensure_guard(self.dir, self.log):
            return False, "看门狗未启动，已阻止开阀"
        now = _now()
        write_journal(self.dir, state="opening", mac=mac, device_id=device_id,
                      mode=mode, order_no=order_no, pid=os.getpid(),
                      boot_ts=boot_ts(), started_ts=now, opened_ts=None,
                      heartbeat_ts=now, deadline_ts=now + self.max_open * 60,
                      max_open_min=self.max_open,
                      max_cost_yuan=self.max_cost,
                      closed_ts=None, closed_by="", close_reason="",
                      close_attempts=0, guard_attempts=0, last_error="",
                      upload_ok=None)
        touch_heartbeat(self.dir)
        return True, ""

    def mark_open(self, opened_ts=None):
        now = _now()
        opened = float(opened_ts or now)
        write_journal(self.dir, state="open", opened_ts=opened,
                      heartbeat_ts=now,
                      deadline_ts=opened + self.max_open * 60)
        touch_heartbeat(self.dir)

    def mark_open_failed(self, reason="开阀未确认"):
        write_journal(self.dir, state="closing", close_reason=reason,
                      close_requested_ts=_now(), heartbeat_ts=_now())
        touch_heartbeat(self.dir)
        self.request_close_async(reason)

    def mark_closing(self, reason="关阀"):
        write_journal(self.dir, state="closing", close_reason=reason,
                      close_requested_ts=_now(), heartbeat_ts=_now())
        touch_heartbeat(self.dir)

    def mark_closed(self, reason="关阀完成", upload=None):
        write_journal(self.dir, state="closed", closed_ts=_now(),
                      close_reason=reason, heartbeat_ts=_now(),
                      upload_ok=upload)
        try:
            os.remove(heartbeat_path(self.dir))
        except Exception:
            pass

    def request_close_sync(self, reason="安全关阀", timeout=90):
        if not self.needs_close_now():
            return True
        with self._lock:
            if not self.needs_close_now():
                return True
            self._closing = True
            self._last_try = _now()
            self.mark_closing(reason)
            ok = False
            try:
                ok = bool(self.close_cb(reason, timeout))
            except Exception as e:
                self.log(f"[安全] 关阀异常：{type(e).__name__}: {e}")
            finally:
                self._closing = False
            if ok:
                self.mark_closed(reason)
                self.log(f"[安全] 已关闭：{reason}")
            else:
                st = self.state()
                n = int(st.get("close_attempts") or 0) + 1
                write_journal(self.dir, state="closing", close_attempts=n,
                              last_error=reason, heartbeat_ts=_now())
                touch_heartbeat(self.dir)
                self.log(f"[安全] 关阀未确认（{n}）：{reason}")
            return ok

    def request_close_async(self, reason="安全关阀", timeout=90):
        if self._closing or _now() - self._last_try < 8:
            return
        self._last_try = _now()
        threading.Thread(target=self.request_close_sync,
                         args=(reason, timeout), daemon=True,
                         name="wv-close").start()

    def recover_if_needed(self):
        if not self.enabled:
            return
        if not needs_close(self.state()):
            return
        self._recovering = True
        self.log("[安全] 发现未收尾会话，启动自愈")
        threading.Thread(target=self._recover, daemon=True,
                         name="wv-recover").start()

    def _recover(self):
        while True:
            if self.request_close_sync("启动自愈", 120):
                self._recovering = False
                return
            time.sleep(15)
