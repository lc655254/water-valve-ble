# -*- coding: utf-8 -*-
"""真机只读联调：扫表 → 多轮直连 → 23 查询 → 85 采集 → 断开。

★ 只读：不发 22/86、不开阀、不上传云端，不会出水、不会产生账单。
★ 设备复位换地址也能找到（按厂商前缀 C0:15:83 + 名称）。

用法： python 实测_连表.py
"""
import asyncio
import sys
import time
from types import SimpleNamespace

sys.path.insert(0, ".")

from bleak import BleakClient, BleakScanner   # noqa: E402

import WaterValveBLE_v4 as wv                # noqa: E402


async def discover(rounds=3, timeout=15.0):
    """水表广播稀疏：单轮常整轮扫空，多轮累积去重。"""
    best = {}
    total = 0
    for rnd in range(1, rounds + 1):
        found = await BleakScanner.discover(timeout=timeout, return_adv=True)
        total = max(total, len(found))
        for addr, (d, adv) in found.items():
            name = (d.name or "").strip()
            up = name.upper()
            if not ("KLCXKJ" in up or "WATER" in up
                    or addr.upper().startswith("C0:15:83")):
                continue
            rssi = getattr(adv, "rssi", -999)
            key = addr.upper()
            if key not in best or rssi > best[key][0]:
                best[key] = (rssi, key, name)
        print(f"     第 {rnd}/{rounds} 轮：{len(found)} 台设备，"
              f"累计命中 {len(best)} 台疑似水表")
        if best:
            break
    cands = sorted(best.values(), reverse=True)
    return cands, total


async def main():
    wv.load_config()
    print(f"配置：账号 {wv.CFG.get('active_phone')} / 目标 {wv.CFG.get('mac')}")
    print("[*] 扫描 3 轮 × 15 秒找表（广播稀疏，单轮常扫空）...")
    t0 = time.perf_counter()
    cands, total = await discover(3, 15.0)
    print(f"[OK] {len(cands)} 台疑似水表 / 单轮最多 {total} 台设备，"
          f"扫描 {time.perf_counter()-t0:.1f}s")
    if not cands:
        print("[!] 没听到水表广播：靠近到 1~2 米，关掉手机蓝牙再试")
        return 1
    for rssi, addr, name in cands:
        print(f"     {rssi:>4} dBm  {addr}  {name or '(未命名)'}")

    # ★ 只连配置里那块表：同栋楼一层好几块同型号表，按信号强弱挑会挑到隔壁
    target = (wv.CFG.get("mac") or "").upper()
    mine = [c for c in cands if c[1] == target]
    if not mine:
        others = ", ".join(f"{a}({r}dBm)" for r, a, _ in cands[:4])
        print(f"\n[!] 没听到你的表 {target}")
        print(f"    只听到同厂商其它表：{others or '无'} —— 不连接，避免动到别人的阀门")
        print("    靠近到 1 米内、关掉手机蓝牙再试")
        return 1
    cands = mine
    addr, name = cands[0][1], (cands[0][2] or "KLCXKJ-Water")
    print(f"\n[*] 目标 {addr}（{cands[0][0]} dBm），开始多轮直连 ...")

    for attempt in range(1, 7):
        t = time.perf_counter()
        try:
            client = BleakClient(addr, timeout=20)
            await client.connect()
        except Exception as e:
            print(f"     第 {attempt}/6 次失败 {time.perf_counter()-t:.1f}s："
                  f"{type(e).__name__}: {str(e)[:70]}")
            await asyncio.sleep(1.5)
            continue

        print(f"[OK] 已连接（第 {attempt} 次，{time.perf_counter()-t:.1f}s）")
        valve = wv.WaterValveBLE(mac=addr, name=name)
        valve.client = client
        valve.connected = True
        ok = await valve._finish_connect(SimpleNamespace(address=addr, name=name))
        if not ok:
            print("[!] 连上了但没找到可写特征，断开")
            await valve.disconnect()
            return 1

        try:
            t = time.perf_counter()
            st = await valve.query_status()
            print(f"[OK] 23 查询 {1000*(time.perf_counter()-t):.0f} ms："
                  f"onCharge={st['onCharge']} randomNumber={st['tacTimes']} "
                  f"commMode={st['commMode']} MAC={st.get('mac')}")

            t = time.perf_counter()
            resp = await valve.send_and_wait("85", "00", timeout=10)
            da = resp.get("data_area", "")
            rc, txt = wv.rc_of(da)
            print(f"[OK] 85 采集 {1000*(time.perf_counter()-t):.0f} ms：返回码 {rc}（{txt}）")
            if rc == "80":
                f = wv.parse_flow(da)
                print(f"     型号 {wv.flow_model_tag(f)}  {wv.flow_brief(f)}")
                z = wv.zero_usage_from(da)
                print(f"     关水时会报零用量：{len(z)//2}B，"
                      f"长度对齐={'是' if len(z) == len(da) else '否'}，"
                      f"useCount={wv.parse_flow(z)['useCount_i']}")
        except TimeoutError as e:
            print(f"[!] 设备无回包：{e}")
        finally:
            await valve.disconnect()
            print("[*] 已断开（全程只读：未开阀、未清账、未上传）")
            print(f"[*] 配置里的目标地址已更新为 {wv.CFG.get('mac')}")
        return 0

    print("\n[!] 6 轮都没连上。按概率排查：")
    print("    ① 信号太弱：能扫到 -88dBm 不等于能连上，贴近到 1 米内（连接要求约 -80dBm 以上）")
    print("    ② 手机水控 App / 趣智校园正连着这块表 —— 退出 App、关掉手机蓝牙")
    print("    ③ 表处于占用/结算中 —— 等 1 分钟再试")
    return 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
