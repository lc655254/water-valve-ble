# -*- coding: utf-8 -*-
"""离线自检（不连蓝牙、不碰钱包、不联网）

覆盖三件事：
  1) 零用量流水生成 —— 用 2026-10-18 那笔真实扣费流水做样本，确认计费字段归零、
     身份字段与长度原样保留
  2) 结算入口默认零用量 —— ble_close 默认值 / 前置补结算 / 全局拦截闸
  3) Web 后端免秘钥启动 —— /api/state 直接可用、/api/activate 直接成功

跑法： python 自检_离线.py
"""
import inspect
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
os.environ.setdefault("WV_RES_DIR", HERE)

# 统一 UTF-8：重定向到文件时默认会用 GBK，导致前半段中文乱码
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

FAIL = []


def check(name, cond, detail=""):
    print(("  [PASS] " if cond else "  [FAIL] ") + name + (f"  {detail}" if detail else ""))
    if not cond:
        FAIL.append(name)


print("=" * 66)
print("  蓝牙水库 · 免激活测试版 离线自检")
print("=" * 66)

t0 = time.perf_counter()
import WaterValveBLE_v4 as wv
t_core = time.perf_counter() - t0

print(f"\n[1] 核心模块导入 {t_core * 1000:.0f} ms")

# --- 用真实扣费流水做样本：2026-10-18 17:01:57，2.575 元那笔 ---
RAW = ("80" "261018170157" "0000040C" "0000013D" "0000A351" "02"
       "00000001" "00001388" "00000A0F" "00000019" "00158303783C")
print("\n[2] 零用量流水（样本 = 10-18 扣费 2.575 元那笔）")
f = wv.parse_flow(RAW)
check("样本可解析", f.get("ok"), wv.flow_brief(f))
check("样本确实是真实用量", f["useCount_i"] == 1 and f["prepay_i"] == 5000)

z = wv.zero_usage_from(RAW)
zf = wv.parse_flow(z)
check("长度不变", len(z) == len(RAW), f"{len(RAW)//2}B → {len(z)//2}B")
check("status 保留", zf["status"] == "80")
check("时间保留", zf["time"] == f["time"], zf["time"])
check("productId 保留", zf["productId"] == f["productId"], str(zf["productId_i"]))
check("deviceId 保留", zf["deviceId"] == f["deviceId"], str(zf["deviceId_i"]))
check("accountId 保留", zf["accountId"] == f["accountId"], zf["accountId"])
check("MAC 保留", zf["mac"] == f["mac"], zf["mac"])
check("useCount 归零", zf["useCount_i"] == 0, str(zf["useCount_i"]))
check("预扣归零", zf["prepay_i"] == 0, str(zf["prepay_i"]))
check("费率归零（42B 老固件）", zf["rate1_i"] == 0 and zf["rate2_i"] == 0)

# 62B 新型号：费率必须保留，否则云端回 226
ext = RAW + "AA" * 20
ze = wv.zero_usage_from(ext)
zef = wv.parse_flow(ze)
check("62B 长度不变", len(ze) == len(ext), f"{len(ext)//2}B")
check("62B 费率保留（过 226 校验）", zef["rate1_i"] == f["rate1_i"] and zef["rate2_i"] == f["rate2_i"])
check("62B 扩展段保留", zef["ext"] == "aa" * 20 or zef["ext"] == "AA" * 20, f"{len(zef['ext'])//2}B")
check("62B 用量/预扣归零", zef["useCount_i"] == 0 and zef["prepay_i"] == 0)

print("\n[3] 结算入口默认零用量")
sig = inspect.signature(wv.ble_close)
check("ble_close 默认 zero_usage=True", sig.parameters["zero_usage"].default is True)
check("默认不允许真实计费", wv.ALLOW_REAL_BILL is False)
src_idle = inspect.getsource(wv.ensure_idle)
check("开阀前置补结算走零用量", "zero_usage=True" in src_idle)
src_settle = inspect.getsource(wv.ble_settle)
check("ble_settle 有拦截闸", "ALLOW_REAL_BILL" in src_settle)

print("\n[4] Web 后端：免秘钥")
t1 = time.perf_counter()
import WaterValveBLE_Web as web
t_web = time.perf_counter() - t1
check("Web 模块导入", True, f"{t_web * 1000:.0f} ms")
check("license_boot 不再联网", "check_license_any" not in inspect.getsource(web.license_boot))
check("license_boot 直接放行", web.license_boot() is True)
check("剩余天数", web.LIC_DAYS[0] == 9999, str(web.LIC_DAYS[0]))

client = web.app.test_client()
t2 = time.perf_counter()
r = client.get("/api/state")
dt = (time.perf_counter() - t2) * 1000
d = r.get_json() or {}
check("/api/state 可用", r.status_code == 200 and d.get("ok") is True, f"{dt:.1f} ms")
check("不要求激活", d.get("need_activation") is False)
check("许可天数 = 9999", d.get("license") == 9999)
check("接口不阻塞（<200ms）", dt < 200, f"{dt:.1f} ms")

