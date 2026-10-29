# -*- coding: utf-8 -*-
"""查云端本月账单（只读）：消费 / 充值 / 退款逐笔列出。

用法： python 查云端账单.py            # 本月
       python 查云端账单.py 2026-09    # 指定月份
"""
import sys
import time

sys.path.insert(0, ".")

import WaterValveBLE_v4 as wv   # noqa: E402

wv.load_config()
s = wv.CFG.get("session")
if not s:
    print("还没登录：先在程序界面用短信验证码登一次")
    sys.exit(1)

month = sys.argv[1] if len(sys.argv) > 1 else time.strftime("%Y-%m", time.localtime())

rows = []
seen = set()
for page in range(1, 6):
    d = wv.api("GET", "/order/query/account/bill/list", {
        "telephone": s["telephone"], "curNum": page, "billRequestType": "0",
        "month": month, "loginCode": s["loginCode"], "projectId": s["projectId"],
        "userId": s["userId"], "accountId": s["accountId"]})
    if not d or d.get("errorCode") != 0:
        print(f"查询失败：{(d or {}).get('errorMessage')}")
        sys.exit(1)
    data = d.get("data") or []
    if not data:
        break
    added = 0
    for b in data:
        c = b.get("consumeBillDTO") or {}
        r = b.get("rechargeBillDTO") or {}
        f = b.get("refundBillDTO") or {}
        key = (b.get("billType"), c.get("orderId") or r.get("dealDate")
               or f.get("dealDate") or b.get("sortTimestamp"))
        if key in seen:
            continue
        seen.add(key)
        rows.append(b)
        added += 1
    if not added:          # 服务端对 curNum 不敏感时，翻页会重复返回同一批
        break

print(f"{month} 账单 {len(rows)} 笔\n")
total = 0.0
for b in rows:
    bt = b.get("billType")
    if bt == 2:
        c = b.get("consumeBillDTO") or {}
        amt = float(c.get("consumeMoney") or 0)
        total += amt
        print(f"消费  {c.get('consumeDate')}  {amt:>7.3f} 元  "
              f"订单{c.get('orderId')}  上传 {c.get('uploadDate')}")
        print(f"      {(c.get('description') or '')[:60]}")
    elif bt == 1:
        r = b.get("rechargeBillDTO") or {}
        print(f"充值  {r.get('dealDate')}  {float(r.get('dealMoney') or 0):>7.3f} 元  {r.get('typeName')}")
    elif bt == 3:
        f = b.get("refundBillDTO") or {}
        print(f"退款  {f.get('dealDate')}  {float(f.get('dealMoney') or 0):>7.3f} 元  {f.get('typeName')}")

print(f"\n本月消费合计：{total:.3f} 元")
