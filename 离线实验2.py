# -*- coding: utf-8 -*-
"""离线实验 2：严格照在线顺序（23 → 21），不先发 85。

依次试两个历史 downData；只要有一个被接受就会出水，脚本 3 秒后自动关阀清账。
"""
import asyncio
import sys

sys.path.insert(0, ".")

import WaterValveBLE_v4 as wv   # noqa: E402

BLOBS = [
    ("10-20 10:01 订单", "C14C970DCDC40B22CC969FF14E8E63208F9AC6FA77FA09CA94BB"
                        "892255415BDC254E027D630BFA772B8B2AEDA92403ED"),
    ("10-19 22:32 订单", "BEB39542A28596F2CC969FF14E8E63208F9AC6FA77FA09CA94BB"
                        "892255415BDC254E027D630BFA77F7E2768D872297DB"),
]


async def main():
    wv.load_config()
    s = wv.CFG.get("session")
    v = wv.WaterValveBLE()
    for i in range(4):
        if await v.connect():
            break
        await asyncio.sleep(2)
    else:
        print("[!] 连不上")
        return 1

    opened = False
    try:
        for label, blob in BLOBS:
            st = await v.query_status()
            if st["onCharge"] != "00":
                print(f"[*] 设备处于 onCharge={st['onCharge']}，先本地清一次 ...")
                await wv.ble_close(v, s, offline=True)
                st = await v.query_status()
            print(f"\n[*] {label}：当前 onCharge={st['onCharge']} "
                  f"randomNumber={st['tacTimes']}")
            if st["onCharge"] != "00":
                print("[!] 设备非空闲，先停止实验")
                return 1
            try:
                r = await v.send_and_wait("21", blob, timeout=8)
            except TimeoutError:
                print("[!] 21 无回包")
                continue
            rc = wv.norm_hex(r.get("data_area", ""))
            print(f"[*] 21 回包 dataArea={rc}")
            if rc.startswith("80"):
                print("[OK] 设备接受了历史 downData")
            for _ in range(4):
                await asyncio.sleep(1.2)
                st2 = await v.query_status()
                print(f"    onCharge={st2['onCharge']}")
                if st2["onCharge"] == "01":
                    opened = True
                    break
            if opened:
                break
            print("[!] 未出水，换下一个 blob")
        if not opened:
            print("\n[!] 结论：重放全部被拒，离线开阀在本固件不可行")
            return 1
        print("\n[OK] 离线开阀成功（未调用云端下单）—— 3 秒后关阀")
        await asyncio.sleep(3)
    finally:
        for attempt in (1, 2, 3):
            try:
                if await wv.ble_close(v, s, offline=True):
                    print("[OK] 已关阀并本地清账")
                    break
            except Exception as e:                   # noqa: BLE001
                print(f"[!] 关阀异常 {type(e).__name__}: {e}")
            await asyncio.sleep(2)
        try:
            st3 = await v.query_status()
            print(f"[*] 收尾 onCharge={st3['onCharge']}")
        except TimeoutError:
            pass
        await v.disconnect()
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