r2 = client.post("/api/activate", json={})
check("/api/activate 直接成功", (r2.get_json() or {}).get("ok") is True)

print("\n[5] 结算链路仿真（假设备 + 假云端，不碰真表/真钱）")
import asyncio
import contextlib
import io
from types import SimpleNamespace

FLOW_STRIPPED = RAW[2:]


class FakeValve:
    """只回放协议帧，不做任何 IO（带 link 状态，供断线重连逻辑判断）"""

    def __init__(self, oncharge="00"):
        self.oncharge = oncharge
        self.client = SimpleNamespace(is_connected=True)

    async def connect(self):
        self.client.is_connected = True
        return True

    async def disconnect(self):
        self.client.is_connected = False

    async def send_and_wait(self, cmd, data="00", timeout=10):
        if cmd == "85":
            return {"raw": "80" + FLOW_STRIPPED, "data_area": RAW}
        return {"raw": "80", "data_area": "80"}

    async def query_status(self):
        return {"tacTimes": "09000000", "commMode": "10",
                "onCharge": self.oncharge, "mac": "00158303783C"}


caught = {}


def fake_upload(s, xf, rn, cm):
    """同步桩：ble_upload 本身是同步函数，返回 None 表示「云端没收下」"""
    caught["xf"] = xf
    return None


wv.ble_upload = fake_upload
wv.CFG["last_rn"], wv.CFG["last_rn_pre"], wv.CFG["last_cm"] = "09000000", "07000000", "10"

buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    res = asyncio.run(wv.ble_settle(FakeValve(), {"telephone": "x", "userId": 1,
                                                  "loginCode": "y"},
                                    zero_usage=False, tag="自检"))
zf2 = wv.parse_flow(caught.get("xf") or "")
check("请求真实用量也被拦成零用量", res["zero_usage"] is True)
check("实际上报的流水用量=0", zf2.get("useCount_i") == 0, f"useCount={zf2.get('useCount_i')}")
check("实际上报的预扣=0", zf2.get("prepay_i") == 0, f"prepay={zf2.get('prepay_i')}")
check("订单仍正常了结（设备回空闲）", res["ok"] is True, f"onCharge={res['onCharge']}")

caught.clear()


async def fake_settle(valve, s, **kw):
    caught.update(kw)
    return {"ok": True}


wv.ble_settle = fake_settle
with contextlib.redirect_stdout(io.StringIO()):
    idle_ok = asyncio.run(wv.ensure_idle(FakeValve("03"), None))
check("开阀前置补结算传 zero_usage=True", caught.get("zero_usage") is True,
      f"ok={idle_ok}")

print("\n[6] 身份闸：连到同型号的隔壁表必须拦下")


class OtherMeter:
    """伪装成 328 房那台表（deviceId=2083），配置认定的是 317"""

    async def query_status(self):
        return {"returnCode": "80", "onCharge": "00", "tacTimes": "7D360000",
                "commMode": "10", "deviceID": "0823", "productId": "040C",
                "mac": "001583095B62"}

    async def send_and_wait(self, *a, **k):
        raise AssertionError("身份不符还发了指令")


def _boom(*a, **k):
    raise AssertionError("身份不符还下了单")


wv.CFG["last_dev_id"] = 317
wv.ble_rate_order = _boom
with contextlib.redirect_stdout(io.StringIO()):
    opened = asyncio.run(wv.ble_open(OtherMeter(), {"projectId": 1,
                                                   "loginCode": "x", "userId": 1,
                                                   "telephone": "1", "accountId": 1}))
check("deviceId 不符 → 开阀中止", opened is False)

print("\n[7] 断线保护：待办标记 + 掉线重连重发")

# 待办标记
wv.pending_mark(kind="test")
check("待办已落盘", bool(wv.pending_read()), str(wv.pending_read().get("kind")))
wv.pending_clear()
check("待办已清除", not wv.pending_read())


class FlakyValve:
    """第一次发送掉线，重连后成功"""

    def __init__(self):
        self.fail = 1
        self.connects = 0
        self.client = SimpleNamespace(is_connected=True)

    async def connect(self):
        self.connects += 1
        self.client.is_connected = True
        return True

    async def disconnect(self):
        self.client.is_connected = False

    async def send_and_wait(self, cmd, data="00", timeout=10):
        if self.fail:
            self.fail -= 1
            self.client.is_connected = False
            raise TimeoutError("模拟掉线")
        return {"data_area": "80", "raw": "80"}


fv = FlakyValve()
with contextlib.redirect_stdout(io.StringIO()):
    rr = asyncio.run(wv._send_rw(fv, "85", "00", tries=3))
check("掉线后重连重发成功", rr.get("data_area") == "80")
check("确实发生了重连", fv.connects >= 1, f"connect x{fv.connects}")

print("\n" + "-" * 66)
print("  说明：本次仿真只写本地 settle_log.json，没有联网、没有蓝牙。")
print("-" * 66)

print("\n" + "=" * 66)
if FAIL:
    print(f"  结果：{len(FAIL)} 项未通过 → {FAIL}")
    sys.exit(1)
print("  结果：全部通过 ✅")
print("=" * 66)
