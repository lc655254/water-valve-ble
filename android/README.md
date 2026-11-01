# 蓝牙水库 · Android

KLCXKJ-Water 水表的安卓控制端（MVP 0.4.0）。手机直接通过 BLE 连表，不依赖电脑。

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
- BLE 断开后不会自动重连；请靠近水表重连再关水。
- 进程被系统强杀时无法执行关阀；前台服务只能降低概率。后续会加更硬的保底。
- 云端零用量结算依赖账号会话；会话失效需重新登录。
- APK 版本 0.1.0，`minSdk 26`（Android 8+），`targetSdk 35`。

## 安全说明

- 关阀始终走 `zero_usage=True`，不会上传真实用量。
- 开阀前校验设备 `deviceId` 与账号项目，避免开错表。
- 放水中退出/划掉任务会尝试零用量关阀。
- 仅用于自己有权控制的水表；协议为逆向所得，与厂商无关联。
