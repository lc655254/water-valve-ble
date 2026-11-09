# tools · 开发与排查脚本

这些脚本只在本地调试时用，不参与打包，也不影响日常使用。
仓库根目录保留的是**运行时源码**；这里放的是「一次性排查 / 实验 / 审计」工具。

每个脚本开头都有一行 `sys.path` 引导，所以**从仓库根目录**执行即可，例如：

```bash
python tools/查账本.py                 # 审计账本（默认看 tools/settle_log.json，可传路径）
python tools/查云端账单.py              # 查云端本月账单（只读）
python tools/probe.py                  # 真机只读联调：扫表 → 连表 → 23 查询 → 85 采集
python tools/bench.py                  # 启动性能实测
python tools/_selftest_live.py         # 对运行中的程序做黑盒测试（走 HTTP）
python tools/离线实验2.py               # 离线重放 23 → 21 顺序
python tools/离线开阀实验.py            # 离线重放历史 downData
```

| 文件 | 用途 |
|---|---|
| `bench.py` / `_bench_启动.py` | 启动耗时基准（起 waitress → 等本地 API 就绪） |
| `probe.py` / `实测_连表.py` | 真机只读联调：扫描、连接、查询状态、采集流水 |
| `查账本.py` | 把 `settle_log.json` 每笔结算列出来，重点标「真实用量上报」 |
| `查云端账单.py` | 云端消费 / 充值 / 退款逐笔列出（只读） |
| `离线实验2.py` | 离线按在线顺序重放（23 → 21） |
| `离线开阀实验.py` | 离线重放历史 `downData`，不调用云端下单 |
| `_selftest_live.py` | 对**正在运行**的程序走 HTTP 做黑盒测试 |

> 真机脚本需要 `bleak`（见根目录 `requirements.txt`）和一块在附近的 KLCXKJ 水表；
> 所有脚本都不改云端数据，除 `开关水.py` 外也不会真正开关阀。
