# -*- coding: utf-8 -*-
"""打便携版 zip：自动排除本地账号/会话/日志，避免把 config.json 一起发出去。

用法：python tools/pack_portable.py <dist\\蓝牙水库安全版> <输出.zip>
"""
import os
import sys
import zipfile

# 这些是「本机数据」，绝不能进发布包
DENY_NAMES = {
    "config.json", "config.local.json", "ui_prefs.json", "device_alias.json",
    "settle_log.json", "usage_stats.json", "bill_seen.json",
    "cloud_order_info.json", "last_key.json", "license.json",
    "valve_session.json", "valve_heartbeat", "valve_guard.pid",
}
DENY_SUFFIX = (".log", ".pid")


def keep(rel):
    name = os.path.basename(rel).lower()
    if name in DENY_NAMES:
        return False
    if name.endswith(DENY_SUFFIX):
        return False
    return True


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    if not os.path.isdir(src):
        print("目录不存在:", src)
        return 2
    if os.path.exists(dst):
        os.remove(dst)
    skipped, count = [], 0
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _dirs, files in os.walk(src):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, src)
                if not keep(rel):
                    skipped.append(rel)
                    continue
                z.write(full, rel)
                count += 1
    print(f"打包 {count} 个文件 -> {dst}")
    for s in skipped:
        print("  跳过本机数据:", s)
    return 0


if __name__ == "__main__":
    sys.exit(main())
