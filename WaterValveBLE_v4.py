# -*- coding: utf-8 -*-
"""
WaterValveBLE v4.6 — 趣智校园热水表 双通道控制器
=========================================================
两种控制方式，菜单 [6] 二选一随时切换：
  A) 互联网方式（TCP 云端）：无需蓝牙，走 4G/TCP 云端接口
     开阀: rateOrder(xfModel=1, 预扣5元) -> 轮询 downRateResult
     关阀: closeOrder -> 轮询 closeOrder/result/query
  B) 蓝牙方式（BLE+云端）：
     开阀: 23 查询 -> rateOrder(签名) -> 21+downData -> 轮询 onCharge=01
     关阀: 22 结束 -> 85 采集 -> 上传 -> AES解密 -> 86 清账
设备   : 不内置默认设备，首次运行请用菜单 [4] 搜索选择（扫描按名称
         KLCXKJ-Water 过滤，只列出疑似水表。仅作过滤，不自动连接）
协议   : 帧 #60+LEN+80+CMD+00+DATA+CS+16（解析已修复官方切片 bug）
交互   : 1=开阀 2=关阀 3=查询 4=搜索蓝牙 5=修改账号 6=切换方式
         7=我的账户 8=本月账单 0=退出
配置   : config.json（账号/密码/目标设备/控制方式，明文存储）
v4.8   : 连接提速：已知 MAC 时跳过扫描直连（Windows 系统设备库查询 ~50ms
         + GATT 服务缓存，实测 0.1~5 秒连上）；设备不在系统库时自动做一次
         系统级搜索入库；扫描仅在地址未知 / 设备换址时兜底。
v3.5   : 修复他人设备"云端查设备失败"：按名称连上设备但 MAC 未保存导致云端
         查询用错设备；现连接后自动认领实际 MAC 并保存，开阀时云端查询失败
         改用 BLE 23 帧内 deviceId/MAC 兜底；登录用户名为空不再显示 None
v3.4   : 修复发码接口"签名错误"：secret 原写死为开发者手机号的值，导致他人
         发码失败；现改为动态计算 MD5(手机号前3位+后4位+klcx)（小程序源码提取）
v3.3   : 费率改为云端获取：开阀下单时读取云端 rateOrder 返回的 rate 并缓存到
         config（每人费率不同也适配）；横幅/开阀显示云端费率，未取到时兜底 0.036
v3.2   : 移除会员状态（VIP验证）菜单（需 adb 读手机微信存储，已无实际用途）；
         新增短信验证码登录（登录方式可选 密码 / 验证码，官方接口）
v3.1   : 新增账户信息/账单查询/会员状态（对齐微信"蓝牙水控"功能）
"""
import asyncio
import base64
import hashlib
import json
import os
import sys
import time
import traceback

import requests
from Crypto.Cipher import AES

# ---------------- 调试开关 ----------------
# 设置环境变量 WV_DEBUG=1（或 --debug 参数）即可打印完整异常堆栈、原始请求等
DEBUG = (os.environ.get("WV_DEBUG", "").strip().lower() in ("1", "true", "yes", "on")
         or "--debug" in sys.argv)

# ---------------- 计费安全开关（自用测试版） ----------------
# 默认：任何结算都强制走「零用量上报」——设备端流水照常采集、订单照常了结、
# 押金照常退回，但上报给云端的用量/预扣是 0，因此不会产生账单。
# 只有在明确要看真实账单时，才设 WV_ALLOW_REAL_BILL=1 放行真实用量上报。
ALLOW_REAL_BILL = (os.environ.get("WV_ALLOW_REAL_BILL", "0").strip().lower()
                   in ("1", "true", "yes", "on"))

# ---------------- Windows 控制台（UTF-8 + ANSI 颜色） ----------------
if sys.platform == "win32":
    try:
        os.system("chcp 65001 >nul 2>&1")
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stdin.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

from bleak import BleakClient, BleakScanner

# ---------------- 云端常量 ----------------
BASE = "https://v3-api.china-qzxy.cn"
HEADERS = {"User-Agent": "okhttp/4.2.2"}
SIGN_KEY = "sign-kailu-855c74a88b8b494187c99b08b8c9a744"   # 凯路固定密钥（源码提取）
AES_KEY = b"20210118klcx@002"                               # clData 解密密钥（源码提取）
BIG_TYPE_ID = 4
SMALL_TYPE_ID = 1


def sms_secret(phone):
    """发码接口 secret：MD5(手机号前3位+后4位+klcx)，随手机号动态变化（小程序源码提取）"""
    return hashlib.md5((phone[:3] + phone[7:] + "klcx").encode()).hexdigest()


def fmt_mac_hex(hexmac):
    """设备帧内 MAC（如 AABBCCDDEEFF）转标准格式 AA:BB:CC:DD:EE:FF"""
    h = (hexmac or "").strip().upper()
    if len(h) != 12 or not all(c in "0123456789ABCDEF" for c in h):
        return hexmac
    return ":".join(h[i:i + 2] for i in range(0, 12, 2))

# ---------------- BLE 常量（不内置默认设备，避免误连他人水表） ----------------
DEFAULT_NAME = "KLCXKJ-Water"   # 仅作设备名过滤关键字：扫描时优先只显示名称含此字符串的设备
# 水表 MAC 前 3 字节固定（厂商 OUI），后 3 字节在设备复位后会重新生成 ——
# 实测同厂商另一块表与本机水表只差后 3 字节。把完整 MAC 当唯一身份，
# 设备复位一次就再也找不到；但把厂商前缀写死又会把设备信息带进发布包，
# 所以改为从已保存的 MAC 现算（见 _oui_prefixes）。
ADV_SVC_HINT = ("fee7", "ff00")   # 广播里的服务特征（实测本型号广播带 FEE7，不是 FF00）
SCAN_TIMEOUT = 15                 # 默认扫描时长：弱信号水表 8 秒窗口常整轮扫空
SERVICE_UUID = "ff00"
WRITE_CHAR = "ff02"
NOTIFY_CHAR = "ff01"
CONNECT_TIMEOUT = 12.0
RX_BUFFER_MAX = 512

# BLE 写入分片：默认 MTU 23，扣除 3 字节 ATT 头后单次最多 20 字节。
# 不主动 requestMtu —— 部分国产模块对 MTU 协商不回包，会把 GATT 队列永久卡死；
# 86 清账帧最长可达 40+ 字节，因此按片发送而非整帧写。
WRITE_CHUNK = 20
CHUNK_DELAY = 0.15          # 片间间隔；Java 版实测 150ms 最稳，过快对端会丢片
CHUNK_RETRY = 3             # 单片写入失败重试次数
CHUNK_RETRY_DELAY = 0.4     # 单片重试间隔
# 发送失败后自动重连的次数（应对 GATT 队列偶发卡死）
WRITE_RETRY = 1
DEVICE_KEYWORDS = ("WATER", "YH", "KLCXKJ", "QZXY", "水控")

# ---------------- 运行配置（不硬编码账号与设备） ----------------
# V4 多账号结构：
#   accounts     —— {手机号: {"session": {...}, "pwd": "", "last_used": ts}}
#   active_phone —— 当前使用的手机号
# 顶层 phone/pwd/session 保留为「当前账号的视图」，兼容原有调用点；
# 唯一数据源是 accounts，save_config() 会把视图回写进去，不会丢。
CFG = {"version": 4, "phone": "", "pwd": "", "mac": "", "name": "",
       "mode": "ble", "rate": None,   # rate: 云端费率（元/升），开阀时自动刷新
       "accounts": {}, "active_phone": "",
       # profiles: {设备key: {model, flow_len, clear_by, ...}} —— 型号自适应记忆
       "profiles": {}, "last_dev_id": None,
       # 上传订单参数：last_rn_pre=开阀前查询值（下单用）、last_rn=开阀后设备回的新值（上传用）
       "last_rn": "", "last_rn_pre": "", "last_cm": ""}
# 默认走蓝牙方式（互联网/TCP 方式实测可用性差，如需可切回 "tcp"）
MODE_NAMES = {"tcp": "互联网方式（云端 TCP）", "ble": "蓝牙方式（BLE+云端）"}
DEFAULT_RATE = 0.036   # 兜底费率（元/升）：云端未取到时使用


def _oui_prefixes():
    """从已保存的 MAC 推导厂商 OUI 前缀（前 3 字节），用于抵抗设备复位换址。

    不把前缀写死在代码里，发布包因此不含任何具体设备地址；
    没选过设备时返回空元组，所有 OUI 相关兜底自动跳过。
    """
    mac = (CFG.get("mac") or "").replace("-", ":").strip().upper()
    parts = [p for p in mac.split(":") if p]
    return (":".join(parts[:3]),) if len(parts) >= 3 else ()


def _oui_text():
    """OUI 前缀的展示文本，空时给出说明"""
    p = _oui_prefixes()
    return "/".join(p) if p else "（未选择设备）"


def fmt_rate():
    """当前费率展示文本：优先云端已取到的值"""
    r = CFG.get("rate")
    return f"{r:.3f}（云端）" if r else f"{DEFAULT_RATE:.3f}（默认，云端待刷新）"


def update_cloud_rate(rate_li):
    """记录云端费率（厘 → 元/升）并持久化，返回元/升数值或 None"""
    try:
        r = float(rate_li) / 1000.0
        if r > 0:
            CFG["rate"] = round(r, 4)
            save_config()
            return CFG["rate"]
    except (TypeError, ValueError):
        pass
    return None


_DATA_DIR = [None]


def _data_dir():
    """运行数据目录：exe / 源码目录（结果缓存，只探测一次）。

    安装到 Program Files 等只读位置时，自动回退到 %LOCALAPPDATA%\\蓝牙水库，
    避免 config.json / settle_log.json 写不进去导致"保存失败"。
    """
    if _DATA_DIR[0]:
        return _DATA_DIR[0]
    if getattr(sys, "frozen", False):
        base = os.path.dirname(sys.executable)
    else:
        base = os.path.dirname(os.path.abspath(__file__))
    probe = os.path.join(base, ".wv_write_test")
    try:
        with open(probe, "w") as f:
            f.write("1")
        os.remove(probe)
        _DATA_DIR[0] = base
    except Exception:
        alt = os.path.join(os.environ.get("LOCALAPPDATA") or base, "蓝牙水库")
        try:
            os.makedirs(alt, exist_ok=True)
        except Exception:
            pass
        _DATA_DIR[0] = alt
    return _DATA_DIR[0]


def config_path():
    return os.path.join(_data_dir(), "config.json")


def _sync_active_view():
    """把当前账号的会话/密码投影到顶层 phone/pwd/session（供旧调用点读取）"""
    p = CFG.get("active_phone") or ""
    acct = (CFG.get("accounts") or {}).get(p) or {}
    CFG["phone"] = p
    CFG["pwd"] = acct.get("pwd", "")
    CFG["session"] = acct.get("session")


def set_active_account(phone):
    """切换当前账号（只改指针 + 刷新视图）"""
    if phone not in (CFG.get("accounts") or {}):
        CFG.setdefault("accounts", {})[phone] = {"session": None, "pwd": ""}
    CFG["active_phone"] = phone
    _sync_active_view()
    save_config()


def set_active_session(s, phone=None):
    """写入当前账号的会话（唯一数据源 accounts），并刷新视图 + 落盘"""
    p = phone or (s or {}).get("telephone") or CFG.get("active_phone") or ""
    if not p:
        return
    CFG["active_phone"] = p
    acct = CFG.setdefault("accounts", {}).setdefault(p, {})
    acct["session"] = s if isinstance(s, dict) else None
    acct["last_used"] = time.time()
    _sync_active_view()
    save_config()


def list_accounts():
    """返回 [(手机号, 会话, 是否当前)]，按最近使用排序"""
    items = []
    for phone, acct in (CFG.get("accounts") or {}).items():
        items.append((phone, acct.get("session") or {},
                      phone == CFG.get("active_phone")))
    items.sort(key=lambda x: -(CFG["accounts"][x[0]].get("last_used") or 0))
    return items


def drop_account(phone):
    """删除一个账号"""
    acct = CFG.get("accounts") or {}
    if phone in acct:
        del acct[phone]
        if CFG.get("active_phone") == phone:
            CFG["active_phone"] = next(iter(acct), "")
        _sync_active_view()
        save_config()
        return True
    return False


def load_config():
    """加载配置；自动把 v3 的单账号结构迁移为 v4 多账号结构"""
    try:
        with open(config_path(), encoding="utf-8") as f:
            c = json.load(f)
    except Exception:
        return False

    CFG["mac"] = c.get("mac", "") or ""
    CFG["name"] = c.get("name", "") or ""
    CFG["mode"] = c.get("mode", "ble")
    if CFG["mode"] not in ("tcp", "ble"):
        CFG["mode"] = "ble"
    CFG["rate"] = c.get("rate") if isinstance(c.get("rate"), (int, float)) else None
    for k in ("last_open_mode", "last_rn", "last_rn_pre", "last_cm", "last_dev_id"):
        if k in c:
            CFG[k] = c[k]
    profs = c.get("profiles") if isinstance(c.get("profiles"), dict) else {}
    CFG["profiles"] = {k: v for k, v in profs.items() if isinstance(v, dict)}

    accounts = c.get("accounts") if isinstance(c.get("accounts"), dict) else {}
    accounts = {k: v for k, v in accounts.items() if isinstance(v, dict)}
    if not accounts and c.get("phone"):
        # v3 单账号 → v4 多账号迁移
        accounts[c["phone"]] = {
            "session": c.get("session") if isinstance(c.get("session"), dict) else None,
            "pwd": c.get("pwd", ""),
            "last_used": time.time(),
        }
        print(f"  {C.info('[*]')} 已把旧配置账号 {c['phone']} 迁移到多账号结构")
    CFG["accounts"] = accounts

    active = c.get("active_phone") or c.get("phone") or ""
    if active not in accounts and accounts:
        active = next(iter(accounts))
    CFG["active_phone"] = active
    _sync_active_view()
    return True


