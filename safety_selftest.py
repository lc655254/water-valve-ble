# -*- coding: utf-8 -*-
"""安全层自检（不碰真表、不关机）。"""
import argparse
import asyncio
import os
import sys
import tempfile
import time
import types

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import valve_safety as vs
import valve_guard as vg


def basic(d):
    vs.write_journal(d, state="opening", mac="TEST", pid=os.getpid(),
                     boot_ts=vs.boot_ts(), started_ts=time.time(),
                     heartbeat_ts=time.time(), deadline_ts=time.time() + 60,
                     closed_ts=None)
    vs.touch_heartbeat(d)
    assert vs.needs_close(vs.read_journal(d))
    called = []

    def close(reason, timeout):
        called.append(reason)
        return True

    s = vs.Safety(d, close, dry_run=True)
    s.start(with_guard=False, with_hooks=False)
    assert s.request_close_sync("test")
    assert called and vs.read_journal(d)["state"] == "closed"
    print("state/close ok")

    vs.write_journal(d, state="open", mac="TEST", pid=999999,
                     boot_ts=vs.boot_ts(), opened_ts=time.time(),
                     heartbeat_ts=time.time(), deadline_ts=time.time() + 60,
                     closed_ts=None)
    rec = []
    s2 = vs.Safety(d, lambda r, t: rec.append(r) or True, dry_run=True)
    s2.recover_if_needed()
    for _ in range(30):
        if vs.read_journal(d).get("state") == "closed":
            break
        time.sleep(0.1)
    assert rec and vs.read_journal(d)["state"] == "closed"
    print("recover ok")

    vs.write_journal(d, state="open", mac="TEST", pid=999999,
                     boot_ts=vs.boot_ts(), opened_ts=time.time(),
                     heartbeat_ts=time.time(), deadline_ts=time.time() + 60,
                     closed_ts=None)
    s3 = vs.Safety(d, lambda r, t: True, dry_run=True)
    ok, _ = s3.before_open("TEST", 1)
    assert not ok
    s3.request_close_sync("gate")
    ok, _ = s3.before_open("TEST", 2)
    assert ok
    assert vs.read_journal(d).get("opened_ts") is None
    s3.mark_closed("done")
    print("open gate ok")
    print("basic PASS")


def hooks(d):
    vs.write_journal(d, state="open", mac="TEST", pid=os.getpid(),
                     boot_ts=vs.boot_ts(), opened_ts=time.time(),
                     heartbeat_ts=time.time(), deadline_ts=time.time() + 600,
                     closed_ts=None)
    called = []

    def close(reason, timeout):
        called.append(reason)
        return True

    s = vs.Safety(d, close, dry_run=True)
    h = vs.WindowsHooks(s, dry_run=True)
    r1 = h._handle(0, vs.WM_QUERYENDSESSION, 0, 0)
    time.sleep(0.6)
    assert r1 == 0 and called and vs.read_journal(d)["state"] == "closed"
    vs.write_journal(d, state="open", mac="TEST", pid=os.getpid(),
                     boot_ts=vs.boot_ts(), opened_ts=time.time(),
                     heartbeat_ts=time.time(), deadline_ts=time.time() + 600,
                     closed_ts=None)
    h2 = vs.WindowsHooks(s, dry_run=True)
    r2 = h2._handle(0, vs.WM_CLOSE, 0, 0)
    time.sleep(0.6)
    assert r2 == 0 and vs.read_journal(d)["state"] == "closed"
    print("hooks PASS")


def guard():
    state = {"oc": "00", "pending": {}}
    calls = []

    class Client:
        async def disconnect(self):
            return None

    class Valve:
        def __init__(self):
            self.client = Client()

        async def connect(self):
            return True

        async def query_status(self):
            return {"onCharge": state["oc"]}

    async def settle(*a, **k):
        calls.append("settle")
        return {"ok": True, "onCharge": "00", "upload": False,
                "clear_by": "fake", "zero_usage": True}

    async def reclear(*a, **k):
        calls.append("reclear")
        return {"ok": True, "onCharge": "00"}

    fake = types.SimpleNamespace(
        CFG={"mac": "AA:BB:CC:DD:EE:FF", "name": "fake", "session": None},
        load_config=lambda: None,
        WaterValveBLE=Valve,
        ble_settle=settle,
        ble_reclear=reclear,
        pending_read=lambda: state["pending"],
        pending_clear=lambda: state.update(pending={}),
    )
    old = vg._WV
    vg._WV = fake
    try:
        loop = asyncio.new_event_loop()
        assert vg._close_once(loop, {"mac": "AA:BB:CC:DD:EE:FF"}, print)
        assert not calls
        state["oc"] = "01"
        assert vg._close_once(loop, {"mac": "AA:BB:CC:DD:EE:FF"}, print)
        assert calls and calls[-1] == "settle"
        calls.clear()
        state["oc"] = "00"
        state["pending"] = {"kind": "settle"}
        assert vg._close_once(loop, {"mac": "AA:BB:CC:DD:EE:FF"}, print)
        assert calls and calls[-1] == "reclear"
        calls.clear()
        assert vg._reclear(loop, print)
        assert calls and calls[-1] == "reclear"
    finally:
        vg._WV = old
    print("guard PASS")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="")
    ap.add_argument("--basic", action="store_true")
    ap.add_argument("--hooks", action="store_true")
    ap.add_argument("--guard", action="store_true")
    a = ap.parse_args()
    d = a.data_dir or tempfile.mkdtemp(prefix="wv_safety_")
    os.makedirs(d, exist_ok=True)
    run_all = not (a.basic or a.hooks or a.guard)
    if run_all or a.basic:
        basic(d)
    if run_all or a.hooks:
        hooks(d)
    if run_all or a.guard:
        guard()
    print("ALL PASS", d)


if __name__ == "__main__":
    main()
