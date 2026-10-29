# -*- coding: utf-8 -*-
"""启动性能实测：起真实 HTTP 服务（waitress）→ 等端口 → 打 /api/state 与首页。

不碰蓝牙、不登录、不联网。跑法： python _bench_启动.py
"""
import os
import socket
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

PORT = 18899
T0 = time.perf_counter()

import WaterValveBLE_Web as web

t_import = time.perf_counter() - T0


def serve():
    from waitress import serve
    serve(web.app, host="127.0.0.1", port=PORT, threads=8, _quiet=True)


threading.Thread(target=serve, daemon=True).start()

ready = None
while time.perf_counter() - T0 < 15:
    try:
        with socket.create_connection(("127.0.0.1", PORT), 0.2):
            ready = time.perf_counter() - T0
            break
    except OSError:
        time.sleep(0.02)

if ready is None:
    print("[FAIL] 服务 15 秒内没起来")
    sys.exit(1)

import requests

t1 = time.perf_counter()
st = requests.get(f"http://127.0.0.1:{PORT}/api/state", timeout=5).json()
t_state = (time.perf_counter() - t1) * 1000

t2 = time.perf_counter()
page = requests.get(f"http://127.0.0.1:{PORT}/", timeout=10)
t_page = (time.perf_counter() - t2) * 1000

print(f"模块导入        : {t_import * 1000:7.0f} ms")
print(f"HTTP 就绪       : {ready * 1000:7.0f} ms")
print(f"/api/state      : {t_state:7.1f} ms  ok={st.get('ok')} 免激活={not st.get('need_activation')} license={st.get('license')}")
print(f"首页 index.html : {t_page:7.1f} ms  HTTP {page.status_code}  {len(page.content) // 1024} KB")
print(f"进程常驻内存    : 见任务管理器（无 WebView2 时约 90-130 MB）")

ok = (st.get("ok") is True and st.get("need_activation") is False
      and page.status_code == 200 and len(page.content) > 50000)
print("\n结论：" + ("启动链路正常 ✅" if ok else "有问题 ❌"))
sys.exit(0 if ok else 1)
