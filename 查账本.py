# -*- coding: utf-8 -*-
"""账本审计：把 settle_log.json 里的每一笔结算列出来，重点标出「真实用量上报」的那几笔。

用法：
    python 查账本.py                # 看本目录的 settle_log.json
    python 查账本.py "D:\\WaterValveBLE\\settle_log.json"
    python 查账本.py --all          # 连零用量的一起列
"""
import datetime
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from WaterValveBLE_v4 import parse_flow   # noqa: E402

args = [a for a in sys.argv[1:] if not a.startswith("--")]
show_all = "--all" in sys.argv
path = args[0] if args else os.path.join(HERE, "settle_log.json")

if not os.path.exists(path):
    print(f"找不到账本：{path}")
    sys.exit(1)

with open(path, encoding="utf-8") as f:
    data = json.load(f) or []

print(f"账本：{path}   共 {len(data)} 笔\n")
print(f"{'结算时间':<20}{'流水时间':<16}{'次数':>4}{'预扣':>7}{'rate1':>7}"
      f"{'零用量':>7}  结论")
print("-" * 88)

real = 0
for e in data:
    t = e.get("ts") or 0
    when = datetime.datetime.fromtimestamp(t).strftime("%Y-%m-%d %H:%M:%S")
    # 账本里存的 collect 已剥掉 status，补回 80 才能按完整布局解析
    f = parse_flow("80" + (e.get("collect") or ""))
    ftime = f.get("time", "")
    if len(ftime) == 12:
        ftime = (f"20{ftime[0:2]}-{ftime[2:4]}-{ftime[4:6]} "
                 f"{ftime[6:8]}:{ftime[8:10]}:{ftime[10:12]}")
    else:
        ftime = "-"
    zero = bool(e.get("zero_usage"))
    if not zero:
        real += 1
    if not show_all and zero:
        continue
    uc = f.get("useCount_i")
    pre = f.get("prepay_i")
    r1 = f.get("rate1_i")
    if zero:
        verdict = "✅ 零用量，计费 0"
    else:
        amt = f"约 {r1 / 1000:.3f} 元" if isinstance(r1, int) else "金额未知"
        verdict = f"⚠ 真实用量上报（rate1={r1} → {amt}）"
    print(f"{when:<20}{ftime:<16}{(uc if uc is not None else '-'):>4}"
          f"{(pre if pre is not None else '-'):>7}{(r1 if r1 is not None else '-'):>7}"
          f"{'是' if zero else '否':>7}  {verdict}")

print("-" * 88)
print(f"真实用量结算：{real} 笔 / 共 {len(data)} 笔")
if real == 0:
    print("结论：没有任何一笔按真实用量上报 → 不会产生扣费 ✅")
else:
    print("结论：上面标 ⚠ 的就是会产生账单的结算；修复后应恒为 0。")
