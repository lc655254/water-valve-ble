# 蓝牙水库 · Android

KLCXKJ-Water 水表的安卓控制端（MVP 0.7.4）。手机直接通过 BLE 连表，不依赖电脑；支持趣智校园宿舍名和带图片缓存的 GitHub 公告弹窗。

## 0.7.4 修复（2026-11-08）

现场日志：`结算中(03)` → 22/85 正常 → 上传回 `226 加密校验失败` → 86 全变体回 `81`。

1. **上传随机数用错**：结算只用上次开阀存下的 `last_rn/last_rn_pre`，设备此刻 23 帧报的
   `tacTimes`（日志里的 `45080000`）从没被试过。现在结算前先查一次 23，候选顺序为
   `当前 tacTimes → last_rn → last_rn_pre → 历史(8 个) → macTime`，成功即回写 `last_rn`。
2. **86 负载错位 1 字节**：官方 App（watercontrol 反编译 `klSendClearOfficial`）的清除负载是
   `respData` 前 22 字节，而 `respData` 第 1 字节就是 `0x80` 状态位；旧版用的是去掉 status 的
   采集数据，整串错开 1 字节 → 设备回 `0x81`（数据不符）。现在含 status 的变体排在前面。
3. **长帧被拆成 4 片写**：官方 App 是整帧一次 `writeCharacteristic`（超过 MTU-3 由协议栈走
   Prepare/Execute 长写）。旧版固定 20 字节分片 + 150ms 间隔，86 帧被切成 20/20/20/1 四包，
   设备按断帧处理。现在整帧一次写，失败才退回 20 字节分片（间隔 20ms）。
4. 日志补上 `85 原始` 十六进制与 `86 变体(长度)`，便于下次对帧。


## 功能

- 手机号 + 短信验证码登录（复用凯路云接口）
- BLE 扫描 / 选择水表 / 连接
- 查询设备状态（23 帧）
- 开水：云端 rateOrder 下单 → 21 下发 downData → 轮询 onCharge
- 关水：22 → 85 采集 → **零用量上传** → 86 清账 → 23 确认
- 放水期间前台服务 + 通知栏关水；30 分钟超时自动关水
- 返回键/划掉任务时尝试关水
- 手机端账本：本次/累计升数、节省金额；结算后自动刷新余额
- 极简改版：暖白底、单一钴蓝强调色、大留白、大字号状态、登录/日志折叠
- GitHub Release 自动更新：检查版本、镜像下载、SHA256、系统安装器
- BLE 断线自动重连；恢复连接后自动零用量补关阀
- 关阀兜底：30 分钟超时、前台服务重启、开机恢复、AlarmManager 闹钟
- 运行日志在页面内可滚动、可复制

## 构建

要求：

- JDK 17+
- Android SDK Platform 35 / Build-Tools 35.0.0
- Gradle 8.10.2

在 `android/` 下创建 `local.properties`：

```properties
sdk.dir=C:/Android/sdk
```

然后：

```bat
gradle assembleDebug
gradle assembleRelease
```

release 未签名，使用自己的 keystore 签名：

```bat
zipalign -f 4 app-release-unsigned.apk app-release-aligned.apk
apksigner sign --ks your.jks --ks-key-alias your-alias --out app-release.apk app-release-aligned.apk
```

## 使用

1. 安装 APK（允许未知来源）；
2. 授予蓝牙/附近设备、通知权限（Android 11 及以下还需要定位权限并打开定位）；
3. 手机号 + 验证码登录；
4. 靠近水表，点「扫描水表」，选择 `KLCXKJ-Water`；
5. 点「开水」，放水期间通知栏有「关水」按钮；
6. 结束点「关水」，或通知栏关水；返回/划掉任务也会尝试关水。

## 限制（当前 MVP）

- 只做单账号、开关水、状态与日志；账单/省钱账本/多账号还没移植。
- BLE 断开后会自动重连（最多 20 次），重连成功先补零用量关阀。
- 进程被系统强杀时无法执行关阀；前台服务只能降低概率。后续会加更硬的保底。
- 云端零用量结算依赖账号会话；会话失效需重新登录。
- APK 版本 0.7.4（versionCode 23），`minSdk 26`（Android 8+），`targetSdk 35`。

## 安全说明

- 关阀始终走 `zero_usage=True`，不会上传真实用量。
- 开阀前校验设备 `deviceId` 与账号项目，避免开错表。
- 放水中退出/划掉任务会尝试零用量关阀。
- 仅用于自己有权控制的水表；协议为逆向所得，与厂商无关联。
