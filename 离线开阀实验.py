# -*- coding: utf-8 -*-
"""离线开阀实验：重放历史 downData（不调用云端下单）。

安全设计：
  · 开阀前确认设备空闲、deviceId 是本机认定那块
  · 下发 21 后最多等 5 秒确认 onCharge=01；没出水就立刻收尾
  · 无论成败，finally 里都会执行「22 结束 → 85 采集 → 86 本地清账」
  · 清理失败会重试 3 次并把状态打出来

跑法： python 离线开阀实验.py
"""
import asyncio
import sys

sys.path.insert(0, ".")

import WaterValveBLE_v4 as wv   # noqa: E402

# 2026-10-20 那次正常在线开阀拿到的 downData（8B头+32B体+8B尾）
DOWN = ("C14C970DCDC40B22CC969FF14E8E63208F9AC6FA77FA09CA94BB892255415BDC"
        "254E027D630BFA772B8B2AEDA92403ED")


async def connect_retry(v, tries=4):
    for i in range(1, tries + 1):
        print(f"[*] 连接 {i}/{tries} ...")
        if await v.connect():
            return True
        await asyncio.sleep(2)
    return False


async def main():
    wv.load_config()
    s = wv.CFG.get("session")
    v = wv.WaterValveBLE()
    if not await connect_retry(v):
        print("[!] 连不上")
        return 1

    opened = False
    try:
        st = await v.query_status()
        did = int(st["deviceID"], 16)
        print(f"[*] 开阀前：deviceId={did} onCharge={st['onCharge']} "
              f"randomNumber={st['tacTimes']}")
        if wv.CFG.get("last_dev_id") and did != int(wv.CFG["last_dev_id"]):
            print("[!] 不是本机认定的表，中止")
            return 1
        if st["onCharge"] != "00":
            print("[!] 设备非空闲，先不实验")
            return 1

        print("[*] 清旧会话：85 采集")
        try:
            r85 = await v.send_and_wait("85", "00", timeout=8)
            print(f"    85 -> {wv.norm_hex(r85.get('data_area',''))[:48]}...")
        except TimeoutError:
            print("    85 无回包（继续）")

        await asyncio.sleep(1)

        print("[*] 下发 21 + 历史 downData（无云端订单）...")
        try:
            r21 = await v.send_and_wait("21", DOWN, timeout=8)
            print(f"[OK] 21 回包 dataArea={r21.get('data_area')}")
        except TimeoutError:
            print("[!] 21 无回包 —— 设备不接受重放")
            return 1

        for i in range(1, 6):
            await asyncio.sleep(1.2)
            st2 = await v.query_status()
            print(f"    第{i}次确认：onCharge={st2['onCharge']}")
            if st2["onCharge"] == "01":
                opened = True
                break

        if not opened:
            print("[!] 没有进入出水状态 —— 重放未被接受")
            return 1

        print("[OK] 离线开阀成功（全程没调用云端下单接口）")
        print("[*] 3 秒后自动关阀 ...")
        await asyncio.sleep(3)
    finally:
        # 不管上面发生什么，都要把阀门关掉并清账
        for attempt in (1, 2, 3):
            print(f"[*] 关阀/清账 第 {attempt} 次 ...")
            try:
                ok = await wv.ble_close(v, s, offline=True)
                if ok:
                    print("[OK] 已关阀并本地清账")
                    break
            except Exception as e:                   # noqa: BLE001
                print(f"[!] 关阀异常：{type(e).__name__}: {e}")
            await asyncio.sleep(2)
        try:
            st3 = await v.query_status()
            print(f"[*] 收尾：onCharge={st3['onCharge']}")
        except TimeoutError:
            print("[!] 收尾查询无回包")
        await v.disconnect()
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
