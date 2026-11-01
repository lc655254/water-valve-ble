# -*- coding: utf-8 -*-
"""GitHub Release 自动更新：检测、镜像测速、下载、校验、静默安装。"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib3
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests

urllib3.disable_warnings()

REPO = "lc655254/water-valve-ble"
API = f"https://api.github.com/repos/{REPO}/releases/latest"
UA = "WaterValveBLE-Updater/1.0"
PROBE = 256 * 1024
CHUNK = 256 * 1024

MIRRORS = [
    ("GitHub 官方", ""),
    ("gh-proxy.com", "https://gh-proxy.com/"),
    ("ghproxy.net", "https://ghproxy.net/"),
    ("github.moeyy.xyz", "https://github.moeyy.xyz/"),
    ("ghfast.top", "https://ghfast.top/"),
    ("gh.llkk.cc", "https://gh.llkk.cc/"),
    ("ghproxy.cc", "https://ghproxy.cc/"),
    ("mirror.ghproxy.com", "https://mirror.ghproxy.com/"),
]


def ver_key(s):
    nums = [int(x) for x in re.findall(r"\d+", str(s or ""))][:3]
    while len(nums) < 3:
        nums.append(0)
    return tuple(nums)


def is_newer(latest, current):
    return ver_key(latest) > ver_key(current)


def _api_json():
    sys_curl = r"C:\Windows\System32\curl.exe"
    curl = sys_curl if os.path.exists(sys_curl) else shutil.which("curl")
    if curl and os.path.exists(curl):
        cmd = [curl, "-sS", "-L", "--max-time", "12",
               "-H", f"User-Agent: {UA}",
               "-H", "Accept: application/vnd.github+json"]
        if os.name == "nt":
            cmd.insert(3, "--ssl-no-revoke")
        cmd.append(API)
        # GUI 程序里跑 curl 必须隐藏控制台，否则会闪一下小黑框
        kwargs = {}
        if os.name == "nt":
            kwargs["creationflags"] = 0x08000000  # CREATE_NO_WINDOW
            si = subprocess.STARTUPINFO()
            si.dwFlags |= 0x00000001             # STARTF_USESHOWWINDOW
            si.wShowWindow = 0                   # SW_HIDE
            kwargs["startupinfo"] = si
        p = subprocess.run(cmd, capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=15,
                           **kwargs)
        if p.returncode == 0 and p.stdout.strip():
            return json.loads(p.stdout)
    r = requests.get(API, headers={"User-Agent": UA,
                                   "Accept": "application/vnd.github+json"},
                     timeout=12, verify=False)
    r.raise_for_status()
    return r.json()


def check_latest():
    d = _api_json()
    tag = str(d.get("tag_name") or "").strip()
    if not tag:
        raise RuntimeError("GitHub 返回里没有 tag_name")
    assets = d.get("assets") or []
    setup = None
    for a in assets:
        n = str(a.get("name") or "")
        if n.lower().endswith(".exe") and "setup" in n.lower():
            setup = a
            break
    if not setup:
        for a in assets:
            if str(a.get("name") or "").lower().endswith(".exe"):
                setup = a
                break
    if not setup:
        raise RuntimeError("Release 里没有 Setup 安装包")
    digest = str(setup.get("digest") or "").lower()
    if digest.startswith("sha256:"):
        digest = digest[7:]
    return {
        "tag": tag,
        "version": tag.lstrip("vV"),
        "notes": d.get("body") or "",
        "published": d.get("published_at") or "",
        "asset": str(setup.get("name") or ""),
        "size": int(setup.get("size") or 0),
        "digest": digest,
        "url": str(setup.get("browser_download_url") or ""),
    }


def _probe(label, prefix, asset_url):
    url = prefix + asset_url if prefix else asset_url
    t0 = time.perf_counter()
    n = 0
    code = 0
    try:
        with requests.get(url, headers={"User-Agent": UA,
                                        "Range": f"bytes=0-{PROBE - 1}"},
                          stream=True, timeout=(6, 10), verify=False) as r:
            code = r.status_code
            if code not in (200, 206):
                raise RuntimeError(f"HTTP {code}")
            for c in r.iter_content(16384):
                n += len(c)
                if n >= PROBE:
                    break
    except Exception as e:  # noqa: BLE001
        return {"label": label, "url": url, "ok": False, "speed": 0.0,
                "bytes": n, "seconds": 0.0,
                "error": f"{type(e).__name__}: {e}"}
    dt = max(0.001, time.perf_counter() - t0)
    ok = n >= 1024
    return {"label": label, "url": url, "ok": ok,
            "speed": (n / dt / 1024.0) if ok else 0.0,
            "bytes": n, "seconds": dt,
            "error": "" if ok else f"HTTP {code}，仅 {n} 字节"}


def test_mirrors(asset_url, workers=8):
    if not asset_url:
        raise RuntimeError("没有下载地址")
    results = []
    with ThreadPoolExecutor(max_workers=min(workers, len(MIRRORS))) as ex:
        futs = [ex.submit(_probe, label, prefix, asset_url)
                for label, prefix in MIRRORS]
        for f in as_completed(futs):
            results.append(f.result())
    results.sort(key=lambda x: (not x["ok"], -x["speed"]))
    if not any(r["ok"] for r in results):
        msg = "；".join(f"{r['label']}={r['error']}" for r in results[:4])
        raise RuntimeError("所有镜像测速失败：" + msg)
    return results


def download(url, dest, expected_sha, total_hint=0, progress=None):
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    free = shutil.disk_usage(os.path.dirname(dest) or ".").free
    if total_hint and free < total_hint * 1.2:
        raise RuntimeError("磁盘空间不足")
    tmp = dest + ".part"
    h = hashlib.sha256()
    done = 0
    t0 = time.perf_counter()
    try:
        with requests.get(url, headers={"User-Agent": UA}, stream=True,
                          timeout=(10, 30), verify=False) as r:
            r.raise_for_status()
            total = int(r.headers.get("Content-Length") or total_hint or 0)
            with open(tmp, "wb") as f:
                for chunk in r.iter_content(CHUNK):
                    if not chunk:
                        continue
                    f.write(chunk)
                    h.update(chunk)
                    done += len(chunk)
                    if progress:
                        progress(done, total, done / max(0.001, time.perf_counter() - t0) / 1024.0)
        if done <= 0:
            raise RuntimeError("下载得到 0 字节")
        got = h.hexdigest().lower()
        if expected_sha and got != expected_sha.lower():
            raise RuntimeError(f"SHA256 不匹配（得到 {got[:12]}…）")
        os.replace(tmp, dest)
        return dest
    except Exception:
        try:
            os.remove(tmp)
        except Exception:
            pass
        raise


def download_best(asset, dest, on_progress=None, on_mirrors=None):
    if not asset.get("digest"):
        raise RuntimeError("Release 缺少 SHA256，拒绝自动更新")
    results = test_mirrors(asset.get("url") or "")
    if on_mirrors:
        on_mirrors(results)
    errors = []
    for r in results:
        if not r.get("ok"):
            continue
        try:
            return download(r["url"], dest, asset.get("digest"),
                            asset.get("size") or 0,
                            (lambda done, total, speed, label=r["label"]:
                             on_progress(done, total, speed, label)) if on_progress else None)
        except Exception as e:  # noqa: BLE001
            errors.append(f"{r['label']}: {type(e).__name__}: {e}")
    raise RuntimeError("镜像下载失败：" + "；".join(errors[-4:]))


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(CHUNK), b""):
            h.update(chunk)
    return h.hexdigest().lower()


def update_dir():
    p = os.path.join(tempfile.gettempdir(), "WaterValveUpdate")
    os.makedirs(p, exist_ok=True)
    return p


def make_install_script(installer, app_dir, app_exe, script=None):
    """用 UTF-8 BOM 的 PowerShell 脚本执行安装并重启（中文路径不受 cmd 代码页影响）。"""
    ps1 = os.path.join(update_dir(), f"apply_{int(time.time())}.ps1")

    def q(s):
        return "'" + str(s).replace("'", "''") + "'"

    if script:
        start_app = (f"Start-Process -FilePath {q(app_exe)} "
                     f"-ArgumentList {q(chr(34) + str(script) + chr(34))} "
                     f"-WorkingDirectory {q(app_dir)}")
    else:
        start_app = (f"Start-Process -FilePath {q(app_exe)} "
                     f"-WorkingDirectory {q(app_dir)}")
    text = (
        "$ErrorActionPreference = 'SilentlyContinue'\r\n"
        "Start-Sleep -Seconds 2\r\n"
        "Get-Process -Name '蓝牙水库安全版' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue\r\n"
        f"$installer = {q(installer)}\r\n"
        f"$dir = {q(app_dir)}\r\n"
        "$argLine = '/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP- /DIR=\"' + $dir + '\"'\r\n"
        "Start-Process -FilePath $installer -ArgumentList $argLine -Wait\r\n"
        "Start-Sleep -Milliseconds 800\r\n"
        + start_app + "\r\n"
    )
    with open(ps1, "w", encoding="utf-8-sig", newline="") as f:
        f.write(text)
    ps = shutil.which("powershell.exe") or \
        r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) | \
        getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    subprocess.Popen([ps, "-NoLogo", "-NoProfile", "-NonInteractive",
                      "-ExecutionPolicy", "Bypass", "-File", ps1],
                     cwd=app_dir, creationflags=flags, close_fds=True,
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL)
    return ps1