def save_config():
    """保存配置（v4 多账号）。顶层 session 会回写到当前账号，避免丢失。"""
    try:
        p = CFG.get("active_phone") or CFG.get("phone") or ""
        if p:
            acct = CFG.setdefault("accounts", {}).setdefault(p, {})
            if isinstance(CFG.get("session"), dict):
                acct["session"] = CFG["session"]
            if CFG.get("pwd"):
                acct["pwd"] = CFG["pwd"]
            acct.setdefault("last_used", time.time())
        out = {
            "version": 4,
            "active_phone": CFG.get("active_phone", ""),
            "accounts": CFG.get("accounts", {}),
            "mac": CFG.get("mac", ""),
            "name": CFG.get("name", ""),
            "mode": CFG.get("mode", "ble"),
            "rate": CFG.get("rate"),
            "last_open_mode": CFG.get("last_open_mode", ""),
            "last_rn": CFG.get("last_rn", ""),
            "last_rn_pre": CFG.get("last_rn_pre", ""),
            "last_cm": CFG.get("last_cm", ""),
            "last_dev_id": CFG.get("last_dev_id"),
            "profiles": CFG.get("profiles", {}),
        }
        with open(config_path(), "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=2)
        return True
    except Exception:
        return False


# ---------------- 控制台颜色 ----------------
class C:
    _on = False
    if sys.platform == "win32":
        try:
            import ctypes
            k = ctypes.windll.kernel32
            k.SetConsoleMode(k.GetStdHandle(-11), 7)
            _on = True
        except Exception:
            _on = False
    else:
        _on = True

    @staticmethod
    def paint(text, code):
        if C._on:
            return f"\033[{code}m{text}\033[0m"
        return text

    @classmethod
    def ok(cls, t): return cls.paint(t, "32;1")
    @classmethod
    def err(cls, t): return cls.paint(t, "31;1")
    @classmethod
    def warn(cls, t): return cls.paint(t, "33;1")
    @classmethod
    def info(cls, t): return cls.paint(t, "36")
    @classmethod
    def bold(cls, t): return cls.paint(t, "1")


def show_exc(e, tag="错误"):
    """统一的异常输出：调试模式打印完整堆栈，否则只打印一行"""
    if DEBUG:
        print(f"  {C.err('[!]')} {tag}（调试模式，完整堆栈）：")
        traceback.print_exc()
    else:
        print(f"  {C.err('[!]')} {tag}：{e}")


def dbg(*args):
    """调试模式下才输出的日志"""
    if DEBUG:
        print(f"  {C.info('[DBG]')}", *args)


# ================= 云端工具 =================
def md5(s):
    return hashlib.md5(s.encode()).hexdigest()


def md5_upper10(s):
    return hashlib.md5(s.encode()).hexdigest().upper()[-10:]


def api(method, path, params=None, retries=2):
    url = BASE + path
    for i in range(retries + 1):
        try:
            if method == "GET":
                r = requests.get(url, params=params, headers=HEADERS, timeout=20)
            else:
                r = requests.post(url, data=params, headers=HEADERS, timeout=20)
            return r.json()
        except Exception as e:
            if i < retries:
                time.sleep(3)
            else:
                print(f"  {C.err('[!]')} 网络异常: {e}")
                return None
    return None


def login(phone=None, pwd=None):
    phone = phone or CFG["phone"]
    pwd = pwd or CFG["pwd"]
    d = api("POST", "/user/login",
            {"password": md5_upper10(pwd), "type": "0", "telephone": phone})
    if not d or d.get("errorCode") != 0:
        print(f"  {C.err('[!]')} 登录失败: {d and d.get('errorMessage')}")
        return None
    data = d["data"]
    ua = data["userAccount"]
    return {
        "loginCode": data["loginCode"],
        "userId": data["userId"],
        "projectId": ua["projectId"],
        "accountId": ua["accountId"],
        "telephone": data["telephone"],
        "userName": ua.get("name") or "",
        "accountRealMoney": ua.get("accountRealMoney"),
    }


_sms_code = ""   # 本次会话的短信验证码（不落盘，过期即失效）


def send_sms_code(phone):
    """发送短信验证码：GET /user/verification/code/get（typeId=3 短信登录，secret 随手机号动态计算）"""
    d = api("GET", "/user/verification/code/get", {
        "phoneSystem": "ios", "platform": "1", "secret": sms_secret(phone),
        "telephone": phone, "typeId": "3", "version": "6.5.26",
    })
    if not d or d.get("errorCode") != 0:
        print(f"  {C.err('[!]')} 验证码发送失败: {d and d.get('errorMessage')}")
        return False
    return True


def login_by_code(phone=None, sms_code=None):
    """验证码登录：POST /user/registerAndLogin（type=5 短信验证码）"""
    phone = phone or CFG["phone"]
    d = api("POST", "/user/registerAndLogin", {
        "phoneSystem": "ios", "smsCode": sms_code, "telephone": phone,
        "type": "5", "version": "6.5.26",
    })
    if not d or d.get("errorCode") != 0:
        print(f"  {C.err('[!]')} 验证码登录失败: {d and d.get('errorMessage')}")
        return None
    data = d["data"]
    ua = data["userAccount"]
    return {
        "loginCode": data["loginCode"],
        "userId": data["userId"],
        "projectId": ua["projectId"],
        "accountId": ua["accountId"],
        "telephone": data["telephone"],
        "userName": ua.get("name") or "",
        "accountRealMoney": ua.get("accountRealMoney"),
    }


def session_alive(s):
    """探测会话是否仍有效。True=有效 / False=已失效 / None=无法判定（网络问题）

    用设备查询接口做探针：它同时会暴露"登录失效"这类会话错误，
    而"找不到设备"只说明项目/设备不匹配，不代表会话失效。
    """
    if not isinstance(s, dict) or not s.get("loginCode"):
        return False
    d = api("GET", "/device/list/mac", {
        "projectId": s.get("projectId"), "isNew": 1,
        "macAddressList": CFG.get("mac", ""),
        "loginCode": s.get("loginCode"), "userId": s.get("userId"),
        "accountId": s.get("accountId"),
    })
    if not d:
        return None
    msg = str(d.get("errorMessage") or "")
    if "登录" in msg or "失效" in msg or "token" in msg.lower():
        return False
    return True


def login_session():
    """优先复用已保存会话（复用前先探测有效性），否则按凭据登录。

    服务端已禁用密码直登，密码登录只会累计失败并被锁号；
    验证码登录成功后会话写入 config.json 长期复用。
    """
    cached = CFG.get("session")
    if isinstance(cached, dict) and cached.get("loginCode"):
        alive = session_alive(cached)
        if alive:
            print(f"  {C.info('[*]')} 会话有效（{CFG.get('active_phone')}）"
                  f" loginCode={str(cached['loginCode'])[:10]}…")
            return cached
        if alive is False:
            print(f"  {C.warn('[!]')} 已保存的会话【已失效】→ 需要重新登录")
            print(f"  {C.info('[*]')} 请用菜单 [5] 账号管理做验证码登录")
            p = CFG.get("active_phone")
            if p:
                CFG.setdefault("accounts", {}).setdefault(p, {})["session"] = None
            CFG["session"] = None
            save_config()
            return None
        print(f"  {C.warn('[!]')} 会话探测遇到网络问题，暂按有效处理")
        return cached

    if CFG["pwd"]:
        s = login()
        if s:
            set_active_session(s)
        return s
    if not _sms_code:
        print(f"  {C.err('[!]')} 未获取到短信验证码，请用菜单 [5] 做验证码登录")
        return None
    s = login_by_code(CFG["phone"], _sms_code)
    if s:
        set_active_session(s)
    return s


def sncode():
    """设备 SN = MAC 去冒号"""
    return CFG["mac"].replace(":", "").upper()


def query_device(s):
    d = api("GET", "/device/list/mac", {
        "projectId": s["projectId"], "isNew": 1, "macAddressList": CFG["mac"],
        "loginCode": s["loginCode"], "userId": s["userId"], "accountId": s["accountId"],
    })
    if not d or d.get("errorCode") != 0 or not d.get("data"):
        print(f"  {C.err('[!]')} 查询设备失败: {d and d.get('errorMessage')}")
        return None
    return d["data"][0]


def ble_signature(param_str, login_code):
    """BLE 专用双重 MD5 签名（源码 sign 函数）"""
    inner = md5(param_str + "&key=" + login_code)
    return md5(inner + "&key=" + SIGN_KEY)


def ble_rate_order(s, dev_id, random_number, comm_mode, mac=None):
    """BLE 开阀下单：返回 downData / order_info
    实测修正：必须带 userId+accountId；couponId 省略；mac 缺省用 CFG"""
    order_auth = "ble_" + str(int(time.time() * 1000))
    sig = ble_signature(
        f"deviceId{dev_id}randomNumber{random_number}telephone{s['telephone']}xfModel0",
        s["loginCode"])
    d = api("POST", "/order/ali/downRate/bluetooth/rateOrder", {
        "xfModel": 0, "loginCode": s["loginCode"],
        "protocolType": comm_mode, "randomNumber": random_number,
        "projectId": s["projectId"], "orderAuthCode": order_auth,
        "deviceId": dev_id, "smallTypeId": SMALL_TYPE_ID, "signature": sig,
        "telephone": s["telephone"], "macAddress": mac or CFG["mac"],
        "bigTypeId": BIG_TYPE_ID, "userId": s["userId"], "accountId": s["accountId"],
    })
    if not d:
        return None, None
    if d.get("errorCode") != 0:
        print(f"  {C.err('[!]')} rateOrder 失败: {d.get('errorMessage')}")
        return None, None
    data = d.get("data") or {}
    if data.get("rate"):
        update_cloud_rate(data.get("rate"))
    return data.get("downData"), data


def ble_upload(s, xf_data, random_number, comm_mode):
    """上传 85 消费数据，返回 clData（AES 加密账单）"""
    sig = ble_signature(
        f"loginCode{s['loginCode']}telephone{s['telephone']}xfData{xf_data}",
        s["loginCode"])
    body = {
        "loginCode": s["loginCode"], "protocolType": comm_mode,
        "telephone": s["telephone"], "userId": s["userId"], "accountId": s["accountId"],
        "xfData": xf_data, "randomNumber": random_number,
        "signature": sig, "projectId": s["projectId"],
    }
    # 服务端对同一订单有提交频率限制，连续提交会回 531「请不要连续点击」。
    # 实测它和真正的失败（226/170）不是一回事，必须退避重试而不是当成上传失败。
    for i in range(3):
        d = api("POST", "/order/upload/bluetooth/data", body)
        if not d:
            return None
        code = d.get("errorCode")
        if code == 531:
            wait = 3 + i * 3
            print(f"  {C.warn('[!]')} 服务端限流（531 请不要连续点击），"
                  f"{wait}s 后重试 ...")
            time.sleep(wait)
            continue
        if code != 0 and code not in (206, 7):
            print(f"  {C.err('[!]')} 上传账单失败: errorCode={code} "
                  f"{d.get('errorMessage')}")
            if code == 226:
                print(f"  {C.info('[*]')} 226 = 服务端校验不通过。已知触发条件："
                      f"上报的费率与云端下发的不一致（费率字段不能清零），"
                      f"或流水本身是设备未用水时的全零记录")
            if DEBUG:
                print(f"  {C.warn('[调试]')} xfData={xf_data}（{len(xf_data) // 2}B）")
                print(f"  {C.warn('[调试]')} 服务端原始响应={d}")
            return None
        return (d.get("data") or {}).get("clData")
    print(f"  {C.err('[!]')} 上传连续被限流，放弃本次上传")
    return None


def aes_decrypt(cl_data):
    """CryptoJS.AES.decrypt(clData, key) -> 明文 "xxx-clearCode" 的 clearCode"""
    try:
        raw = base64.b64decode(cl_data)
    except Exception:
        try:
            raw = bytes.fromhex(cl_data)
        except Exception:
            print(f"  {C.err('[!]')} clData 解码失败")
            return None
    cipher = AES.new(AES_KEY, AES.MODE_ECB)
    plain = cipher.decrypt(raw)
    pad = plain[-1] if plain else 0
    if 0 < pad <= 16:
        plain = plain[:-pad]
    text = plain.decode("utf-8", errors="replace")
    print(f"  {C.ok('[OK]')} 账单解密: {text}")
    if "-" in text:
        return text.split("-", 1)[1]
    return text


# ================= TCP 云端通道（互联网方式） =================
def tcp_open(s):
    """互联网方式开阀：rateOrder(xfModel=1, 预扣5元) + 轮询结果"""
    print("  " + "-" * 56)
    print(f"  {C.bold('[1] 开阀（互联网方式）')}  云端 rateOrder + 轮询")
    if not CFG["mac"]:
        print(f"  {C.err('[!]')} 未选择设备，请先用菜单 [4] 搜索并选择你的水表")
        return None
    dev = query_device(s)
    if not dev:
        return None
    print(f"  {C.info('[*]')} 设备: {dev.get('deviceName')}  [在线]")
    print(f"  {C.info('[*]')} 调用云端开阀接口（预扣 5 元，xfModel=1）...")
    d = api("POST", "/order/ali/tcpDevice/downRate/rateOrder", {
        "accountId": s["accountId"], "deviceSncode": sncode(),
        "telephone": s["telephone"], "loginCode": s["loginCode"],
        "orderAuthCode": "", "projectId": s["projectId"],
        "userId": s["userId"], "snCode": sncode(), "xfModel": 1,
    })
    if not d:
        return None
    if d.get("errorCode") != 0:
        print(f"  {C.err('[!]')} 开阀失败: {d.get('errorMessage')}")
        return None
    print(f"  {C.ok('[OK]')} 开阀指令已下发，等待设备响应 ...")
    if CFG.get("rate"):
        print(f"  {C.info('[*]')} 费率（云端）: {CFG['rate']:.3f} 元/升")
    order_no = (d.get("data") or {}).get("orderNo") or ""
    for i in range(1, 6):
        time.sleep(8)
        r = api("POST", "/order/tcpDevice/query/downRateResult", {
            "snCode": sncode(), "telephone": s["telephone"],
            "loginCode": s["loginCode"], "orderAuthCode": "",
            "projectId": s["projectId"], "userId": s["userId"],
            "accountId": s["accountId"],
        })
        if not r:
            continue
        if r.get("errorCode") != 0:
            print(f"  [轮询{i}] {r.get('errorMessage')}，继续 ...")
            continue
        data = r.get("data")
        if not data:
            continue
        if data.get("rate"):
            update_cloud_rate(data.get("rate"))
        if not order_no:
            order_no = data.get("orderNo") or ""
        if data.get("result") == 1:
            print(f"  {C.ok('[OK]')} [轮询{i}] 设备确认开阀成功")
            return order_no
        if data.get("result") == 2:
            print(f"  {C.err('[!]')} [轮询{i}] 设备开阀失败（result=2）")
            return None
        print(f"  [轮询{i}] 设备处理中 (result={data.get('result')}) ...")
    return order_no or None


def tcp_using(s):
    """查当前在用订单号（互联网方式）"""
    if not CFG["mac"]:
        return None
    d = api("POST", "/order/tcpDevice/query/rateOrder/using", {
        "accountId": s["accountId"], "deviceSncode": sncode(),
        "telephone": s["telephone"], "loginCode": s["loginCode"],
        "projectId": s["projectId"], "userId": s["userId"], "snCode": sncode(),
    })
    if not d or d.get("errorCode") != 0 or not d.get("data"):
        return None
    return d["data"]


def tcp_close(s, order_no):
    """互联网方式关阀：closeOrder + 轮询结果"""
    print("  " + "-" * 56)
    print(f"  {C.bold('[2] 关阀（互联网方式）')}  云端 closeOrder + 轮询")
    if not CFG["mac"]:
        print(f"  {C.err('[!]')} 未选择设备，请先用菜单 [4] 搜索并选择你的水表")
        return False
    if not order_no:
        print(f"  {C.err('[!]')} 缺少订单号")
        return False
    print(f"  {C.info('[*]')} 调用云端关阀接口（订单 {order_no}）...")
    d = api("POST", "/order/tcpDevice/closeOrder", {
        "loginCode": s["loginCode"], "projectId": s["projectId"],
        "snCode": sncode(), "telephone": s["telephone"], "orderNo": order_no,
        "userId": s["userId"], "accountId": s["accountId"],
    })
    if not d:
        return False
    if d.get("errorCode") != 0:
        print(f"  {C.err('[!]')} 关阀失败: {d.get('errorMessage')}")
        return False
    print(f"  {C.ok('[OK]')} 关阀指令已下发，等待设备停水 ...")
    for i in range(1, 4):
        time.sleep(8)
        r = api("POST", "/order/tcpDevice/closeOrder/result/query", {
            "orderNo": order_no, "telephone": s["telephone"],
            "loginCode": s["loginCode"], "projectId": s["projectId"],
            "userId": s["userId"], "snCode": sncode(), "accountId": s["accountId"],
        })
        if not r:
            continue
        if r.get("errorCode") == 0:
            print(f"  {C.ok('[OK]')} [轮询{i}] 设备确认关阀")
            return True
        print(f"  [轮询{i}] {r.get('errorMessage')}，继续 ...")
    return True  # 指令已下发，默认成功


def tcp_query(s):
    """互联网方式查询：设备在线状态 + 在用订单 + 余额"""
    print("  " + "-" * 56)
    print(f"  {C.bold('[3] 查询（互联网方式）')}")
    if not CFG["mac"]:
        print(f"  {C.err('[!]')} 未选择设备，请先用菜单 [4] 搜索并选择你的水表")
        return
    dev = query_device(s)
    if dev:
        print(f"  {C.info('[*]')} 设备: {dev.get('deviceName')}  在线状态: {dev.get('isOnline', '?')}")
    if s.get("accountRealMoney") is not None:
        print(f"  {C.info('[*]')} 账户余额: {s['accountRealMoney']} 元")
    using = tcp_using(s)
    if using and using.get("orderNo"):
        print(f"  {C.warn('[!]')} 当前在用订单: {using.get('orderNo')}")
    else:
        print(f"  {C.ok('[OK]')} 当前无在用订单（设备空闲）")


# ================= BLE 协议工具（修复版解析） =================
def norm_hex(s):
    return "".join(str(s or "").split()).upper()


def sum_payload(payload_hex):
    s = 0
    h = norm_hex(payload_hex)
    for i in range(0, len(h), 2):
        s = (s + int(h[i:i + 2], 16)) & 0xFF
    return s


# 控制字节：新固件用 0x80 族；老固件用 syncType 当控制位（SDK 双编码，v2.10 逆向）
CTRL_NEW = "80"
OLD_CMDS = {
    "state":   ("06", "09", "查状态(老)"),
    "collect": ("07", "09", "采集消费(老)"),
    "clear":   ("08", "09", "清除消费(老)"),
    "rate":    ("05", "08", "下发费率(老)"),
    "start":   ("09", "04", "开始交易(老)"),
    "end":     ("10", "04", "结束交易(老)"),
}


def build_frame(cmd, data="00", ctrl=CTRL_NEW):
    """构造 #60 + LEN(4) + CTRL + CMD + 00 + DATA + CS + 16

    ctrl 默认 80（新固件）；老固件传 OLD_CMDS 中的 syncType。
    空负载按官方抓包补 1 字节 00（查询帧 len=4 即 80 23 00 00）。
    """
    c = norm_hex(cmd).zfill(2)[-2:]
    ct = norm_hex(ctrl).zfill(2)[-2:]
    payload = ct + c + "00" + (norm_hex(data) or "00")
    length = f"{len(payload) // 2:04X}"
    cs = f"{sum_payload(payload):02X}"
    return ("#60" + length + payload + cs + "16").upper()


def build_old_frame(key, data="00"):
    """构造老固件指令帧，返回 (帧, 说明)"""
    ctrl, cmd, label = OLD_CMDS[key]
    return build_frame(cmd, data, ctrl=ctrl), label


# 帧格式探索变体（对应 Java 版 klExploreNext）：不同固件对控制字节的编码不同
FRAME_VARIANTS = [
    ("新固件 80/23", "80", "23", None),
    ("老固件 06/09", "06", "09", None),
    ("老固件 06/04", "06", "04", None),
    ("新固件 80/23+时间负载", "80", "23", "time"),
]

# 新固件操作码 -> 老固件 (ctrl, cmd) 映射；锁定老固件后自动换算
NEW_TO_OLD = {
    "23": ("06", "09"),   # 查状态
    "85": ("07", "09"),   # 采集消费
    "86": ("08", "09"),   # 清除消费
    "17": ("05", "08"),   # 下发费率
    "31": ("09", "04"),   # 开始交易
    "32": ("10", "04"),   # 结束交易
    "34": ("10", "04"),   # 结束交易(水表)
}


def dec_as_hex(n):
    """十进制 -> BCD 风格半字节（等价 Java decAsHexPub：28 -> 0x28）"""
    return ((n // 10) << 4) | (n % 10)


def time_payload():
    """6 字节 BCD 时间负载 YY MM DD HH MM SS，供带时间负载的查询变体使用"""
    t = time.localtime()
    vals = (t.tm_year % 100, t.tm_mon, t.tm_mday, t.tm_hour, t.tm_min, t.tm_sec)
    return "".join("%02X" % dec_as_hex(v) for v in vals)


# ================= 离线费率负载（不扣费开阀的核心） =================
# 在线方式：云端 rateOrder 下发 downData —— 会产生真实订单、真实扣费。
# 离线方式：本地构造费率帧直接下发，预扣金额与费率都由本机决定，
#          设备按此放水，全程不产生云端账单。
# 负载字段来自 KlcxkjProto v2.8 逆向（金额单位：厘，1000 厘 = 1 元）。

def put32(val):
    """4 字节大端十六进制"""
    return "%08X" % (int(val) & 0xFFFFFFFF)


def put16(val):
    """2 字节大端十六进制"""
    return "%04X" % (int(val) & 0xFFFF)


def rate_payload_v2(product_id, account_id, yukou_li, rate1=10, rate2=10, rate3=10):
    """51 字节费率数据域（V1.1 固件新格式）

    productId(4) accountId(4) timeId(12 ASCII "YYMMDDhhmmss") accountType(1)
    useCount(2) yuKou(4 厘) rate1~3(各 4) minMoney(4) minTime(4) paraTypeId(4)
    """
    t = time.localtime()
    time_ascii = "".join("%02d" % v for v in
                         (t.tm_year % 100, t.tm_mon, t.tm_mday,
                          t.tm_hour, t.tm_min, t.tm_sec))
    return (put32(product_id) + put32(account_id)
            + time_ascii.encode("ascii").hex().upper()
            + "02" + put16(1) + put32(yukou_li)
            + put32(rate1) + put32(rate2) + put32(rate3)
            + put32(yukou_li) + put32(1) + put32(1))


def rate_payload(product_id, account_id, yukou_li, rate1=10, rate2=10, rate3=10):
    """33 字节费率数据域（老格式：时间字段用 BCD，无 minMoney/minTime/paraTypeId）"""
    t = time.localtime()
    bcd = "".join("%02X" % dec_as_hex(v) for v in
                  (t.tm_year % 100, t.tm_mon, t.tm_mday,
                   t.tm_hour, t.tm_min, t.tm_sec))
    return (put32(product_id) + put32(account_id) + bcd
            + "02" + put16(1) + put32(yukou_li)
            + put32(rate1) + put32(rate2) + put32(rate3))


def parse_frame(f):
    """修复版解析：按 LEN 精确切帧（官方 slice(13,-5) 对短帧会切丢 1 位）。
    帧 = #60 + LEN(4) + payload + CS(2) + 16
    payload = control(2) + CMD(2) + extend(2) + data
    CS 校验 = payload 各字节和低 8 位"""
    f = norm_hex(f)
    if not f or len(f) < 15 or not f.startswith("#") or not f.endswith("16"):
        return None
    try:
        length = int(f[3:7], 16)
    except ValueError:
        return None
    end_payload = 7 + length * 2
    if end_payload + 4 > len(f):
        control, fun, extend = f[7:9], f[9:11], f[11:13]
        da, cs = f[13:-4], f[-4:-2].upper()
    else:
        payload = f[7:end_payload]
        cs = f[end_payload:end_payload + 2]
        control, fun, extend = payload[0:2], payload[2:4], payload[4:6]
        da = payload[6:]
    calc = sum_payload(control + fun + extend + da) & 0xFF
    return {
        "raw": f, "control": control, "funCode": fun,
        "extend": extend, "data_area": da, "cs": cs,
        "valid": f"{calc:02X}" == cs.upper(),
    }


def parse_query23(da):
    """23 回帧 dataArea 解析（官方 parseQuery23DataArea）"""
    return {
        "returnCode": da[0:2], "productId": da[2:10], "deviceID": da[10:18],
        "accountId": da[18:26], "mac": da[26:38], "commMode": da[38:40],
        "macTime": da[40:48], "tacTimes": da[48:56], "onCharge": da[56:58],
        "macType": da[58:62], "chargeMethod": da[62:64],
    }


ONCHARGE_TEXT = {"00": "空闲", "01": "使用中(放水)", "02": "刷卡", "03": "结算中"}

# 计费方式（设备 23 帧的 chargeMethod 字段）
CHARGE_METHOD_TEXT = {
    "00": "水控计时", "01": "操作计时", "02": "操作计量",
    "03": "电控计时", "04": "电控计量", "11": "水控计量",
}


def parse_old_resp(frame_hex):
    """老固件应答解析：C4 开头帧，校验和允许丢 1 个字节。

    部分老水表固件上报时会丢字节，严格校验会全判失败；
    这里把「差值 ≤ 0x30」放宽为通过，并译出错误码含义。
    返回 None 表示不是老帧（交给标准解析处理）。
    """
    h = norm_hex(frame_hex)
    if len(h) < 8 or not h.startswith("C4"):
        return None
    try:
        b = bytes.fromhex(h)
    except ValueError:
        return "老帧非十六进制"
    out = ["老固件应答[C4]"]
    if len(b) >= 5:
        ctrl = b[3]
        if ctrl == 0x83:
            out.append("错误码03：未采集消费数据（需先采集再清除）")
        elif ctrl == 0x81:
            out.append("成功应答")
        else:
            out.append("控制字节 %02X" % ctrl)
    s = sum(b[:-2]) & 0xFF
    diff = (b[-2] - s) % 256
    if diff == 0:
        out.append("[校验✓]")
    elif diff <= 0x30:
        out.append("[校验✓(容丢1字节)]")
    else:
        out.append("[校验✗差%02X]" % diff)
    return " ".join(out)


# ---------------- 设备缓存 + 事件驱动扫描（连接提速） ----------------
# 旧版连接慢的根因：每次 connect 都先整轮扫描 15 秒，而且必须扫满才返回。
# 现在：① 扫到过的设备缓存下来，连接直接复用（不再重扫）；
#       ② 需要扫描时用广播回调，听到目标立即返回（通常 1~3 秒）。
_BLE_CACHE = {}                 # addr(upper) -> {"dev", "rssi", "ts"}
DEVICE_CACHE_TTL = 90           # 缓存新鲜度（秒）


def _cache_put(dev, adv=None):
    """把一次扫描结果写进设备缓存（同地址保留信号更强的值，时间总是刷新）"""
    addr = (getattr(dev, "address", "") or "").upper()
    if not addr:
        return
    rssi = getattr(adv, "rssi", None) if adv is not None else None
    if not isinstance(rssi, (int, float)):
        rssi = getattr(dev, "rssi", None)
    if not isinstance(rssi, (int, float)):
        rssi = -999
    cur = _BLE_CACHE.get(addr)
    best = rssi if cur is None else max(rssi, cur["rssi"])
    _BLE_CACHE[addr] = {"dev": dev, "rssi": best, "ts": time.time()}


def _cache_get(addr, ttl=DEVICE_CACHE_TTL):
    """取新鲜缓存（超过 ttl 秒视为过期），没有返回 None"""
    rec = _BLE_CACHE.get((addr or "").upper())
    if rec and time.time() - rec["ts"] <= ttl:
        return rec
    return None


async def _scan_match(match, timeout, soft_grace=1.5):
    """事件驱动扫描：听到 match 命中的设备立即停止，不再干等满超时。

    match(dev, adv) 返回值：
      "mac"  —— 精确命中，立即结束
      "soft" —— 前缀/名称兜底命中，先记下；soft_grace 秒内没有精确命中就用它
      None   —— 不匹配
    扫描期间听到的所有设备都会写入设备缓存。返回 (device, adv) 或 (None, None)。
    """
    hard, soft = {}, {}

    def _cb(dev, adv):
        _cache_put(dev, adv)
        if "dev" in hard:
            return
        try:
            kind = match(dev, adv)
        except Exception:
            kind = None
        if kind == "mac":
            hard["dev"], hard["adv"] = dev, adv
        elif kind and "dev" not in soft:
            soft["dev"], soft["adv"], soft["ts"] = dev, adv, time.time()

    scanner = BleakScanner(detection_callback=_cb)
    await scanner.start()
    try:
        deadline = time.time() + max(0.1, timeout)
        while "dev" not in hard:
            now = time.time()
            if "dev" in soft and now - soft["ts"] >= soft_grace:
                break
            if now >= deadline:
                break
            await asyncio.sleep(0.1)
    finally:
        try:
            await scanner.stop()
        except Exception:
            pass
    if "dev" in hard:
        return hard["dev"], hard.get("adv")
    if "dev" in soft:
        return soft["dev"], soft.get("adv")
    return None, None


# ---------------- Windows 系统设备库直连（v4.8 连接提速） ----------------
# 背景：水表广播极稀疏（实测空闲时 45 秒才 1 条），靠「扫描听广播」找表
# 天生快不了。但 Windows 自己维护一个已知蓝牙设备库：设备一旦入库，
# 按地址查询只要 30~100ms，且 bleak 连接时正是用这个 API 取设备对象 ——
# 所以只要把地址直接喂给 BleakClient（构造 BLEDevice），就能完全跳过扫描。
# 设备不在库时（全新系统首次使用）：用系统级 AEP 查询把它登记进库
# （Windows「设置→蓝牙」页同款查询，约 30 秒，仅首次），或退回扫描兜底。
_WARM_TRIED = {}    # mac -> 上次系统登记尝试时间（失败后 2 分钟内不再重试，避免干等）


def _make_ble_device(mac, name=None):
    """用已知地址构造 bleak 的 BLEDevice（连接时跳过扫描，直接查系统设备库）"""
    try:
        from bleak.backends.device import BLEDevice
    except Exception:
        return None
    mac_u = (mac or "").replace("-", ":").upper()
    if not mac_u:
        return None
    try:
        return BLEDevice(mac_u, name or "", None)
    except Exception:
        return None


async def _winrt_db_lookup(mac):
    """查 Windows 系统设备库：True=在库（可直连）/ False=不在 / None=winrt 不可用"""
    try:
        from winrt.windows.devices.bluetooth import BluetoothLEDevice
    except Exception:
        return None
    try:
        addr = int((mac or "").replace(":", "").replace("-", ""), 16)
    except ValueError:
        return False
    try:
        dev = await asyncio.wait_for(
            BluetoothLEDevice.from_bluetooth_address_async(addr), 2.0)
    except Exception:
        return False
    if dev is None:
        return False
    try:
        dev.close()
    except Exception:
        pass
    return True


async def _winrt_warm_db(mac, timeout=32.0):
    """系统级 AEP 查询 + 轮询：把设备登记进 Windows 设备库。

    做法：起一个 AEP DeviceWatcher 触发系统级查询，同时每 0.5 秒按地址
    查一次设备库；查到立即返回（不必等系统查询整批结束）。仅首次需要，
    登记成功后 from_bluetooth_address 永久命中（实测跨进程、跨天有效）。
    """
    try:
        from winrt.windows.devices.bluetooth import BluetoothLEDevice
        from winrt.windows.devices.enumeration import (
            DeviceInformation, DeviceInformationKind)
    except Exception:
        return False
    tag = (mac or "").replace("-", ":").lower()
    if not tag:
        return False
    watcher = None
    try:
        sel = BluetoothLEDevice.get_device_selector_from_pairing_state(False)
        watcher = DeviceInformation.create_watcher_with_kind_aqs_filter_and_additional_properties(
            sel, [], DeviceInformationKind.ASSOCIATION_ENDPOINT)
    except Exception:
        watcher = None
    if watcher is not None:
        try:
            watcher.start()
        except Exception:
            watcher = None

    hit = False
    deadline = time.time() + timeout
    try:
        while time.time() < deadline:
            if await _winrt_db_lookup(mac):
                hit = True
                break
            await asyncio.sleep(0.5)
    finally:
        if watcher is not None:
            try:
                watcher.stop()
            except Exception:
                pass
    return hit


# ================= BLE 客户端 =================
class WaterValveBLE:
    def __init__(self, mac=None, name=None):
        self.mac = mac or CFG["mac"]
        self.name = name or CFG["name"]
        self.client = None
        self.write_char = None
        self.connected = False
        self.rx_buffer = ""
        self.pending = None
        self.last_rx = ""

    @staticmethod
    async def scan(timeout=None, rounds=2):
        """扫描附近 BLE 设备，返回去重列表 [(name, address, rssi)]
        兼容：bleak 0.x 的 rssi 在 BLEDevice 上；bleak 1.x+/3.x 的 rssi 在 advertisement_data 上
        识别规则：名称关键字 ∪ 厂商 OUI 前缀 ∪ 广播服务特征，取并集。
        只按名称过滤会漏掉真设备 —— 水表广播常常不带名字（或名字在 scan response 里丢包），
        而 OUI 前缀在任何广播包里都有，稳定性远高于名称。
        实测水表广播稀疏、信号常低于 -80dBm，单轮 8 秒会整轮扫空，
        因此默认 15 秒一轮，未识别到水表时自动补第二轮（两轮结果累积去重）。"""
        if timeout is None:
            timeout = SCAN_TIMEOUT
        seen, svc_map = {}, {}

        def is_meter(name, addr):
            up = (name or "").upper()
            if DEFAULT_NAME.upper() in up or any(k in up for k in DEVICE_KEYWORDS):
                return True
            if any(addr.upper().startswith(p) for p in _oui_prefixes()):
                return True
            return any(h in s for s in svc_map.get(addr, []) for h in ADV_SVC_HINT)

        def absorb(found):
            """把一轮结果并进累积表，同一个地址保留信号最强的那次"""
            if isinstance(found, dict):             # bleak >=1.0: {address: (device, adv)}
                it = [(d, adv) for d, adv in found.values()]
            else:                                   # 老版本: [BLEDevice]
                it = [(d, None) for d in found]
            for d, adv in it:
                addr = getattr(d, "address", "")
                if not addr:
                    continue
                _cache_put(d, adv)      # 顺手缓存：选完设备连接时免重扫
                if adv is not None:
                    svc_map.setdefault(addr, []).extend(
                        str(s).lower() for s in (getattr(adv, "service_uuids", None) or []))
                    rssi = getattr(adv, "rssi", None)
                else:
                    rssi = getattr(d, "rssi", None)
                if not isinstance(rssi, (int, float)):
                    rssi = -999
                name = getattr(d, "name", None) or "(未命名)"
                cur = seen.get(addr)
                if cur is None or rssi > cur[2]:
                    seen[addr] = (name, addr, rssi)

        matched = []
        for rnd in range(1, max(1, rounds) + 1):
            print(f"  {C.info('[*]')} 正在扫描附近蓝牙设备"
                  f"（第 {rnd}/{rounds} 轮，每轮 {timeout} 秒，弱信号水表请靠近些）...")
            try:
                found = await BleakScanner.discover(timeout=timeout, return_adv=True)
            except TypeError:      # 老版本 bleak 无 return_adv 参数
                found = await BleakScanner.discover(timeout=timeout)
            absorb(found)
            matched = [r for r in seen.values() if is_meter(r[0], r[1])]
            if matched:
                break
            if rnd < rounds:
                print(f"  {C.warn('[!]')} 本轮 {len(seen)} 个设备里没识别到水表，再补一轮 ...")

        if matched:
            print(f"  {C.info('[*]')} 识别到 {len(matched)}/{len(seen)} 个疑似水表"
                  f"（名称关键字 / 厂商前缀 {_oui_text()} / 服务特征 任一命中）")
            return matched
        if seen:
            print(f"  {C.warn('[!]')} 共 {len(seen)} 个设备但都无识别到水表特征，改为显示全部")
        else:
            print(f"  {C.warn('[!]')} 没收到任何广播：蓝牙可能被占用/未开启，或设备不在范围内")
        return list(seen.values())

    async def find_device(self, timeout=None, force_scan=False, allow_oui=True):
        """查找顺序：系统设备库直连 → 新鲜缓存 → 完整 MAC 扫描 → 厂商 OUI / 名称。

        v4.8 提速：已知 MAC 时先查 Windows 系统设备库（~50ms），在库就直接
        构造设备对象返回，完全不扫描；不在库时做一次系统级登记（约 30 秒，
        仅首次，此后永久命中），仍失败才退回原扫描流程。
        force_scan=True：跳过系统库/缓存，强制走扫描（水表复位换地址时用）。

        allow_oui=False：**只认完整 MAC**。已保存过设备时一律用这个模式 ——
        同厂商的水表前缀都一样（实测隔壁宿舍那台只差后 3 字节），按前缀兜底会
        连到别人的表上（2026-10-20 实测：自动连到了 328 房那台并改写了目标地址）。
        设备复位换地址后，让用户用「选择设备」重新认表，不再自动猜。
        """
        timeout = SCAN_TIMEOUT if timeout is None else timeout
        want = (self.mac or "").replace("-", ":").upper()

        # 0) 已知 MAC：系统设备库直查（免扫描，~50ms）
        if want and not force_scan:
            dev = _make_ble_device(want, self.name)
            if dev is not None:
                state = await _winrt_db_lookup(want)
                if state:
                    print(f"  {C.ok('[+]')} 系统设备库直连 {want}（免扫描）")
                    _cache_put(dev)
                    return dev
                if state is False:
                    last = _WARM_TRIED.get(want, 0.0)
                    if time.time() - last >= 120:
                        _WARM_TRIED[want] = time.time()
                        print(f"  {C.info('[*]')} 设备不在系统库，"
                              f"正在做系统级查找（最长约 30 秒，仅首次）...")
                        if await _winrt_warm_db(want):
                            print(f"  {C.ok('[+]')} 已登记进系统库，"
                                  f"直连 {want}（免扫描）")
                            _cache_put(dev)
                            return dev
                        print(f"  {C.warn('[!]')} 系统级查找没登记上，回退扫描兜底")

        # 1) 新鲜缓存：刚扫到过就直接复用设备对象，跳过整轮扫描
        if want and not force_scan:
            rec = _cache_get(want)
            if rec is not None:
                age = int(time.time() - rec["ts"])
                print(f"  {C.ok('[+]')} 使用缓存设备 {want}"
                      f"（{age} 秒前扫到，RSSI={rec['rssi']}），跳过扫描")
                return rec["dev"]

        oui = _oui_prefixes()
        name_u = (self.name or "").upper()

        def match(dev, adv):
            addr = (dev.address or "").replace("-", ":").upper()
            if want and addr == want:
                return "mac"
            if not allow_oui:
                return None          # 已保存设备：只认完整 MAC，绝不猜
            if oui and any(addr.startswith(p) for p in oui):
                return "soft"
            nm = (getattr(dev, "name", None) or "").upper()
            if name_u and name_u in nm:
                return "soft"
            return None

        print(f"  {C.info('[*]')} 扫描设备（听到目标立即返回，最长 {timeout} 秒）...")
        dev, adv = await _scan_match(match, timeout)
        if dev is not None:
            rssi = getattr(adv, "rssi", None) if adv is not None else getattr(dev, "rssi", None)
            addr = (dev.address or "").upper()
            if want and addr == want:
                if isinstance(rssi, (int, float)) and rssi < -80:
                    print(f"  {C.warn('[!]')} 信号偏弱（{rssi} dBm），"
                          f"连接容易失败，建议靠近水表到 3 米内")
                print(f"  {C.ok('[+]')} 扫描命中目标 {addr}")
                return dev
            # 前缀 / 名称兜底命中（设备复位换了 MAC 也能找回）
            named = name_u and name_u in (getattr(dev, "name", None) or "").upper()
            tag = "名称确认" if named else "仅前缀命中（未拿到广播名）"
            print(f"  {C.ok('[+]')} 本机水表按前缀发现 {dev.address} "
                  f"(RSSI={rssi}, {tag})")
            return dev

        if want:
            print(f"  {C.warn('[!]')} 本轮没有 {self.mac} —— 设备当前不在广播范围")
        else:
            print(f"  {C.warn('[!]')} 未选择设备（无 MAC），跳过按地址查找")
        if oui:
            print(f"  {C.warn('[!]')} 本轮也没有 {'/'.join(oui)} 前缀的设备")
        return None

    async def connect(self):
        if self.connected and self.client and self.client.is_connected:
            return True
        if not self.mac and not self.name:
            print(f"  {C.err('[!]')} 尚未选择目标设备（本程序不内置默认设备）。")
            print("      请先执行菜单 [4] 搜索并选择你的水表。")
            return False
        device = await self.find_device(allow_oui=not self.mac)
        if device is None:
            tip = f"{self.name} ({self.mac})" if self.mac else (self.name or "未命名设备")
            print(f"  {C.err('[!]')} 未找到设备 {tip}")
            print("      请确认：电脑蓝牙已开启 / 设备在范围内 / 未被手机占用")
            return False
        print(f"  {C.info('[*]')} 找到设备 {device.name or self.name} ({device.address})")
        for attempt in range(1, 4):
            # 第 1 次优先走系统 GATT 服务缓存（实测 0.1~5 秒连上，免服务发现）；
            # 缓存为空/过期时自动退回完整服务发现重连。
            use_cache = (attempt == 1)
            tip = "优先缓存" if use_cache else "完整服务发现"
            print(f"  {C.info('[*]')} 正在连接 (第 {attempt}/3 次，{tip}) ...")
            kwargs = {"winrt": {"use_cached_services": True}} if use_cache else {}
            self.client = BleakClient(device, timeout=CONNECT_TIMEOUT, **kwargs)
            try:
                await self.client.connect()
                if use_cache:
                    try:
                        w, _n = self._resolve_chars() if self.client.services else (None, None)
                    except Exception:
                        w = None
                    if w is None:
                        print(f"  {C.warn('[!]')} 缓存服务表不完整，"
                              f"改用完整服务发现重连 ...")
                        try:
                            await self.client.disconnect()
                        except Exception:
                            pass
                        continue
                break
            except Exception as e:
                nm = type(e).__name__
                msg = str(e).strip()
                if not msg:
                    msg = "（系统未给出原因，通常是信号太弱或设备已被另一台手机占用）"
                print(f"  {C.warn('[!]')} 连接失败：{nm}: {msg}")
                # 设备对象可能已过期：清缓存；已知 MAC 时无需重扫（直连更快），
                # 仅按扫描找到的设备才补一轮短扫描。
                _BLE_CACHE.pop((device.address or "").upper(), None)
                if attempt < 3:
                    await asyncio.sleep(0.5)
                    if not self.mac:
                        fresh = await self.find_device(timeout=5)
                        if fresh is not None:
                            device = fresh
        else:
            # 直连 3 次都失败：最后强制扫描一轮。
            # ★ 已保存过设备时**只按完整 MAC**查（allow_oui=False）：
            #   同厂商前缀一大片，按前缀兜底会连到隔壁宿舍的表。
            fresh = await self.find_device(timeout=SCAN_TIMEOUT, force_scan=True,
                                           allow_oui=not self.mac)
            if fresh is not None and (fresh.address or "").upper() != (self.mac or "").upper():
                if self.mac:
                    print(f"  {C.err('[!]')} 扫描到同厂商另一台表 {fresh.address}，"
                          f"但配置的目标是 {self.mac} —— 不自动切换")
                    print(f"      确认要换表请用「选择设备」手动选，避免开错别人的阀门")
                    return False
                print(f"  {C.info('[*]')} 设备换了地址（{fresh.address}），用新地址重试连接 ...")
                device = fresh
                try:
                    self.client = BleakClient(device, timeout=CONNECT_TIMEOUT)
                    await self.client.connect()
                except Exception as e:
                    nm = type(e).__name__
                    msg = str(e).strip() or "（系统未给出原因）"
                    print(f"  {C.warn('[!]')} 连接失败：{nm}: {msg}")
                    return False
                # 走下方成功收尾（同一函数尾部逻辑）
                return await self._finish_connect(device)
            print(f"  {C.err('[!]')} 连续 3 次连接失败")
            print(f"  {C.info('[*]')} 常见原因（按概率）：")
            print(f"      ① 离水表太远 —— BLE 连接要求信号强于约 -80dBm，"
                  f"能扫到不等于能连上")
            print(f"      ② 另一台手机的水控 App 正连着这块表 —— "
                  f"先关掉手机蓝牙或退出 App")
            print(f"      ③ 水表刚被别人刷开，处于占用状态 —— 稍等再试")
            return False
        return await self._finish_connect(device)

    async def _finish_connect(self, device):
        """连接成功后的收尾：缓存设备、认领 MAC、解析特征、订阅上报"""
        self.connected = True
        _cache_put(device)      # 连上了：缓存设备对象，短时间重连免扫描
        print(f"  {C.ok('[+]')} 已连接")
        if device.address and device.address.upper() != (self.mac or "").upper():
            old = self.mac
            self.mac = device.address.upper()
            self.name = device.name or self.name
            if not old:
                # 首次装配（还没保存过设备）：认领并落盘
                print(f"  {C.ok('[OK]')} 已保存目标设备: {self.name} ({self.mac})")
                CFG["mac"], CFG["name"] = self.mac, self.name
                save_config()
            else:
                # ★ 配置里已有目标表，却连到了另一台：绝不自动改目标
                print(f"  {C.err('[!]')} 连到的设备 {self.mac} 与配置目标 {old} 不一致")
                print(f"      未改写配置；开阀前请先用「选择设备」确认是哪一块表")
        try:
            self.write_char, notify = self._resolve_chars()
            if self.write_char is None:
                print(f"  {C.err('[!]')} 没找到可写特征，无法下发指令")
                return False
            wprops = ",".join(getattr(self.write_char, "properties", None) or [])
            wu = str(self.write_char.uuid).upper()
            wdisp = ("0x" + wu[4:8]) if wu.startswith("0000") else wu
            nu = str(notify.uuid).upper() if notify is not None else ""
            ndisp = ("0x" + nu[4:8]) if nu.startswith("0000") else nu
            print(f"  {C.ok('[+]')} 写特征 {wdisp}（{wprops or '未知属性'}）")
            if notify is not None:
                subscribed = False
                last_err = ""
                for sub_try in range(1, 5):
                    # WinRT start_notify 偶发「操作已被取消 / Not connected」——
                    # 等连接稳定后重试几次即可；实在连不上由外层 send 重连兜底。
                    if sub_try > 1:
                        await asyncio.sleep(0.4)
                    try:
                        if not self.client.is_connected:
                            continue
                        await self.client.start_notify(notify, self._on_notify)
                        subscribed = True
                        break
                    except Exception as e:
                        last_err = "%s: %s" % (type(e).__name__, str(e)[:60])
                        if sub_try == 4:
                            print(f"  {C.warn('[!]')} 订阅失败：{last_err}")
                if subscribed:
                    print(f"  {C.ok('[+]')} 已订阅上报通道 {ndisp}")
            else:
                print(f"  {C.warn('[!]')} 未找到可通知特征，只发不收（设备响应收不到）")
        except Exception as e:
            print(f"  {C.warn('[!]')} 订阅通知失败（不影响发送）：{e}")
        if DEBUG:
            self.dump_services()
        return True

    # ---------------- 特征值自动匹配 / 服务诊断 ----------------
    @staticmethod
    def _is_standard_service(uuid):
        """标准 GATT 服务（通用访问/通用属性/设备信息等），挑特征时跳过"""
        u = str(uuid).lower()
        return any(k in u for k in ("00001800", "00001801", "0000180a",
                                    "0000180f", "00001812", "00001816"))

    def _resolve_chars(self):
        """自动匹配写 / 通知特征：优先已知 UUID，否则遍历服务按属性挑选。

        换固件或透传模块后 UUID 常变，写死单一特征会出现「连上了却发不出指令」；
        这里按能力挑选并把结果打印出来，便于定位。
        """
        svcs = self.client.services
        write = (svcs.get_characteristic(WRITE_CHAR)
                 or svcs.get_characteristic("0000ff02-0000-1000-8000-00805f9b34fb"))
        notify = (svcs.get_characteristic(NOTIFY_CHAR)
                  or svcs.get_characteristic("0000ff01-0000-1000-8000-00805f9b34fb"))
        if write is not None and notify is not None:
            return write, notify

        def first_match(keys, skip_standard=True):
            for s in svcs:
                if skip_standard and self._is_standard_service(s.uuid):
                    continue
                for ch in s.characteristics:
                    if keys & set(ch.properties or []):
                        return ch
            return None

        if write is None:
            write = first_match({"write", "write-without-response"})
        if write is None:                     # 兜底：标准服务也翻一遍
            write = first_match({"write", "write-without-response"}, skip_standard=False)
        if notify is None:
            notify = first_match({"notify", "indicate"})
        if notify is None:
            notify = first_match({"notify", "indicate"}, skip_standard=False)
        return write, notify

    def dump_services(self):
        """打印 GATT 服务 / 特征结构，排查「连上但指令没反应」"""
        print(f"  {C.info('[服务结构]')} 设备 GATT：")
        try:
            for s in self.client.services:
                tag = "  (标准)" if self._is_standard_service(s.uuid) else ""
                print(f"    服务 {s.uuid}{tag}")
                for ch in s.characteristics:
                    props = ",".join(ch.properties or [])
                    mark = "   <- 本程序使用的写特征" if ch is self.write_char else ""
                    print(f"      特征 {ch.uuid}  [{props}]{mark}")
        except Exception as e:
            print(f"  {C.warn('[!]')} 枚举服务失败：{e}")

    def _on_notify(self, _sender, data: bytearray):
        try:
            chunk = bytes(data).decode("ascii", errors="replace")
        except Exception:
            return
        self.rx_buffer += chunk
        self._extract_frames()
        if len(self.rx_buffer) > RX_BUFFER_MAX:
            self.rx_buffer = self.rx_buffer[-256:]

    def _extract_frames(self):
        """按帧头分帧并分发。

        - '#'  开头：新固件 60 帧，按帧内 LEN 精确切（修正官方 slice 丢位）
        - 'C4' 开头：老固件应答帧，按帧尾 '16' 定界（老固件上报常丢字节）
        无帧头时只保留尾部残片，不再整段清空——否则 C4 帧会被直接丢弃。
        """
        while True:
            up = self.rx_buffer.upper()
            h = up.find("#")
            c = up.find("C4")
            if h < 0 and c < 0:
                if len(self.rx_buffer) > 64:
                    self.rx_buffer = self.rx_buffer[-64:]
                return

            if h >= 0 and (c < 0 or h <= c):
                # ---- 新固件 60 帧 ----
                if h > 0:
                    self.rx_buffer = self.rx_buffer[h:]
                if len(self.rx_buffer) < 7:
                    return
                try:
                    length = int(self.rx_buffer[3:7], 16)
                except ValueError:
                    self.rx_buffer = self.rx_buffer[1:]
                    continue
                total = 7 + length * 2 + 2 + 2
                if len(self.rx_buffer) < total:
                    return
                frame = self.rx_buffer[:total]
                if not frame.endswith("16"):
                    self.rx_buffer = self.rx_buffer[1:]
                    continue
                self.rx_buffer = self.rx_buffer[total:]
                self._handle_frame(frame)
            else:
                # ---- 老固件 C4 帧 ----
                if c > 0:
                    self.rx_buffer = self.rx_buffer[c:]
                sz = self.rx_buffer.upper().find("16", 2)
                if sz < 0:
                    if len(self.rx_buffer) > 200:      # 明显是垃圾，丢弃
                        self.rx_buffer = ""
                    return
                total = sz + 2
                frame = self.rx_buffer[:total]
                self.rx_buffer = self.rx_buffer[total:]
                self._handle_old_frame(frame)

    def _handle_old_frame(self, frame_hex):
        """老固件 C4 应答处理：译出错误码语义，并唤醒等待中的调用方"""
        self.last_rx = frame_hex
        info = parse_old_resp(frame_hex)
        print(f"  {C.info('[RX]')} 老帧 raw={frame_hex}")
        if info:
            print(f"        {info}")
        if self.pending and not self.pending["future"].done():
            p = self.pending
            self.pending = None
            p["future"].set_result({
                "raw": frame_hex, "control": "C4", "funCode": "OLD",
                "extend": "", "cs": frame_hex[-4:-2], "valid": True,
                "data_area": frame_hex[6:], "old": True,
            })

    def _handle_frame(self, frame_hex):
        parsed = parse_frame(frame_hex)
        self.last_rx = frame_hex
        if not parsed:
            print(f"  [RX] (无法解析) raw={frame_hex}")
            return
        flag = C.ok("[OK]") if parsed["valid"] else C.warn("[!!]")
        print(f"  {flag} RX raw={frame_hex}")
        print(f"        funCode=0x{parsed['funCode']} dataArea={parsed['data_area']}"
              f" CS={parsed['cs']} 校验={'通过' if parsed['valid'] else '异常'}")
        if self.pending and parsed["funCode"] == self.pending["cmd"]:
            p = self.pending
            self.pending = None
            if not p["future"].done():
                p["future"].set_result(parsed)

    async def _write_chunk(self, data):
        """写入一片：按特征能力选 write / write-without-response，失败重试并自动降级。

        国产透传模块在 GATT 忙时会直接抛异常，重试比立刻放弃更接近官方 SDK 行为；
        持续失败则降级为无响应写，再失败才向上抛（交给调用方重连）。
        """
        props = set(getattr(self.write_char, "properties", None) or [])
        use_response = ("write" in props) or ("write-without-response" not in props)
        last = None
        for attempt in range(1, CHUNK_RETRY + 1):
            try:
                await self.client.write_gatt_char(self.write_char, data,
                                                  response=use_response)
                return
            except Exception as e:
                last = e
                use_response = False                 # 后续尝试降级为无响应写
                if attempt < CHUNK_RETRY:
                    dbg(f"片写入失败({attempt}/{CHUNK_RETRY})：{e}")
                    await asyncio.sleep(CHUNK_RETRY_DELAY)
        raise last

    async def _write_frame(self, frame):
        """分片发送整帧。

        帧长超过 MTU 时整帧写会被底层截断，85 采集 / 86 清账这类带负载的帧
        尤其明显（清账负载最长 41 字节，整帧可达 60 字节）。按 WRITE_CHUNK
        切片、片间留出间隔，让对端有时间消化。
        """
        data = frame.encode("ascii")
        if len(data) <= WRITE_CHUNK:
            await self._write_chunk(data)
            return
        total = (len(data) + WRITE_CHUNK - 1) // WRITE_CHUNK
        dbg(f"分片发送 0x{frame[9:11]}：{len(data)} 字节 / {total} 片")
        for i in range(0, len(data), WRITE_CHUNK):
            await self._write_chunk(data[i:i + WRITE_CHUNK])
            if i + WRITE_CHUNK < len(data):
                await asyncio.sleep(CHUNK_DELAY)

    def _map_cmd(self, cmd):
        """按锁定的帧风格换算指令码。

        新固件用 (ctrl=0x80, cmd=操作码)；老固件 ctrl 与 cmd 都不同
        （如查状态 新=80/23、老=06/09），锁定后这里自动换算，
        上层业务代码继续用 23/85/86 等新固件操作码即可。
        """
        c = norm_hex(cmd)
        if str(CFG.get("frame_style", "")).startswith("老固件"):
            old = NEW_TO_OLD.get(c)
            if old:
                return old[1], old[0]
        return c, (CFG.get("frame_ctrl") or CTRL_NEW)

    async def send_and_wait(self, cmd, data="00", timeout=10.0):
        c, ctrl = self._map_cmd(cmd)
        frame = build_frame(c, data, ctrl=ctrl)
        return await self.send_and_wait_raw(frame, timeout)

    async def hard_reconnect(self, tag="重连"):
        """断开并重连：GATT 队列卡死时的复位手段"""
        print(f"  {C.info('[*]')} {tag}：断开并重新连接设备 ...")
        try:
            await self.disconnect()
        except Exception:
            pass
        await asyncio.sleep(1.0)
        ok = await self.connect()
        print(f"  {C.ok('[OK]')} {tag}成功" if ok else f"  {C.err('[!]')} {tag}失败")
        return ok

    async def send_and_wait_raw(self, frame, timeout=10.0):
        cmd = frame[9:11]
        print(f"  {C.info('[TX]')} 发送 0x{cmd} 帧：{frame}")
        if self.pending:
            old = self.pending
            self.pending = None
            if not old["future"].done():
                old["future"].set_exception(RuntimeError("被新命令打断"))
        future = asyncio.get_running_loop().create_future()
        self.pending = {"cmd": cmd, "future": future}
        try:
            await self._write_frame(frame)
        except Exception as e:
            # 写入异常多为 GATT 队列卡死：断开重连后再发一次
            print(f"  {C.warn('[!]')} 写入失败：{e}")
            self.pending = None
            if WRITE_RETRY <= 0:
                raise
            if not await self.hard_reconnect("写入失败重连"):
                raise
            future = asyncio.get_running_loop().create_future()
            self.pending = {"cmd": cmd, "future": future}
            await self._write_frame(frame)
        try:
            return await asyncio.wait_for(future, timeout=timeout)
        except asyncio.TimeoutError:
            self.pending = None
            raise TimeoutError(
                f"等待 0x{cmd} 回包超时（{timeout}s），最后回包={self.last_rx or '无'}")

    async def query_status(self):
        """23 查询，返回解析 dict；失败抛异常"""
        resp = await self.send_and_wait("23", "00", timeout=8)
        st = parse_query23(resp["data_area"])
        state = ONCHARGE_TEXT.get(st["onCharge"], f"未知({st['onCharge']})")
        print(f"  {C.ok('[+]')} 设备状态: returnCode={st['returnCode']} "
              f"deviceId={int(st['deviceID'], 16)} commMode={st['commMode']} "
              f"onCharge={st['onCharge']} ({state}) randomNumber={st['tacTimes']}")
        return st

    async def disconnect(self):
        if self.client and self.client.is_connected:
            try:
                await self.client.disconnect()
            except Exception:
                pass
        self.connected = False


# ================= BLE 业务流程 =================
async def probe_frame_style(valve, timeout_each=2.5):
    """逐变体探测帧编码风格，收到应答即锁定并写入 config。

    对应 Java 版 klExploreNext：新固件用 (0x80, 0x23)，老固件用 (06, 09) 等。
    锁定后 `_map_cmd` 会自动换算后续所有指令，业务代码无需改动。
    """
    print("  " + "-" * 56)
    print(f"  {C.bold('[帧格式探索]')} 依次尝试 {len(FRAME_VARIANTS)} 种编码发查询帧")
    print(f"  {C.info('[*]')} 当前锁定：{CFG.get('frame_style') or '未锁定（默认新固件 80/23）'}")
    for i, (name, ctrl, cmd, extra) in enumerate(FRAME_VARIANTS, 1):
        data = time_payload() if extra == "time" else "00"
        frame = build_frame(cmd, data, ctrl=ctrl)
        print(f"  {C.info('[*]')} 变体 {i}/{len(FRAME_VARIANTS)}「{name}」 -> {frame}")
        try:
            resp = await valve.send_and_wait_raw(frame, timeout=timeout_each)
        except TimeoutError:
            print(f"  {C.warn('[!]')} 无应答")
            await asyncio.sleep(0.5)
            continue
        print(f"  {C.ok('[★]')} 帧格式锁定：「{name}」（ctrl={ctrl} cmd={cmd}）"
              f"，已写入 config，后续指令自动换算")
        CFG["frame_style"] = name
        CFG["frame_ctrl"] = ctrl
        save_config()
        return {"name": name, "ctrl": ctrl, "cmd": cmd, "resp": resp}
    print(f"  {C.err('[!]')} {len(FRAME_VARIANTS)} 种编码均无应答："
          f"设备可能未就绪，或需要先完成密钥认证")
    return None


async def ble_open(valve, s):
    print("  " + "-" * 56)
    print(f"  {C.bold('[1] 开阀（蓝牙方式）')}  BLE 23 查询 -> 云端下单 -> 21 下发 downData")
    try:
        st = await _query_rw(valve)
    except TimeoutError as e:
        print(f"  {C.err('[!]')} 查询超时：{e}")
        return False
    if st["returnCode"] != "80":
        print(f"  {C.err('[!]')} 查询异常 returnCode={st['returnCode']}")
        return False
    if st["onCharge"] != "00":
        # 结算中(03) 多为上轮残留消费数据：自动补结算后重查，不必用户手动处理
        if not await ensure_idle(valve, s):
            return False
        try:
            st = await _query_rw(valve)
        except TimeoutError as e:
            print(f"  {C.err('[!]')} 补结算后查询超时：{e}")
            return False
        if st["onCharge"] != "00":
            print(f"  {C.warn('[!]')} 设备仍未就绪 onCharge={st['onCharge']}，开阀中止")
            return False
    random_number = st["tacTimes"]
    comm_mode = st["commMode"]

    # ★ 身份闸：设备自报 deviceId 必须与上次认定的那块表一致。
    #   同一栋楼一层就有好几块同型号表，连错了照样能开水 —— 那是在开别人的阀门。
    try:
        cur_id = int(str(st.get("deviceID") or "0"), 16)
    except ValueError:
        cur_id = 0
    expect_id = CFG.get("last_dev_id")
    if expect_id and cur_id and int(expect_id) != cur_id:
        print(f"  {C.err('[!]')} 这块表 deviceId={cur_id}，配置认定的是 {expect_id} —— 不是同一块表")
        print(f"      已中止：没有下单、没有出水量。请用「选择设备」重新确认目标水表")
        return False

    # 账号项目 vs 设备项目：不匹配时云端必然回"找不到设备"，
    # 这里提前拦下并给出指引（实测踩过：用了别的项目的账号）
    try:
        dev_proj = int(str(st.get("productId") or "0"), 16)
    except ValueError:
        dev_proj = 0
    acc_proj = s.get("projectId")
    try:
        acc_proj_i = int(acc_proj) if acc_proj is not None else 0
    except (TypeError, ValueError):
        acc_proj_i = 0
    if dev_proj and acc_proj_i and acc_proj_i != dev_proj:
        print(f"  {C.err('[!]')} 账号项目 {acc_proj_i} ≠ 设备项目 {dev_proj}")
        print(f"  {C.info('[*]')} 该账号不属于这台设备所在项目，云端会返回「找不到设备」")
        print(f"  {C.info('[*]')} 请用菜单 [5] 切换到项目 {dev_proj} 的账号")
        return False

    dev = query_device(s)
    if dev:
        dev_id = dev.get("deviceId")
        dev_mac = CFG["mac"]
        dev_sn = dev.get("snCode")
    else:
        # 云端查不到（MAC 未配置/账号未绑定），用 23 帧内的设备信息兜底
        print(f"  {C.warn('[!]')} 云端查设备失败，改用 BLE 帧内设备信息 ...")
        dev_id = int(st["deviceID"], 16)
        dev_mac = fmt_mac_hex(st["mac"])
        dev_sn = None
    print(f"  {C.info('[*]')} 使用 randomNumber={random_number} commMode={comm_mode} "
          f"deviceId={dev_id}" + (f" snCode={dev_sn}" if dev_sn else ""))
    # 记住设备身份：Profile 以 deviceId 为 key（MAC 会因复位而变）
    try:
        CFG["last_dev_id"] = int(dev_id) if dev_id is not None else None
    except (TypeError, ValueError):
        CFG["last_dev_id"] = None
    prof = profile_get()
    if prof:
        print(f"  {C.info('[*]')} 设备档案：型号 {prof.get('model', '?')} / "
              f"流水 {prof.get('flow_len', '?')}B / "
              f"上次清账「{prof.get('clear_by') or '无记录'}」")

    print(f"  {C.info('[*]')} 云端 BLE 下单 rateOrder（预扣 5 元）...")
    down_data, order_info = ble_rate_order(s, dev_id, random_number, comm_mode, mac=dev_mac)
    if not down_data:
        print(f"  {C.err('[!]')} 未拿到 downData，开阀中止")
        return False
    print(f"  {C.ok('[OK]')} 拿到 downData={down_data}")
    if order_info.get("rate"):
        r = update_cloud_rate(order_info.get("rate"))
        if r:
            print(f"        费率 rate={order_info.get('rate')} 厘（云端 {r:.3f} 元/升）")
        else:
            print(f"        费率 rate={order_info.get('rate')} 厘")

    print(f"  {C.info('[*]')} BLE 下发 21 + downData ...")
    try:
        resp = await valve.send_and_wait("21", down_data, timeout=8)
        if resp["data_area"][:2] == "80":
            print(f"  {C.ok('[OK]')} 设备回 80 —— 费率已接受")
    except TimeoutError:
        print(f"  {C.info('[*]')} 21 无回帧（部分设备静默执行），等待状态变化确认 ...")
    # 官方判定：轮询 23 查询 onCharge 变为 01（使用中）即放水成功
    for i in range(1, 6):
        await asyncio.sleep(2)
        try:
            st2 = await valve.query_status()
        except TimeoutError:
            continue
        if st2["onCharge"] in ("01", "03"):
            print(f"  {C.ok('[OK]')} 轮询{i}: onCharge={st2['onCharge']}"
                  f" —— 设备已进入用水状态，热水阀开启（放水中）")
            CFG["last_open_mode"] = "online"
            # 两个随机数都要留，用途不同（实测区别很关键）：
            #   last_rn_pre —— 开阀前查询到的值，rateOrder 下单用的就是它
            #   last_rn     —— 开阀后设备返回的**新值**，本次消费记录绑定在它上面
            # 型号B 实测：只有"开阀后的值"配本次流水才能通过上传校验；
            #   用开阀前的值一律 errorCode=226「加密校验失败」。
            # 结算时按 last_rn → last_rn_pre 顺序尝试，两种固件都能覆盖。
            CFG["last_rn_pre"] = random_number
            CFG["last_rn"] = st2["tacTimes"] or random_number
            CFG["last_cm"] = comm_mode
            save_config()
            return True
        if st2["onCharge"] == "00":
            print(f"  {C.info('  [轮询]')} 设备仍空闲，等待 ...")
        else:
            print(f"  {C.info('  [轮询]')} 设备状态 onCharge={st2['onCharge']}")
    print(f"  {C.warn('[!]')} 轮询超时，未能确认放水")
    return False


async def ble_open_offline(valve, yukou_li=None, rate1=10, rate2=10, rate3=10):
    """离线开阀：不调用云端下单，本地构造费率帧直接下发。

    与在线方式的本质区别：
      在线  —— rateOrder 云端下单（预扣 5 元），关阀后结算，产生真实账单与扣费；
      离线  —— 只用设备 23 应答里的 projectId/accountId，预扣金额与费率本机自定，
               全程不接触云端订单接口，因此不产生扣费。
    下发按 21 → 17 → 31 指令降级，负载按 51B → 33B 降级，每步后都轮询确认是否出水。
    """
    print("  " + "-" * 56)
    print(f"  {C.bold('[B] 离线开阀')}  23 查询 → 本地构造费率 → 下发 → 轮询（无云端订单）")
    try:
        st = await _query_rw(valve)
    except TimeoutError as e:
        print(f"  {C.err('[!]')} 查询超时：{e}")
        return False
    if st["returnCode"] != "80":
        print(f"  {C.err('[!]')} 查询异常 returnCode={st['returnCode']}")
        return False
    if st["onCharge"] != "00":
        print(f"  {C.warn('[!]')} 设备状态 onCharge={st['onCharge']}，先补结算 …")
        if not await ensure_idle(valve, None):
            return False
        try:
            st = await _query_rw(valve)
        except TimeoutError:
            return False

    product_id = int(st["productId"], 16) or 1
    account_id = int(st["accountId"], 16) or 1001
    if not yukou_li:
        yukou_li = int(float(CFG.get("offline_yukou", 4.90)) * 1000)
    print(f"  {C.info('[*]')} 设备参数 projectId={product_id} accountId={account_id}"
          f"（取自设备 23 应答）")
    print(f"  {C.info('[*]')} 预扣 {yukou_li/1000:.2f} 元（本机自定）"
          f" | 费率 {rate1}/{rate2}/{rate3} 厘")

    # ---------- 关键前置：清旧会话（0x85 采集 → 0x86 清除）----------
    # 对齐 Java 版 v2.17 链（"账号0也采集，清旧会话保险"）：
    # 设备即使显示空闲，内部仍留着上次的账号与会话流水，不清掉就不会接受新费率。
    # 这两步实测无需密钥；且必须在"空闲态"做（结算中(03)时做会全被拒）。
    print(f"  {C.info('[*]')} 前置：清旧会话（0x85 采集 → 0x86 清除，无需密钥）")
    collect = None
    rc85 = ""
    try:
        r85 = await valve.send_and_wait("85", "00", timeout=8)
        raw85 = norm_hex(r85.get("data_area", ""))
        rc85 = raw85[:2]
        collect = raw85[2:]              # 去掉 status，对齐 Java respData 语义
        flag = C.ok("[OK]") if rc85 == "80" else C.warn("[!!]")
        print(f"  {flag} 85 采集 status={rc85} 数据={collect[:40]}…")
    except TimeoutError:
        print(f"  {C.warn('[!]')} 85 无回包")
    await asyncio.sleep(1.5)

    cleared = False
    if collect and rc85 == "80":
        for name, payload in clear_payload_candidates(collect, None):
            try:
                r86 = await valve.send_and_wait("86", payload, timeout=8)
                rc86 = norm_hex(r86.get("data_area", ""))[:2]
                if rc86 == "80":
                    print(f"  {C.ok('[OK]')} 86 清除成功（{name}）—— 设备就绪")
                    cleared = True
                    break
                print(f"  {C.warn('[!!]')} 86 变体「{name}」status={rc86}")
            except TimeoutError:
                print(f"  {C.warn('[!]')} 86 变体「{name}」无回包")
            await asyncio.sleep(1.2)
    if not cleared:
        print(f"  {C.warn('[!]')} 旧会话未清掉，设备可能拒绝新费率（继续尝试）")
    await asyncio.sleep(1)

    p51 = rate_payload_v2(product_id, account_id, yukou_li, rate1, rate2, rate3)
    p33 = rate_payload(product_id, account_id, yukou_li, rate1, rate2, rate3)
    print(f"  {C.info('[*]')} 费率负载 51B 共 {len(p51)//2} 字节；33B 共 {len(p33)//2} 字节")

    # 下发降级链：0x17 才是"下发费率"（Java 版 OP_RATE）；
    # 0x21 在这套协议里是密钥认证位，放最后兜底尝试。
    plan = [
        ("17", p51, "下发费率(17/51B)"),
        ("17", p33, "下发费率(17/33B)"),
        ("31", p51, "开始交易(31/51B)"),
        ("21", p51, "下发费率(21/51B)"),
        ("21", p33, "下发费率(21/33B)"),
    ]
    for cmd, payload, label in plan:
        print(f"  {C.info('[*]')} 尝试 {label} …")
        try:
            resp = await valve.send_and_wait(cmd, payload, timeout=8)
            rc = norm_hex(resp.get("data_area", ""))[:2]
            flag = C.ok("[OK]") if rc == "80" else C.warn("[!!]")
            print(f"  {flag} {label} 返回码 {rc}（{RC_TEXT.get(rc, '未知')}）")
        except TimeoutError:
            print(f"  {C.warn('[!]')} {label} 无回包")
        await asyncio.sleep(1.5)
        try:
            st2 = await valve.query_status()
            # 只有 01(使用中/放水) 才算真正开阀；
            # 03 是"结算中"（设备在等清账），那是失败态而不是成功态。
            if st2["onCharge"] == "01":
                print(f"  {C.ok('[★]')} 设备已放水（onCharge=01）"
                      f"—— 离线开阀成功，本条无云端扣费")
                CFG["last_open_mode"] = "offline"
                save_config()
                return True
            if st2["onCharge"] == "03":
                print(f"  {C.info('  [提示]')} 设备进入「结算中(03)」"
                      f"—— 本次未放水，继续尝试下一种指令")
        except TimeoutError:
            pass

    for i in range(1, 6):
        await asyncio.sleep(2)
        try:
            st2 = await valve.query_status()
        except TimeoutError:
            continue
        if st2["onCharge"] == "01":
            print(f"  {C.ok('[★]')} 轮询{i}: onCharge=01（放水中）—— 离线开阀成功")
            CFG["last_open_mode"] = "offline"
            save_config()
            return True
    print(f"  {C.warn('[!]')} 未能确认出水：该设备固件可能要求云端签发的费率/密钥，"
          f"请改用菜单 [1] 在线开阀")
    print(f"  {C.info('[*]')} 若设备停在「结算中(03)」，跑一次 "
          f"`python _cleanup_state.py` 清理后再试")
    return False


# ================= 结算（关阀清账：云端优先 + 本地变体兜底） =================
# 移植自 watercontrol / KlcxkjProto v2.14 的容错设计：
#   · 85 采集应答的数据区即"消费流水"，同时充当 86 清账负载的本地来源
#   · 云端 clearCode 优先；云端不可用/解密失败时，用采集数据按字节数变体逐个清账
#   · 22 / 85 允许重试；83(未采集消费数据) 自动补一次清账再采集
#   · 最后 23 轮询 onCharge 回到 00 才算结算完成
# 原实现的问题：云端上传失败或 AES 解密失败直接 return False，
# 设备会停留在「结算中(03)」，导致下次开阀被拒。

# 应答返回码（dataArea 前 1 字节）
RC_TEXT = {
    "80": "成功",
    "81": "包头错误",
    "82": "包长度错误",
    "83": "未采集消费数据（需先采集并清除）",
    "84": "功能码错误",
    "85": "数据格式错误",
}

# 86 清账负载的本地变体（取采集数据的前 N 字节；-1 = 全长）
CLEAR_LOCAL_VARIANTS = [
    ("采集前22B(官方)", 22),
    ("采集前41B", 41),
    ("采集前23B", 23),
    ("采集全长", -1),
    ("采集前27B", 27),
    ("采集前6B", 6),
]

SETTLE_LOG = os.path.join(_data_dir(), "settle_log.json")

# 零用量消费流水（42 字节）：业务字段全零，只保留设备标识（deviceId=013D、MAC 尾 5B）。
# 用途：关阀结算时用它替换真实流水上报，云端据此算出的费用为 0，
#       同时仍会返回合法 clearCode 供设备 86 清账 —— 订单正常了结、押金退回。
# 该格式已实测被服务端接受（返回 clearCode）。
ZERO_USAGE_PAYLOAD = ("000000000000000000000000013D0000000000000000000000000000000000"
                      "00000000158303783C")

# ---------------- 流水型号自适应 ----------------
# 已知两种固件的 85 采集流水（完整 data_area）：
#   42B —— 老固件，到 MAC 尾段结束（例：80260915174516 0000040C 0000013D ... AABBCCDDEEFF）
#   62B —— 新固件，MAC 尾段后还带 20B 扩展段（实测 00000001 0036 000B01 + 6B 零 + 5B 校验）
# 两者**前 42 字节布局完全一致**，差异只在尾部扩展段。
# 因此所有解析都按「固定基础段 + 自适应扩展段」做，绝不写死总长度 ——
# 早期版本把 62B 砍成 42B 上报，被服务端直接拒（"网络出错了，请稍后重试"）。
FLOW_BASE_LEN = 42


def parse_flow(collect_hex):
    """解析 85 采集到的完整流水，自适应型号长度。

    返回 dict（长度不足基础段时 ok=False，其余字段不存在）：
      status/time/productId/deviceId/accountId/accountType/useCount/prepay/
      rate1/rate2/mac + ext（型号扩展段，老固件为空）
    """
    c = norm_hex(collect_hex)
    n = len(c) // 2
    if n < FLOW_BASE_LEN:
        return {"ok": False, "len": n, "raw": c}
    f = {
        "ok": True, "len": n, "raw": c,
        "status": c[0:2],
        "time": c[2:14],
        "productId": c[14:22],
        "deviceId": c[22:30],
        "accountId": c[30:38],
        "accountType": c[38:40],
        "useCount": c[40:48],
        "prepay": c[48:56],
        "rate1": c[56:64],
        "rate2": c[64:72],
        "mac": c[72:84],
        "ext": c[84:],
    }
    for key, src in (("useCount_i", "useCount"), ("prepay_i", "prepay"),
                     ("rate1_i", "rate1"), ("rate2_i", "rate2"),
                     ("deviceId_i", "deviceId"), ("productId_i", "productId")):
        try:
            f[key] = int(f[src], 16)
        except ValueError:
            f[key] = None
    return f


def flow_brief(f):
    """流水的一句话摘要（用于日志）。f 为 parse_flow 的结果。"""
    if not f.get("ok"):
        return f"{f.get('len', 0)}B（短于基础布局 {FLOW_BASE_LEN}B，无法解析）"
    s = (f"{f['len']}B status={f['status']} 时间={f['time']} "
         f"productId={f['productId_i']} deviceId={f['deviceId_i']} "
         f"次数={f['useCount_i']} 预扣={f['prepay_i']} "
         f"费率={f['rate1_i']}/{f['rate2_i']} MAC={f['mac']}")
    if f["ext"]:
        s += f" 扩展段={len(f['ext']) // 2}B"
    return s


def flow_model_tag(f):
    """按流水形状给出型号标签，用于 Profile 记忆与日志"""
    if not f.get("ok"):
        return "unknown"
    if not f["ext"]:
        return f"base{FLOW_BASE_LEN}"
    return f"ext{FLOW_BASE_LEN}+{len(f['ext']) // 2}"


# ---------------- 设备档案（Profile）记忆 ----------------
# 每个设备记住：流水型号、上次成功的清账方式、上次成功的帧风格。
# 同一设备再来时直接用已验证过的方案，不再逐个试错（86 每个变体要等 1.2 秒）。
# key 优先 deviceId：MAC 会因设备复位重新生成，deviceId 才是云端认定的设备身份。


def device_profile_key():
    """当前设备档案的 key：优先 deviceId，退回 MAC；都没有则空串"""
    did = CFG.get("last_dev_id")
    if did:
        return f"dev{did}"
    mac = (CFG.get("mac") or "").replace(":", "").replace("-", "").upper()
    return f"mac{mac}" if mac else ""


def profile_get():
    """取当前设备的档案（没有则空 dict）"""
    k = device_profile_key()
    if not k:
        return {}
    return (CFG.get("profiles") or {}).get(k) or {}


def profile_update(**kv):
    """合并更新当前设备档案并落盘"""
    k = device_profile_key()
    if not k:
        return
    profs = CFG.setdefault("profiles", {})
    cur = profs.setdefault(k, {})
    cur.update(kv)
    cur["ts"] = time.time()
    save_config()


def zero_usage_from(collect):
    """在**真实流水**基础上清零计费字段，生成零用量上报数据（型号自适应）。

    输入必须是**完整 data_area（含 status 0x80）** —— 服务端要的就是这种形式，
    实测用户成功上传的样本同样是 80 开头。**长度也必须与设备返回的一致**，
    否则服务端直接回"网络出错了，请稍后重试"。

    基础布局（前 42 字节，所有已知型号一致）：
      [0:2]   status(80)     [2:14]  时间(BCD)      [14:22] productId
      [22:30] deviceId       [30:38] accountId     [38:40] accountType
      [40:48] useCount       [48:56] 预扣金额      [56:64] 费率1
      [64:72] 费率2          [72:84] MAC 尾段

    保留 status/时间/产品/设备/账号/MAC 与**型号扩展段**，只清零计费字段。

    **清零范围按型号区分（实测差异，很关键）**：
      老固件（42B，无扩展段）—— useCount/预扣/费率 全清零，实测被服务端接受
      新固件（62B，有扩展段）—— **只能清 useCount + 预扣，费率必须原样保留**。
        服务端会拿上报的费率与它自己下发的 downData 比对，费率被清零会被判
        「errorCode=226 加密校验失败」。实测对照：
          清 useCount           → 成功
          清 预扣               → 成功
          清 useCount+预扣      → 成功
          清 rate1/rate2        → 226
          清全部四个            → 226
        费用侧：用量与预扣都已归零，云端按 0 计费。
    """
    f = parse_flow(collect)
    if not f["ok"]:
        print(f"  {C.warn('[!]')} 流水仅 {f['len']}B（短于基础布局 {FLOW_BASE_LEN}B），"
              f"改用内置模板兜底 —— 模板里的设备标识可能与当前设备不符")
        return ZERO_USAGE_PAYLOAD
    keep_rate = bool(f["ext"])       # 新型号：费率要留着过服务端校验
    return (f["status"] + f["time"] + f["productId"] + f["deviceId"]
            + f["accountId"] + f["accountType"]
            + "00" * 8               # useCount + 预扣 → 归零（云端据此算 0）
            + (f["rate1"] + f["rate2"] if keep_rate else "00" * 8)
            + f["mac"] + f["ext"])   # MAC 与型号扩展段原样保留，长度不失真


def rc_of(data_area):
    """取应答返回码：返回 (rc, 中文说明)"""
    da = norm_hex(data_area)
    rc = da[:2]
    return rc, RC_TEXT.get(rc, f"未知({rc})")


def clear_payload_candidates(collect_hex, clear_code=None, full=None, prefer=None):
    """生成 86 清账负载候选 [(来源, 负载hex), ...]，按可靠性排序、自动去重。

    collect_hex —— 剥离 status 的采集数据（对齐服务端 respData 语义）
    full        —— 含 status 的完整 data_area；**部分型号固件只认这种形式**
    prefer      —— 上次成功过的变体名（Profile 记忆），命中则提到最前面

    变体全部**按实际长度自适应**：老固件 42B、新固件 62B，都能生成对应的全长负载，
    不会因为写死 42B 而让新型号永远试不中。
    """
    out = []
    seen = set()

    def add(name, payload):
        p = norm_hex(payload)
        if p and p not in seen:
            seen.add(p)
            out.append((name, p))

    # 官方路径：云端返回的 clearCode（有它就不需要猜本地格式）
    add("云端clearCode", clear_code)

    cands = []
    hs = norm_hex(collect_hex)
    if hs:
        n = len(hs) // 2
        for name, size in CLEAR_LOCAL_VARIANTS:
            take = n if size == -1 else min(size, n)
            if take > 0:
                cands.append((name, hs[:take * 2]))

    # 含 status 的完整形式：新型号固件可能只认它（实测老固件两者都拒）
    hf = norm_hex(full) if full else ""
    if hf:
        cands.append(("含status全长", hf))
        base = min(FLOW_BASE_LEN, len(hf) // 2)
        if base > 0:
            cands.append((f"含status基础{FLOW_BASE_LEN}B", hf[:base * 2]))

    if prefer:
        cands.sort(key=lambda t: 0 if t[0] == prefer else 1)
    for name, payload in cands:
        add(name, payload)
    return out


def record_settle(entry):
    """结算结果落盘（最近 200 条），便于事后对账"""
    try:
        data = []
        if os.path.exists(SETTLE_LOG):
            with open(SETTLE_LOG, "r", encoding="utf-8") as f:
                data = json.load(f) or []
        data.insert(0, entry)
        with open(SETTLE_LOG, "w", encoding="utf-8") as f:
            json.dump(data[:200], f, ensure_ascii=False, indent=2)
    except Exception as e:
        dbg("记录结算失败:", e)


# ================= 未完成结算的「待办」标记 =================
# 蓝牙断开 / 程序被杀 / 结算中途失败时，设备端的流水会一直留在表里，
# 用量还会继续累加 —— 下一次结算会把之前所有没结算的用量一起上传（用户
# 看到的就是「平时不扣，一扣就把前面的全扣了」）。
# 所以：进入结算就落一个标记，确认收尾（onCharge=00）才删除。
# 下次启动 / 连上设备时看到标记，先自动做一次零用量补清账，再谈别的。
PENDING_FILE = os.path.join(_data_dir(), "pending_settle.json")


def pending_mark(**kv):
    try:
        kv.setdefault("ts", time.time())
        kv.setdefault("mac", CFG.get("mac") or "")
        with open(PENDING_FILE, "w", encoding="utf-8") as f:
            json.dump(kv, f, ensure_ascii=False, indent=2)
    except Exception as e:
        dbg("写待办标记失败:", e)


def pending_clear():
    try:
        if os.path.exists(PENDING_FILE):
            os.remove(PENDING_FILE)
    except Exception as e:
        dbg("清待办标记失败:", e)


def pending_read():
    try:
        # utf-8-sig：兼容被记事本/PowerShell 写过的带 BOM 文件
        with open(PENDING_FILE, encoding="utf-8-sig") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except Exception:
        return {}


# ================= 断线自愈的收发 =================
# 人走开、门一关，BLE 就掉。原来一发失败整段结算就直接放弃，
# 设备停在「结算中」，流水越积越多。现在每次收发都先确认链路，
# 掉线就重连再重发（重连不影响已经开着的阀门）。
async def _link_ok(valve):
    try:
        return bool(valve.client and valve.client.is_connected)
    except Exception:
        return False


async def _send_rw(valve, cmd, data="00", timeout=10.0, tries=3):
    """带重连重试的发送（22/85/86 等）"""
    last = None
    for i in range(1, tries + 1):
        try:
            if not await _link_ok(valve):
                print(f"  {C.warn('[!]')} 链路已断，重连后再发 {cmd}"
                      f"（第 {i}/{tries} 次）...")
                if not await valve.connect():
                    raise TimeoutError("重连失败")
            return await valve.send_and_wait(cmd, data, timeout=timeout)
        except Exception as e:                      # noqa: BLE001
            last = e
            if i >= tries:
                break
            await asyncio.sleep(1.5)
            try:
                await valve.disconnect()
            except Exception:
                pass
    raise last if isinstance(last, TimeoutError) else TimeoutError(str(last))


async def _query_rw(valve, tries=3):
    """带重连重试的 23 查询"""
    last = None
    for i in range(1, tries + 1):
        try:
            if not await _link_ok(valve):
                print(f"  {C.warn('[!]')} 链路已断，重连后再查询（第 {i}/{tries} 次）...")
                if not await valve.connect():
                    raise TimeoutError("重连失败")
            return await valve.query_status()
        except Exception as e:                      # noqa: BLE001
            last = e
            if i >= tries:
                break
            await asyncio.sleep(1.2)
            try:
                await valve.disconnect()
            except Exception:
                pass
    raise last if isinstance(last, TimeoutError) else TimeoutError(str(last))


async def ble_settle(valve, s, random_number="", comm_mode="", tag="2] 关阀",
                     offline=False, zero_usage=False):
    """完整结算：22 结束 → 85 采集 → 云端上传 → 86 清账(多变体) → 23 确认。

    offline=True    ：跳过云端上传（流水不落地，但订单会挂着不结算、押金不退）
    zero_usage=True ：正常上传，但**用零用量流水替换真实流水** ——
                      订单正常了结、押金退回，而云端按 0 用量计费

    返回 dict：{ok, ended, collect, upload, clear_by, onCharge, ts, offline, zero_usage}
    """
    # ★ 最后一道闸：只要没显式打开 WV_ALLOW_REAL_BILL，任何调用点传进来的
    #   zero_usage=False 都被强制改回 True。这样即使别处漏改、或以后新增调用，
    #   也不会再把真实用量传到云端产生账单。
    if not zero_usage and not ALLOW_REAL_BILL:
        print(f"  {C.warn('[!]')} 拦截真实用量上报 → 已改为零用量结算"
              f"（要真实计费请设环境变量 WV_ALLOW_REAL_BILL=1）")
        zero_usage = True
    res = {"ok": False, "ended": False, "collect": None, "upload": False,
           "clear_by": None, "onCharge": None, "ts": time.time(),
           "offline": offline, "zero_usage": zero_usage}
    # 进入结算先落待办：中途掉线 / 被杀进程，下次启动也能接着零用量收拾
    pending_mark(kind="settle", tag=tag, zero_usage=zero_usage, offline=offline)
    print("  " + "-" * 56)
    mode = "离线结算（不上报云端）" if offline else "在线结算"
    print(f"  {C.bold(f'[{tag} 结算')}  {mode}：22 结束 → 85 采集 → 86 清账 → 23 确认")

    # ---------- 1) 22 结束交易 ----------
    for i in (1, 2):
        try:
            resp22 = await _send_rw(valve, "22", "00", timeout=10)
            print(f"  {C.ok('[OK]')} 22 结束回帧 raw={resp22['raw']}")
            res["ended"] = True
            break
        except TimeoutError as e:
            print(f"  {C.warn('[!]')} 22 第{i}次无回包（{e}）")
            await asyncio.sleep(1.5)
    if not res["ended"]:
        print(f"  {C.warn('[!]')} 22 未确认（部分设备静默执行），继续尝试采集")
    await asyncio.sleep(2)

    # ---------- 2) 85 采集消费数据（含 83 错误码补救） ----------
    collect = None          # 剥离 status：供 86 清账作负载
    collect_full = None     # 保留 status：供上报云端作 xfData
    for i in (1, 2, 3):
        try:
            resp85 = await _send_rw(valve, "85", "00", timeout=10)
        except TimeoutError as e:
            print(f"  {C.warn('[!]')} 85 第{i}次无回包（{e}）")
            await asyncio.sleep(2)
            continue
        da = resp85.get("data_area", "")
        rc, txt = rc_of(da)
        if rc == "80":
            # 同一份采集数据两处用，形式不同：
            #   collect_full —— 上报云端的 xfData，必须原样含 status（80 开头）
            #   collect      —— 86 清账负载，必须剥离 status（对齐 Java respData）
            collect_raw = norm_hex(da)
            collect_full = collect_raw
            collect = collect_raw[2:] if collect_raw.startswith("80") else collect_raw
            prof = parse_flow(collect_full)
            print(f"  {C.ok('[OK]')} 85 采集成功（完整 {len(collect_full)//2}B / "
                  f"清账用 {len(collect)//2}B，型号 {flow_model_tag(prof)}）")
            print(f"      {flow_brief(prof)}")
            if prof["ok"]:
                old_tag = (CFG.get("profiles") or {}).get(device_profile_key(), {}).get("model")
                if old_tag and old_tag != flow_model_tag(prof):
                    print(f"  {C.warn('[!]')} 型号与上次记录不同"
                          f"（上次 {old_tag}，本次 {flow_model_tag(prof)}）—— 可能换了设备或固件升级")
            break
        print(f"  {C.warn('[!]')} 85 返回码 {rc}（{txt}）")
        if rc == "83":
            print(f"  {C.info('[*]')} 按设备提示先补一次清账，再重试采集 ...")
            try:
                await _send_rw(valve, "86", "00", timeout=8)
            except TimeoutError:
                pass
            await asyncio.sleep(1.5)
        elif rc in ("81", "82", "85"):
            await asyncio.sleep(1.5)
        else:
            break
    res["collect"] = collect
    if not collect:
        print(f"  {C.err('[!]')} 未能取得消费数据，结算中止（设备可能仍在结算中）")
        record_settle(res)
        return res

    # ---------- 3) 补齐云端参数 ----------
    # 上传用的 randomNumber 有讲究：设备在收到 downData 后会把 tacTimes 换成**新值**，
    # 本次消费记录绑定在那个新值上。实测（型号B）：
    #   用「开阀后的新值」+ 本次流水 → 服务端接受
    #   用「开阀前的旧值」+ 本次流水 → errorCode=226 加密校验失败
    # 因此按 开阀后 → 开阀前 → 现查 的顺序依次尝试，两种固件都能覆盖。
    if not comm_mode:
        comm_mode = CFG.get("last_cm") or ""
    rn_cands = []
    for cand in (CFG.get("last_rn"), CFG.get("last_rn_pre"), random_number):
        c = (cand or "").strip().upper()
        if c and c not in rn_cands:
            rn_cands.append(c)
    if not rn_cands:
        for _ in range(2):
            try:
                st = await _query_rw(valve)
                rn_cands.append(st["tacTimes"])
                comm_mode = comm_mode or st["commMode"]
                break
            except TimeoutError:
                await asyncio.sleep(1.5)
    if rn_cands:
        tag = "开阀后" if CFG.get("last_rn") else "现查"
        print(f"  {C.info('[*]')} 上传候选 randomNumber={rn_cands}（首个为{tag}值）"
              f" commMode={comm_mode}")
    random_number = rn_cands[0] if rn_cands else ""

    # ---------- 4) 云端上传（离线模式跳过；失败不中断，降级本地兜底） ----------
    clear_code = None
    if offline:
        print(f"  {C.info('[*]')} 离线结算：跳过云端上传"
              f"（消费流水只用于本地清账，服务器收不到 → 不产生账单）")
    elif s and rn_cands and comm_mode:
        xf = zero_usage_from(collect_full) if zero_usage else collect_full
        if zero_usage:
            rc_n = len(norm_hex(collect_full))
            print(f"  {C.info('[*]')} 零扣费模式：上报零用量流水"
                  f"（真实 {rc_n//2}B → 上报 {len(xf)//2}B，"
                  f"长度对齐={'是' if rc_n == len(xf) else '否'}，含status="
                  f"{'是' if xf.startswith('80') else '否'}）")
        for i, rn_try in enumerate(rn_cands, 1):
            print(f"  {C.info('[*]')} 云端上传账单 upload/bluetooth/data "
                  f"（尝试 {i}/{len(rn_cands)}，randomNumber={rn_try}）...")
            cl_data = ble_upload(s, xf, rn_try, comm_mode)
            if cl_data:
                random_number = rn_try
                break
            if i < len(rn_cands):
                print(f"  {C.warn('[!]')} 该随机数未匹配上订单，换下一个 ...")
                await asyncio.sleep(2)
        if cl_data:
            res["upload"] = True
            clear_code = aes_decrypt(cl_data)
            if clear_code:
                print(f"  {C.ok('[OK]')} 云端返回 clearCode={clear_code}")
            else:
                print(f"  {C.warn('[!]')} 云端账单解密失败，转本地采集数据清账")
        else:
            print(f"  {C.warn('[!]')} 云端上传失败 → 改走本地清账")
            print(f"  {C.warn('    ')} ⚠ 本地清账会抹掉设备端流水，"
                  f"此订单将无法再补结算（押金可能挂着不退）")
    else:
        print(f"  {C.warn('[!]')} 缺少云端参数，直接走本地清账")

    # ---------- 5) 86 清账（多变体逐个尝试；自适应型号长度） ----------
    pref = profile_get().get("clear_by")
    if pref:
        print(f"  {C.info('[*]')} 设备档案：上次清账方式「{pref}」，优先重试")
    for name, payload in clear_payload_candidates(collect, clear_code,
                                                  full=collect_full, prefer=pref):
        try:
            resp86 = await _send_rw(valve, "86", payload, timeout=10)
            da = resp86.get("data_area", "")
            rc, txt = rc_of(da)
            if rc == "80":
                print(f"  {C.ok('[OK]')} 86 清账成功（{name}）raw={resp86['raw']}")
                res["clear_by"] = name
                break
            print(f"  {C.warn('[!]')} 86 变体「{name}」返回码 {rc}（{txt}）")
        except TimeoutError:
            print(f"  {C.warn('[!]')} 86 变体「{name}」无回包")
        await asyncio.sleep(1.2)
    if not res["clear_by"]:
        print(f"  {C.warn('[!]')} 所有清账变体均未确认，等待设备自行结算")

    # ---------- 6) 23 确认回到空闲 ----------
    for i in range(1, 6):
        await asyncio.sleep(2)
        try:
            st = await _query_rw(valve)
        except TimeoutError:
            continue
        res["onCharge"] = st["onCharge"]
        if st["onCharge"] == "00":
            res["ok"] = True
            print(f"  {C.ok('[OK]')} 结算完成，设备已回空闲（onCharge=00）")
            break
        print(f"  {C.info('  [确认]')} onCharge={st['onCharge']}"
              f"（{ONCHARGE_TEXT.get(st['onCharge'], '未知')}），继续等待 ...")
    if not res["ok"]:
        print(f"  {C.warn('[!]')} 未确认到空闲态，请稍后用 [3] 查询状态")

    # 记录设备档案：型号形状 + 上次成功的清账方式，下次直接命中，不再逐个试错
    prof_now = parse_flow(collect_full or collect or "")
    upd = {
        "model": flow_model_tag(prof_now),
        "flow_len": len(norm_hex(collect_full or "")) // 2,
        "last_settle_ts": time.time(),
        "last_upload_ok": bool(res.get("upload")),
        "last_zero_usage": bool(zero_usage),
    }
    if res.get("clear_by"):
        upd["clear_by"] = res["clear_by"]
    profile_update(**upd)

    record_settle(res)
    if res.get("ok"):
        pending_clear()      # 收尾成功：设备已回空闲，待办标记消掉
    else:
        print(f"  {C.warn('[!]')} 结算未收尾，已记待办 —— 下次连上会自动零用量补清账")
    return res


async def ble_reclear(valve, s, zero_usage=True):
    """补清账：设备已回空闲、但设备端流水还没清除时，补做后半段结算。

    用于收拾上一次失败的结算 —— 上传被拒、86 又不被接受，设备状态回到 00，
    但流水仍留在设备里、云端订单还挂着（押金不退）。
    与完整结算的区别：**不发 22**（交易已结束，重复发会干扰设备）。

    返回结构与 ble_settle 一致（另带 reclear=True）。
    """
    print("  " + "-" * 56)
    print(f"  {C.bold('[D] 补清账')}  85 采集 → 云端上传 → 86 清账 → 23 确认（不发 22）")
    res = {"ok": False, "collect": None, "upload": False, "clear_by": None,
           "onCharge": None, "ts": time.time(), "reclear": True,
           "zero_usage": zero_usage}
    pending_mark(kind="reclear", zero_usage=zero_usage)

    # ---------- 1) 查询状态：拿到 onCharge / 订单参数 ----------
    try:
        st = await _query_rw(valve)
    except TimeoutError:
        print(f"  {C.err('[!]')} 查询状态无回包，补清账中止")
        return res
    print(f"  {C.info('[*]')} onCharge={st['onCharge']}"
          f"（{ONCHARGE_TEXT.get(st['onCharge'], '未知')}）"
          f" randomNumber={st['tacTimes']} commMode={st['commMode']}")

    # ---------- 2) 85 采集 ----------
    collect = collect_full = None
    for i in (1, 2, 3):
        try:
            resp85 = await _send_rw(valve, "85", "00", timeout=10)
        except TimeoutError:
            print(f"  {C.warn('[!]')} 85 第{i}次无回包")
            await asyncio.sleep(2)
            continue
        da = resp85.get("data_area", "")
        rc, txt = rc_of(da)
        if rc == "80":
            collect_full = norm_hex(da)
            collect = collect_full[2:] if collect_full.startswith("80") else collect_full
            prof = parse_flow(collect_full)
            print(f"  {C.ok('[OK]')} 85 采集成功（完整 {len(collect_full) // 2}B / "
                  f"清账用 {len(collect) // 2}B，型号 {flow_model_tag(prof)}）")
            print(f"      {flow_brief(prof)}")
            break
        print(f"  {C.warn('[!]')} 85 返回码 {rc}（{txt}）")
        if rc == "83":          # 设备明说没有待采集数据
            break
        await asyncio.sleep(1.5)
    if not collect_full:
        print(f"  {C.ok('[OK]')} 设备端没有待清流水 —— 无需补清账")
        pending_clear()          # 设备已经干净，待办标记可以销掉
        return res
    res["collect"] = collect

    # ---------- 3) 云端上传（按 开阀后 → 开阀前 → 现查 顺序试随机数） ----------
    cm = CFG.get("last_cm") or st["commMode"]
    rn_cands = []
    for cand in (CFG.get("last_rn"), CFG.get("last_rn_pre"), st["tacTimes"]):
        c = (cand or "").strip().upper()
        if c and c not in rn_cands:
            rn_cands.append(c)
    clear_code = None
    cl_data = None
    if s:
        xf = zero_usage_from(collect_full) if zero_usage else collect_full
        print(f"  {C.info('[*]')} 上传候选 randomNumber={rn_cands} commMode={cm}")
        print(f"  {C.info('[*]')} 上传{'零用量' if zero_usage else '真实'}流水"
              f"（{len(norm_hex(xf)) // 2}B / 采集 {len(collect_full) // 2}B，"
              f"长度对齐={'是' if len(norm_hex(xf)) == len(collect_full) else '否'}）")
        for i, rn_try in enumerate(rn_cands, 1):
            print(f"  {C.info('[*]')} 上传尝试 {i}/{len(rn_cands)}："
                  f"randomNumber={rn_try}")
            cl_data = ble_upload(s, xf, rn_try, cm)
            if cl_data:
                break
            if i < len(rn_cands):
                await asyncio.sleep(2)
        if cl_data:
            res["upload"] = True
            clear_code = aes_decrypt(cl_data)
            if clear_code:
                print(f"  {C.ok('[OK]')} 云端返回 clearCode={clear_code}")
            else:
                print(f"  {C.warn('[!]')} 账单解密失败，转本地清账")
        else:
            print(f"  {C.warn('[!]')} 云端上传失败 → 改走本地清账")
    else:
        print(f"  {C.warn('[!]')} 无有效账号会话，跳过上传")

    # ---------- 4) 86 清账 ----------
    pref = profile_get().get("clear_by")
    if pref:
        print(f"  {C.info('[*]')} 设备档案：上次清账方式「{pref}」，优先重试")
    for name, payload in clear_payload_candidates(collect, clear_code,
                                                  full=collect_full, prefer=pref):
        try:
            resp86 = await _send_rw(valve, "86", payload, timeout=10)
            rc, txt = rc_of(resp86.get("data_area", ""))
            if rc == "80":
                print(f"  {C.ok('[OK]')} 86 清账成功（{name}）raw={resp86['raw']}")
                res["clear_by"] = name
                break
            print(f"  {C.warn('[!]')} 86 变体「{name}」返回码 {rc}（{txt}）")
        except TimeoutError:
            print(f"  {C.warn('[!]')} 86 变体「{name}」无回包")
        await asyncio.sleep(1.2)
    if not res["clear_by"]:
        print(f"  {C.warn('[!]')} 所有清账变体均未确认")

    # ---------- 5) 确认空闲 ----------
    for _ in range(3):
        await asyncio.sleep(2)
        try:
            st2 = await valve.query_status()
        except TimeoutError:
            continue
        res["onCharge"] = st2["onCharge"]
        if st2["onCharge"] == "00":
            res["ok"] = True
            break

    upd = {"model": flow_model_tag(parse_flow(collect_full)),
           "flow_len": len(collect_full) // 2,
           "last_reclear_ts": time.time(),
           "last_upload_ok": bool(res.get("upload"))}
    if res.get("clear_by"):
        upd["clear_by"] = res["clear_by"]
    profile_update(**upd)
    record_settle(res)
    if res.get("ok"):
        pending_clear()
    print(f"  {C.ok('[完成]')} 补清账结束：upload={res['upload']} "
          f"clear_by={res['clear_by'] or '未确认'} onCharge={res['onCharge']}")
    return res


async def upload_diag(valve, s):
    """上传接口诊断：定位 /order/upload/bluetooth/data 的 errorCode（如 226「加密校验失败」）。

    逐项只改一个变量（随机数 / protocolType / 附带参数 / 签名串字段组合），
    看哪一项能让服务端接受 —— 避免凭猜测改签名规则。

    安全边界：**只提交零用量数据**（计费段清零），即使被服务端接受也不产生费用。
    仅做一次 85 采集（只读，不清账），不改变设备状态。
    """
    print("  " + "-" * 56)
    print(f"  {C.bold('[E] 上传诊断')}  逐项改参数，定位上传被拒的原因")
    if not s:
        print(f"  {C.err('[!]')} 未登录，无法诊断")
        return
    if not (valve.client and valve.client.is_connected):
        print(f"  {C.warn('[!]')} 设备未连接：先按 [4] 选设备，或按 [1] 触发连接")
        return

    try:
        resp85 = await _send_rw(valve, "85", "00", timeout=10)
    except TimeoutError:
        print(f"  {C.err('[!]')} 85 采集无回包")
        return
    da = resp85.get("data_area", "")
    rc, txt = rc_of(da)
    if rc != "80":
        print(f"  {C.err('[!]')} 85 返回 {rc}（{txt}），取不到流水，无法诊断")
        return
    full = norm_hex(da)
    prof = parse_flow(full)
    print(f"  {C.ok('[OK]')} 采集到流水（{flow_model_tag(prof)}）")
    print(f"      {flow_brief(prof)}")
    if prof.get("useCount_i"):
        print(f"  {C.warn('[!]')} 该流水含用量 {prof['useCount_i']}，"
              f"诊断一律提交零用量版本，不会产生费用")
    zf = zero_usage_from(full)
    rn0 = CFG.get("last_rn") or ""
    cm0 = CFG.get("last_cm") or "20"
    print(f"  数据：真实 {len(full) // 2}B → 提交零用量 {len(zf) // 2}B"
          f"（对齐={'是' if len(full) == len(zf) else '否'}）")
    print(f"  订单参数：randomNumber={rn0 or '(空)'} commMode={cm0}\n")

    results = []

    def attempt(tag, xf, rn_v, cm_v, sig_param=None, extra=None):
        if sig_param is None:
            sig_param = f"loginCode{s['loginCode']}telephone{s['telephone']}xfData{xf}"
        body = {
            "loginCode": s["loginCode"], "protocolType": cm_v,
            "telephone": s["telephone"], "userId": s["userId"],
            "accountId": s["accountId"], "xfData": xf,
            "randomNumber": rn_v, "projectId": s["projectId"],
            "signature": ble_signature(sig_param, s["loginCode"]),
        }
        if extra:
            body.update(extra)
        d = api("POST", "/order/upload/bluetooth/data", body)
        code = (d or {}).get("errorCode")
        msg = (d or {}).get("errorMessage") or ""
        data = (d or {}).get("data") or {}
        cl = data.get("clData")
        mark = C.ok("✓") if code == 0 else " "
        print(f"  [{mark}] {tag:<30} errorCode={code}  {msg}")
        if cl:
            cc = aes_decrypt(cl)
            print(f"        {C.ok('→ 拿到 clData，clearCode=' + str(cc))}")
        if DEBUG and code != 0:
            print(f"        {C.warn('[DBG]')} 响应={d}")
        results.append((tag, code, bool(cl)))
        return code

    lg = s["loginCode"]

    print("== A. 复现现行请求")
    attempt("A1 现行签名", zf, rn0, cm0)
    await asyncio.sleep(1.2)

    print("\n== B. 换 randomNumber（结算阶段设备会给出另一个值）")
    attempt("B1 rn=空", zf, "", cm0)
    await asyncio.sleep(1.2)
    try:
        st_now = await valve.query_status()
        attempt("B2 rn=刚查询的设备值", zf, st_now["tacTimes"], st_now["commMode"])
    except TimeoutError:
        print("    B2 跳过：查询设备无回包")
    await asyncio.sleep(1.2)

    print("\n== C. 换 protocolType（设备回的是字符串 '20'，也许要 hex 0x20=32）")
    attempt("C1 protocolType=32", zf, rn0, 32)
    await asyncio.sleep(1.2)

    print("\n== D. 补上 rateOrder 用过、upload 没传的类型参数")
    attempt("D1 附 smallTypeId/bigTypeId", zf, rn0, cm0,
            extra={"smallTypeId": SMALL_TYPE_ID, "bigTypeId": BIG_TYPE_ID})
    await asyncio.sleep(1.2)

    print("\n== E. 换签名串（服务端可能要求更多字段参与签名）")
    attempt("E1 加 protocolType", zf, rn0, cm0,
            sig_param=f"loginCode{lg}protocolType{cm0}telephone{s['telephone']}xfData{zf}")
    await asyncio.sleep(1.2)
    attempt("E2 加 randomNumber", zf, rn0, cm0,
            sig_param=f"loginCode{lg}randomNumber{rn0}telephone{s['telephone']}xfData{zf}")
    await asyncio.sleep(1.2)
    attempt("E3 加 userId", zf, rn0, cm0,
            sig_param=f"loginCode{lg}telephone{s['telephone']}userId{s['userId']}xfData{zf}")
    await asyncio.sleep(1.2)
    attempt("E4 全字段(字母序)", zf, rn0, cm0,
            sig_param=(f"accountId{s['accountId']}loginCode{lg}protocolType{cm0}"
                       f"randomNumber{rn0}telephone{s['telephone']}"
                       f"userId{s['userId']}xfData{zf}"))
    await asyncio.sleep(1.2)

    print("\n== F. 提交原始采集数据（不改动）")
    if prof.get("useCount_i") or prof.get("rate1_i") or prof.get("rate2_i"):
        print(f"  {C.warn('[跳过]')} 原始数据含用量/费率"
              f"（次数={prof.get('useCount_i')} 费率={prof.get('rate1_i')}/"
              f"{prof.get('rate2_i')}）—— 提交它会计费，诊断不做这件事")
    else:
        attempt("F1 原始数据(本身即零用量)", full, rn0, cm0)

    print("\n  " + "-" * 52)
    ok = [(t, c) for t, c, _ in results if c == 0]
    codes = {}
    for _, c, _ in results:
        codes[c] = codes.get(c, 0) + 1
    print(f"  诊断汇总（共 {len(results)} 次请求）：")
    for c, n in sorted(codes.items(), key=lambda kv: str(kv[0])):
        print(f"    errorCode={c}  出现 {n} 次")
    if ok:
        print(f"  {C.ok('[成功]')} 可用组合：{[t for t, _ in ok]}")
    else:
        print(f"  {C.warn('[!]')} 全部组合都被拒 —— 说明不是签名/参数问题，"
              f"更可能是数据内容本身（如时间为零、productId=0 无法匹配订单）")
    print(f"  {C.info('[*]')} 结论请把上面整段输出发回（不含账号密码，可安全外发）")


async def ensure_idle(valve, s, tag="前置"):
    """开阀前确保设备可开：若停留在「结算中(03)」，先补一次结算。

    对应 KlcxkjMainActivity v2.14：设备带未采集消费数据时会拒绝后续指令。
    """
    try:
        st = await _query_rw(valve)
    except TimeoutError:
        return True          # 查询失败不阻断，后续开阀流程自身会报错
    oc = st["onCharge"]
    if oc == "00":
        return True
    if oc == "03":
        print(f"  {C.warn('[!]')} 设备处于「结算中」，自动补结算后再开阀 ...")
        # ★ 关键：这里原来用默认 zero_usage=False，会把上一笔**遗留流水**按真实
        #   用量上报 → 直接扣钱（2026-10-18 17:01 那笔 2.575 元就是这么来的）。
        #   前置补结算一律按零用量上报。
        r = await ble_settle(valve, s, random_number=st["tacTimes"],
                             comm_mode=st["commMode"], tag=f"{tag}补结算",
                             offline=(CFG.get("last_open_mode") == "offline"),
                             zero_usage=True)
        return r["ok"]
    print(f"  {C.warn('[!]')} 设备当前状态 onCharge={oc}"
          f"（{ONCHARGE_TEXT.get(oc, '未知')}），请先关阀再开")
    return False


async def ble_close(valve, s, random_number="", comm_mode="", offline=False,
                    zero_usage=True):
    """关阀 + 完整结算

    offline=True    → 不上报云端（订单会挂着，押金不退）
    zero_usage=True → 上报零用量流水（订单正常了结、押金退回、云端计费 0）
                      自用测试版默认 True，别改。
    """
    res = await ble_settle(valve, s, random_number=random_number,
                           comm_mode=comm_mode, tag="2] 关阀", offline=offline,
                           zero_usage=zero_usage)
    return res["ok"]


# ================= 交互界面 =================
def banner():
    print()
    print(C.bold("=" * 58))
    print(C.bold("   电子水阀 KLCXKJ-Water · 双通道控制器  v4.6"))
    print(f"   费率 {fmt_rate()} | 预扣 5 元 | 结算按实际用量")
    if DEBUG:
        print(C.warn("   [调试模式] 已开启：异常将打印完整堆栈（WV_DEBUG=1）"))
    print(C.bold("=" * 58))


def show_status(s, valve):
    print(f"   账号     : {C.info(CFG['phone'])}")
    if s:
        print(f"   用户     : {C.info(s['userName'])}  项目 {s['projectId']}")
        if s.get("accountRealMoney") is not None:
            print(f"   余额     : {C.info(s['accountRealMoney'])} 元")
    print(f"   控制方式 : {C.bold(MODE_NAMES[CFG['mode']])}")
    print(f"   费率     : {fmt_rate()}")
    if CFG["mac"]:
        print(f"   目标设备 : {C.info(CFG['name'])} ({C.info(CFG['mac'])})")
    else:
        print(f"   目标设备 : {C.warn('未设置 —— 请用菜单 [4] 搜索并选择你的水表')}")
    conn = "已连接" if (valve and valve.client and valve.client.is_connected) else "未连接"
    print(f"   连接状态 : {C.ok(conn) if '已' in conn else C.warn(conn)}")
    print("-" * 58)


def input_credentials(force=False):
    """输入账号并选择登录方式（1=密码  2=验证码）。返回 True 表示凭据已就绪"""
    global _sms_code
    if not force and CFG["phone"] and CFG["pwd"]:
        print(f"  使用已保存账号：{CFG['phone']}")
        r = input("  回车使用该账号，或输入 n 重新输入：").strip().lower()
        if r not in ("n", "no", "否"):
            return True
    print("  " + "-" * 56)
    print("  请输入账号（趣智校园登录手机号）")
    phone = input("  账号: ").strip()
    if not phone:
        print(f"  {C.err('[!]')} 账号不能为空")
        return False
    print("  登录方式: 1=密码登录  2=验证码登录")
    method = input("  选择 [1/2]（回车默认 1）: ").strip() or "1"
    if method == "2":
        CFG["phone"], CFG["pwd"] = phone, ""
        _sms_code = ""
        print(f"  {C.info('[*]')} 正在发送验证码到 {phone} ...")
        if not send_sms_code(phone):
            return False
        print(f"  {C.ok('[OK]')} 验证码已发送，注意查收短信")
        _sms_code = input("  请输入 6 位短信验证码: ").strip()
        if not _sms_code:
            print(f"  {C.err('[!]')} 验证码不能为空")
            return False
    else:
        try:
            import getpass
            pwd = getpass.getpass("  密码: ").strip()
        except Exception:
            pwd = input("  密码: ").strip()
        if not pwd:
            print(f"  {C.err('[!]')} 密码不能为空")
            return False
        CFG["phone"], CFG["pwd"] = phone, pwd
        _sms_code = ""
    r = input("  保存账号到本地 config.json（下次免输入，明文存储）？[y/N]: ").strip().lower()
    if r in ("y", "yes", "是"):
        save_config()
        print(f"  {C.ok('[OK]')} 已保存")
    else:
        print(f"  {C.info('[*]')} 本次不保存（仅当前会话有效）")
    return True


def _new_account():
    """新增账号（验证码登录；任意手机号）"""
    print("  " + "-" * 56)
    phone = input("  手机号（11 位）: ").strip()
    if len(phone) != 11 or not phone.isdigit():
        print(f"  {C.err('[!]')} 手机号格式不对（需 11 位数字）")
        return None
    print("  登录方式: 1=密码（服务端已禁用，基本会失败）  2=验证码（推荐）")
    method = input("  选择 [1/2]（回车默认 2）: ").strip() or "2"
    if method == "2":
        print(f"  {C.info('[*]')} 正在发送验证码到 {phone} …")
        if not send_sms_code(phone):
            return None
        print(f"  {C.ok('[OK]')} 验证码已发送")
        code = input("  请输入 6 位短信验证码: ").strip()
        if not code:
            print(f"  {C.err('[!]')} 验证码不能为空")
            return None
        s = login_by_code(phone, code)
    else:
        try:
            import getpass
            pwd = getpass.getpass("  密码: ").strip()
        except Exception:
            pwd = input("  密码: ").strip()
        s = login(phone, pwd) if pwd else None
    if not s:
        print(f"  {C.err('[!]')} 登录失败，账号未添加")
        return None
    set_active_session(s, phone=phone)
    print(f"  {C.ok('[OK]')} 已添加并切换到 {phone}（项目 {s.get('projectId')}）")
    return s


def _relogin(phone):
    """对已有账号重新做验证码登录（会话过期时用）"""
    if not phone:
        return None
    print(f"  {C.info('[*]')} 重新登录账号 {phone}")
    if not send_sms_code(phone):
        return None
    print(f"  {C.ok('[OK]')} 验证码已发送，注意查收短信")
    code = input("  请输入 6 位短信验证码: ").strip()
    if not code:
        print(f"  {C.err('[!]')} 验证码不能为空")
        return None
    s = login_by_code(phone, code)
    if s:
        set_active_session(s, phone=phone)
        print(f"  {C.ok('[OK]')} 重新登录成功（项目 {s.get('projectId')}）")
    return s


def account_manager(cur_s):
    """[5] 账号管理：列出 / 切换 / 新增 / 删除（支持任意手机号）"""
    print("  " + "-" * 56)
    print(f"  {C.bold('[5] 账号管理')}  任意手机号，验证码登录")
    items = list_accounts()
    if items:
        print(f"  {'序号':<5}{'手机号':<14}{'项目':<8}{'余额(元)':<11}{'会话':<7}")
        print("  " + "-" * 50)
        for i, (phone, sess, is_cur) in enumerate(items, 1):
            proj = sess.get("projectId", "-")
            money = sess.get("accountRealMoney")
            bal = f"{money / 1000:.3f}" if isinstance(money, (int, float)) else "-"
            alive = session_alive(sess)
            state = "有效" if alive else ("失效" if alive is False else "未知")
            mark = "  ←当前" if is_cur else ""
            print(f"  [{i}]  {phone:<14}{str(proj):<8}{bal:<11}{state:<7}{mark}")
    else:
        print("  （暂无已保存账号）")

    print()
    print("  输入 序号=切换 / n=新增 / d序号=删除 / 回车=取消")
    r = input("  选择: ").strip().lower()
    if not r:
        return cur_s
    if r == "n":
        s = _new_account()
        return s or cur_s
    if r.startswith("d") and r[1:].isdigit():
        idx = int(r[1:]) - 1
        if 0 <= idx < len(items):
            phone = items[idx][0]
            if input(f"  确认删除账号 {phone}？[y/N]: ").strip().lower() in ("y", "yes", "是"):
                drop_account(phone)
                print(f"  {C.ok('[OK]')} 已删除 {phone}")
        else:
            print(f"  {C.warn('[!]')} 序号超出范围")
        return cur_s
    if r.isdigit():
        idx = int(r) - 1
        if 0 <= idx < len(items):
            phone = items[idx][0]
            set_active_account(phone)
            print(f"  {C.ok('[OK]')} 已切换到 {phone}")
            return login_session()
        print(f"  {C.warn('[!]')} 序号超出范围")
        return cur_s
    print(f"  {C.warn('[!]')} 无效选择")
    return cur_s


async def toggle_mode(valve):
    """切换控制方式：互联网 <-> 蓝牙"""
    old = CFG["mode"]
    CFG["mode"] = "ble" if old == "tcp" else "tcp"
    save_config()
    print("  " + "-" * 56)
    print(f"  {C.bold('[6] 切换控制方式')}")
    print(f"  {C.info('[*]')} {MODE_NAMES[old]}  ->  {C.bold(MODE_NAMES[CFG['mode']])}")
    if old == "ble" and valve.client and valve.client.is_connected:
        print(f"  {C.info('[*]')} 断开蓝牙连接 ...")
        try:
            await valve.disconnect()
        except Exception:
            pass
    print(f"  {C.ok('[OK]')} 已切换（已保存，下次启动沿用）")


async def choose_device(valve, s):
    """搜索蓝牙设备并选择目标"""
    print("  " + "-" * 56)
    print(f"  {C.bold('[4] 搜索蓝牙设备')}")
    try:
        devs = await WaterValveBLE.scan(timeout=SCAN_TIMEOUT)
    except Exception as e:
        print(f"  {C.err('[!]')} 扫描失败：{e}")
        return
    if not devs:
        print(f"  {C.warn('[!]')} 未扫描到任何设备（确认蓝牙已开启、设备在范围内）")
        return

    def is_meter(item):
        nm, addr = (item[0] or "").upper(), (item[1] or "").upper()
        return (any(k in nm for k in DEVICE_KEYWORDS)
                or any(addr.startswith(p) for p in _oui_prefixes()))

    devs.sort(key=lambda x: (0 if is_meter(x) else 1, -(x[2] or -999)))
    print()
    print(f"  {'序号':<4} {'设备名':<24} {'MAC 地址':<22} {'信号':>6}")
    print("  " + "-" * 58)
    for i, (nm, addr, rssi) in enumerate(devs, 1):
        mark = " *" if is_meter((nm, addr, rssi)) else ""
        print(f"  {i:<4} {nm[:22]:<24} {addr:<22} {rssi:>4}{mark}")
    print("  " + "-" * 58)
    print(f"  * = 疑似水控设备（名称关键字 / 厂商前缀 {_oui_text()} 任一命中）")
    r = input("  输入序号选择设备（回车取消）：").strip()
    if not r:
        print(f"  {C.info('[*]')} 已取消")
        return
    try:
        idx = int(r) - 1
        if idx < 0 or idx >= len(devs):
            raise ValueError
    except ValueError:
        print(f"  {C.err('[!]')} 无效序号")
        return
    nm, addr, _ = devs[idx]
    CFG["mac"] = addr
    # 名称兜底：广播名没拿到时用厂商默认名，这样即使下次水表复位换了 MAC，
    # find_device() 还能退回按名称找回来，不会因为存了个 MAC 当名字而失联。
    if nm and nm != "(未命名)":
        CFG["name"] = nm
    elif any((addr or "").upper().startswith(p) for p in _oui_prefixes()):
        CFG["name"] = DEFAULT_NAME
    else:
        CFG["name"] = addr
    save_config()
    print(f"  {C.ok('[OK]')} 已选择: {CFG['name']} ({CFG['mac']})，已保存")
    if valve.client and valve.client.is_connected:
        try:
            await valve.disconnect()
        except Exception:
            pass
    valve.mac, valve.name = CFG["mac"], CFG["name"]
    if CFG["mode"] == "ble":
        print(f"  {C.info('[*]')} 正在连接新设备 ...")
        await valve.connect()
    else:
        print(f"  {C.info('[*]')} 互联网方式无需蓝牙连接，设备信息已保存")


def account_info(s):
    """[7] 账户信息：姓名/学号/余额（/account/info + /account/wallet）"""
    print(f"\n  {C.bold('=== 账户信息 ===')}")
    d = api("GET", "/account/info", {
        "loginCode": s["loginCode"], "userId": s["userId"],
        "accountId": s["accountId"], "projectId": s["projectId"],
        "telephone": s["telephone"]})
    w = api("GET", "/account/wallet", {
        "loginCode": s["loginCode"], "userId": s["userId"],
        "accountId": s["accountId"], "projectId": s["projectId"],
        "telephone": s["telephone"],
        "phoneSystem": "ios", "version": "6.5.26", "telPhone": s["telephone"]})
    if d and d.get("errorCode") == 0:
        a = d.get("data") or {}
        print(f"  姓名   : {a.get('name')}")
        print(f"  学号   : {a.get('idCardNumber')}")
        print(f"  手机号 : {a.get('telephone')}")
        print(f"  项目   : {a.get('projectId')}  账户: {a.get('accountId')}")
    else:
        print(f"  {C.warn('[!]')} 账户信息查询失败: {(d or {}).get('errorMessage')}")
    if w and w.get("errorCode") == 0:
        wd = w.get("data") or {}
        print(f"  充值余额 : {wd.get('accountRealMoneyStr')} 元")
        print(f"  赠送余额 : {wd.get('accountGivenMoneyStr')} 元")
        print(f"  可用金额 : {wd.get('money')} 元")
    else:
        print(f"  {C.warn('[!]')} 钱包查询失败: {(w or {}).get('errorMessage')}")


def bill_query(s):
    """[8] 本月账单：消费/充值/退款明细（/order/query/account/bill/list）

    注意 billRequestType：1=只返回充值，2=只返回消费，0=全部。
    旧代码写死 "1"，所以哪怕真扣了钱也永远显示「本月无账单记录」。
    """
    print(f"\n  {C.bold('=== 本月账单 ===')}")
    month = time.strftime("%Y-%m", time.localtime())
    rows = []
    for page in range(1, 6):
        d = api("GET", "/order/query/account/bill/list", {
            "telephone": s["telephone"], "curNum": page, "billRequestType": "0",
            "month": month, "loginCode": s["loginCode"],
            "projectId": s["projectId"], "userId": s["userId"],
            "accountId": s["accountId"]})
        if not d or d.get("errorCode") != 0:
            print(f"  {C.err('[!]')} 账单查询失败: {(d or {}).get('errorMessage')}")
            return
        data = d.get("data") or []
        if not data:
            break
        rows.extend(data)
        if len(rows) >= 60:
            break
    seen = set()
    uniq = []
    for b in rows:
        c = b.get("consumeBillDTO") or {}
        r = b.get("rechargeBillDTO") or {}
        key = (b.get("billType"), b.get("sortTimestamp"),
               c.get("orderId") or r.get("dealDate") or b.get("billId"))
        if key in seen:
            continue
        seen.add(key)
        uniq.append(b)
    if not uniq:
        print("  本月无账单记录")
        return
    print(f"  {'时间':<16}{'类型':<8}{'金额':>8}   明细")
    print("  " + "-" * 52)
    total_consume = 0.0
    total_recharge = 0.0
    for b in uniq:
        bt = b.get("billType")
        ts = b.get("sortTimestamp", 0)
        tm = time.strftime("%m-%d %H:%M", time.localtime(ts / 1000)) if ts else ""
        if bt == 1:
            r = b.get("rechargeBillDTO") or {}
            amt = float(r.get("dealMoney") or 0)
            tname = r.get("typeName") or ""
            # "支付宝代扣充值" 实为扣费记录（服务端如此命名）
            if "代扣" in tname or "扣" in tname:
                total_consume += amt
                print(f"  {tm:<16}{'扣费':<8}{amt:>8.3f}   {tname}")
            else:
                total_recharge += amt
                print(f"  {tm:<16}{'充值':<8}{amt:>8.3f}   {tname}")
        elif bt == 2:
            c = b.get("consumeBillDTO") or {}
            pre = float(c.get("preDeductMoney") or 0)
            con = float(c.get("consumeMoney") or 0)
            total_consume += con
            desc = c.get("description") or c.get("typeName") or ""
            desc = desc.split(":", 1)[-1] if ":" in desc else desc
            print(f"  {tm:<16}{'消费':<8}{con:>8.3f}   预扣{pre:.2f}  "
                  f"订单{c.get('orderId')} {desc[:28]}")
        elif bt == 3:
            f = b.get("refundBillDTO") or {}
            amt = float(f.get("dealMoney") or 0)
            print(f"  {tm:<16}{'退款':<8}{amt:>8.3f}   {f.get('typeName')}")
        else:
            print(f"  {tm:<16}{'其他':<8}   {str(b)[:44]}")
    print("  " + "-" * 52)
    print(f"  本月扣费合计: {total_consume:.3f} 元   (充值/退款: {total_recharge:.3f} 元)")


def print_menu():
    print()
    print(C.bold("  ┌────────────────────────────────────────┐"))
    print(C.bold("  │           请选择操作                    │"))
    print(C.bold("  ├────────────────────────────────────────┤"))
    print(C.bold("  │   [1] 开阀（放热水）                    │"))
    print(C.bold("  │   [2] 关阀（停水 + 自动结算）           │"))
    print(C.bold("  │   [3] 查询状态                          │"))
    print(C.bold("  │   [4] 搜索蓝牙设备（换设备用）          │"))
    print(C.bold("  │   [5] 账号管理（多账号/任意号）         │"))
    print(C.bold("  │   [6] 切换控制方式                      │"))
    print(C.bold("  │   [7] 我的账户（余额/信息）             │"))
    print(C.bold("  │   [8] 本月账单（消费/扣费明细）         │"))
    print(C.bold("  │   [9] 手动指令台（发任意 hex 帧）       │"))
    print(C.bold("  │   [A] 探测帧格式（老固件适配）          │"))
    print(C.bold("  │   [B] 离线开阀（不扣费）                │"))
    print(C.bold("  │   [C] 关阀·零扣费结算（押金退回）       │"))
    print(C.bold("  │   [D] 补清账（上次结算失败后用）        │"))
    print(C.bold("  │   [E] 上传诊断（定位 226 校验失败）     │"))
    print(C.bold("  │   [0] 退出                              │"))
    print(C.bold("  └────────────────────────────────────────┘"))


async def manual_console(valve):
    """手动指令台：直接下发任意十六进制负载，观察设备原始应答。

    负载段与 build_frame 的 DATA 部分一致（不含帧头/长度/校验），例如：
      23 查询状态 = 00      22 结束交易 = 00
      85 采集     = 00      86 清账     = <clearCode 或采集数据>
    用于验证未知固件、复现问题帧、或手工补一次结算。
    """
    print("  " + "-" * 56)
    print(f"  {C.bold('[9] 手动指令台')}  直接下发 hex 负载并读回原始应答")
    if not (valve.client and valve.client.is_connected):
        print(f"  {C.warn('[!]')} 设备未连接：请用 [6] 切到蓝牙方式，或先执行 [4] 选设备")
        return
    cmd = input("  指令码（两位 hex，如 23）：").strip()
    if not cmd:
        print(f"  {C.info('[*]')} 已取消")
        return
    data = input("  负载（hex，留空则 00）：").strip() or "00"
    try:
        frame = build_frame(cmd, data)
    except Exception as e:
        print(f"  {C.err('[!]')} 构造帧失败：{e}")
        return
    print(f"  {C.info('[*]')} 帧 = {frame}（{len(frame)} 字符）")
    try:
        resp = await valve.send_and_wait_raw(frame, timeout=10)
    except TimeoutError as e:
        print(f"  {C.warn('[!]')} {e}")
        return
    print(f"  {C.ok('[OK]')} 应答 raw={resp['raw']}")
    print(f"       control={resp['control']} funCode=0x{resp['funCode']} "
          f"extend={resp['extend']}")
    print(f"       dataArea={resp['data_area']}")
    rc, txt = rc_of(resp["data_area"])
    print(f"       返回码 {rc}（{txt}）  校验={'通过' if resp['valid'] else '异常'}")
    old = parse_old_resp(resp["raw"])
    if old:
        print(f"       老帧解析：{old}")


async def main():
    load_config()
    banner()
    if not CFG.get("active_phone"):
        # 首次运行（或配置被清空）：引导新增账号，任意手机号 + 验证码登录
        print(f"  {C.info('[*]')} 未找到已保存账号，请先添加一个")
        if not _new_account():
            input("  按回车退出 ...")
            return
    else:
        sess = CFG.get("session") or {}
        print(f"  {C.info('[*]')} 当前账号：{CFG['active_phone']}"
              f"（项目 {sess.get('projectId', '-')}）"
              f"，换账号或新增请用菜单 [5]")
    s = login_session()
    if not s:
        # 会话过期不必退出程序：当场用验证码重登一次
        print(f"  {C.info('[*]')} 尝试重新登录当前账号 …")
        s = _relogin(CFG.get("active_phone") or "")
        if not s:
            print(f"  {C.err('[!]')} 登录失败，程序结束")
            input("  按回车退出 ...")
            return
    print(f"  {C.ok('[OK]')} 登录成功: {s['userName']}  项目 {s['projectId']}")

    valve = WaterValveBLE()
    if not CFG["mac"]:
        print(f"  {C.warn('[!]')} 尚未选择目标设备（本程序不内置默认设备）。")
        print(f"  {C.info('[*]')} 请先执行菜单 [4] 搜索并选择你的水表。")
    if CFG["mode"] == "ble":
        if CFG["mac"]:
            print(f"  {C.info('[*]')} 当前为蓝牙方式，尝试连接目标设备 "
                  f"{CFG['name']} ({CFG['mac']}) ...")
            await valve.connect()
    else:
        print(f"  {C.info('[*]')} 当前为互联网方式（无需蓝牙）")

    session_order = {}   # 互联网方式下保存本次会话开阀订单号
    try:
        while True:
            show_status(s, valve)
            print_menu()
            try:
                choice = input("  请输入指令：").strip()
            except EOFError:
                print(f"\n  {C.warn('[!]')} 输入流已关闭，退出。")
                break
            if choice == "0":
                break
            if choice == "5":
                s2 = account_manager(s)
                if s2:
                    s = s2
                continue
            if choice == "6":
                await toggle_mode(valve)
                continue
            if choice == "7":
                account_info(s)
                continue
            if choice == "8":
                bill_query(s)
                continue
            if choice == "4":
                if CFG["mode"] == "tcp":
                    print(f"  {C.info('[*]')} 互联网方式同样需要设备 MAC（云端 snCode）。")
                    print(f"  {C.warn('[!]')} 扫描需靠近设备，若设备不在身边，"
                          f"可先切换到蓝牙方式在选择后再切回。")
                await choose_device(valve, s)
                continue
            if choice == "9":
                await manual_console(valve)
                continue
            if choice.upper() == "A":
                if CFG["mode"] != "ble":
                    print(f"  {C.warn('[!]')} 帧格式探测只在蓝牙方式下可用，先按 [6] 切换")
                elif not (valve.client and valve.client.is_connected):
                    print(f"  {C.warn('[!]')} 设备未连接：先按 [4] 选设备，或按 [1] 触发连接")
                else:
                    await probe_frame_style(valve)
                continue
            if choice.upper() == "B":
                if CFG["mode"] != "ble":
                    print(f"  {C.warn('[!]')} 离线开阀走蓝牙通道，请先按 [6] 切到蓝牙方式")
                elif not (valve.client and valve.client.is_connected):
                    print(f"  {C.warn('[!]')} 设备未连接：先按 [4] 选设备，或按 [1] 触发连接")
                else:
                    print(f"  {C.info('[*]')} 离线开阀不产生云端订单（不扣费）；"
                          f"关阀时仍会自动结算清账")
                    await ble_open_offline(valve)
                continue
            if choice.upper() == "C":
                if CFG["mode"] != "ble":
                    print(f"  {C.warn('[!]')} 零扣费结算走蓝牙通道，请先按 [6] 切到蓝牙方式")
                elif not (valve.client and valve.client.is_connected):
                    print(f"  {C.warn('[!]')} 设备未连接：先按 [4] 选设备，或按 [1] 触发连接")
                else:
                    print(f"  {C.info('[*]')} 零扣费结算：正常上报以了结订单（押金退回），"
                          f"但上报的用量为 0 → 云端计费 0")
                    await ble_close(valve, s, zero_usage=True)
                continue
            if choice.upper() == "D":
                if CFG["mode"] != "ble":
                    print(f"  {C.warn('[!]')} 补清账走蓝牙通道，请先按 [6] 切到蓝牙方式")
                elif not (valve.client and valve.client.is_connected):
                    print(f"  {C.warn('[!]')} 设备未连接：先按 [4] 选设备，或按 [1] 触发连接")
                else:
                    print(f"  {C.info('[*]')} 补清账：设备已空闲但流水未清时使用；"
                          f"仍以零用量上报（计费 0）")
                    await ble_reclear(valve, s, zero_usage=True)
                continue
            if choice.upper() == "E":
                if CFG["mode"] != "ble":
                    print(f"  {C.warn('[!]')} 上传诊断要读设备流水，请先按 [6] 切到蓝牙方式")
                elif not (valve.client and valve.client.is_connected):
                    print(f"  {C.warn('[!]')} 设备未连接：先按 [4] 选设备，或按 [1] 触发连接")
                else:
                    await upload_diag(valve, s)
                continue
            if choice not in ("1", "2", "3"):
                print(f"  {C.err('[!]')} 无效输入，请重新选择")
                continue

            try:
                # ★ 防呆：命令行菜单的裸开/关阀没有看门狗、没有关机拦截，
                #   默认停用。请用 1-启动(控制台).bat / 开水.bat / 关水.bat
                #   （它们走带安全层的 Web 控制台）。确需绕过设 WV_ALLOW_LEGACY_CLI=1。
                if choice in ("1", "2") and os.environ.get("WV_ALLOW_LEGACY_CLI") != "1":
                    print(f"  {C.warn('[!]')} 命令行菜单的开阀/关阀已停用（无看门狗保护）")
                    print(f"  {C.info('[*]')} 请用：1-启动(控制台).bat，或 开水.bat / 关水.bat")
                    print(f"  {C.info('[*]')} 确需绕过：set WV_ALLOW_LEGACY_CLI=1（不推荐）")
                    continue
                if choice == "1":
                    if CFG["mode"] == "tcp":
                        order_no = tcp_open(s)
                        if order_no:
                            session_order["orderNo"] = order_no
                            print(f"\n  {C.ok('[成功]')} 开阀成功！订单号 {order_no}")
                            print("    结束用水请按 2 关阀（按实际用量结算）。")
                        else:
                            print(f"\n  {C.err('[失败]')} 开阀未确认成功。"
                                  f"若设备已出水可继续按 2 关阀。")
                    else:
                        if not (valve.client and valve.client.is_connected):
                            print(f"  {C.info('[*]')} 连接已断开，正在重连 ...")
                            if not await valve.connect():
                                print(f"  {C.err('[!]')} 重连失败，请检查设备")
                                continue
                        await ble_open(valve, s)
                elif choice == "2":
                    if CFG["mode"] == "tcp":
                        order_no = session_order.get("orderNo") or ""
                        if not order_no:
                            using = tcp_using(s)
                            if using and using.get("orderNo"):
                                order_no = using["orderNo"]
                                session_order["orderNo"] = order_no
                        if not order_no:
                            print(f"  {C.warn('[!]')} 未找到进行中的订单。"
                                  f"如果刚刚开过阀请先开阀再关阀。")
                            continue
                        if tcp_close(s, order_no):
                            session_order.pop("orderNo", None)
                            print(f"\n  {C.ok('[成功]')} 关阀成功！热水已停止，"
                                  f"费用按实际用量结算。")
                        else:
                            print(f"\n  {C.err('[失败]')} 关阀未确认成功，请重试。")
                    else:
                        if not (valve.client and valve.client.is_connected):
                            print(f"  {C.info('[*]')} 连接已断开，正在重连 ...")
                            if not await valve.connect():
                                print(f"  {C.err('[!]')} 重连失败，请检查设备")
                                continue
                        _off = (CFG.get("last_open_mode") == "offline")
                        if _off:
                            print(f"  {C.info('[*]')} 上次是离线开阀 → 本次关阀"
                                  f"**不上报云端**，只做本地清账")
                        _ok = await ble_close(valve, s, offline=_off, zero_usage=True)
                        if _ok:
                            CFG["last_open_mode"] = ""
                            save_config()
                else:
                    if CFG["mode"] == "tcp":
                        tcp_query(s)
                    else:
                        if not (valve.client and valve.client.is_connected):
                            print(f"  {C.info('[*]')} 连接已断开，正在重连 ...")
                            if not await valve.connect():
                                print(f"  {C.err('[!]')} 重连失败，请检查设备")
                                continue
                        await valve.query_status()
            except (TimeoutError, RuntimeError) as e:
                show_exc(e, "命令异常")
            except Exception as e:
                show_exc(e, "命令错误")
    except KeyboardInterrupt:
        print(f"\n  {C.warn('[!]')} 收到中断，退出。")
    finally:
        await valve.disconnect()
        print(f"  {C.info('[*]')} 已断开，再见。")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as e:
        show_exc(e, "程序异常")
        try:
            input("  按回车退出 ...")
        except Exception:
            pass
