# 蓝牙水库 · Android

KLCXKJ-Water 水表的安卓控制端（0.12.13）。手机直接通过 BLE 连表，不依赖电脑；支持趣智校园宿舍名和带图片缓存的 GitHub 公告弹窗。

## 0.12.13 关水后状态不再卡「结算中」（2026-11-11）

`renderWaterStatus()` 的状态来自 `lastOnCharge`，而它只在 `queryInternal()` 走
`listener.onQuery()` 时更新。结算轮询（`settleInternal` 里那 5 次 `ble.queryStatus()`）
从来不走这条路 —— 最后那帧 `onCharge=00` 只打了日志，界面收不到，所以关水成功后
状态还挂在「已连接 · 结算中」。

- 新增 `ValveManager.publishQuery(q)`：结算轮询的每一帧 23 都推给界面
- `MainActivity.onWater(false)` 里把 `lastOnCharge` 复位成 `00`（关水成功即空闲）
- 「水表在结算中」的 toast 只在手动查询时弹（`!running`），结算过程不再反复弹

## 0.12.12 按云端实扣记账 + MTU 协商（2026-11-10）

真机日志（淮安 / project 1036 / device 2263）里同一个账号连续两单：
`rate1=325 → 账单 consumeMoney=0.325`、`rate1=3125 → 3.125`。也就是说这台云端**不看零用量**，
按设备流水里的 `rate1` 照扣，而账本还写着「省 ¥0.325 / 省 ¥3.125」。

- `Cloud.upload()` 把响应里的 `consumeMoney` 存到 `Cloud.lastConsumeMoney`；
  `recordSession()` 拿到就按它记 `paid`，`saved` 归 0，并在 `note` 写「云端按量扣费，零用量未生效」
- 下单前先 `Cloud.refreshBalance()`：上次结算断网（日志里 `UnknownHostException`）会让缓存余额过期，
  「下单前 − 结算后」算成 `实扣 ¥-1.550`
- `refreshBalanceAndNotify()` 增加 `lastRecordPaidPositive()` 判断，已经按云端金额记过就不再被余额差覆盖
- `BleClient` 连接后 `requestMtu(247)`：默认 MTU 23 时 130B 的 21 帧写会回 `status=7`，
  只能退回 20 字节分片；MTU 抬上去后整帧可发，日志新增 `[ble] MTU 协商：mtu=…`

## 0.12.11 实验开关不再卡住关阀（2026-11-10）

成都现场日志（project 225 / `rate1=180 rate2=60`）：打开「零用量连费率一起清零」后，
云端对这条流水一律回 `226 加密校验失败`，拿不到 `clearCode`，86 的 12 个变体全回 81，
设备停在 `onCharge=03`，阀关不上，App 每 50 秒重试一次。

- `settleInternal()` 现在：`zero_rate` 上传失败时**立刻回退标准零用量**（保留费率）再传一轮，
  拿到 `clearCode` 后正常 86 清账，不再让实验开关挡住关阀
- 同一台设备连续两次被拒，自动把 `zero_rate` 关掉并提示（`zero_rate_fail` 计数）
- 设置里的说明同步改成「有些云端会判 226，这时会自动退回，不会卡住关阀」
- 公开版 `usesCleartextTraffic` 保持 `false`（靶机调试版另存，不进 release）

## 0.12.8 账单页：看清钱怎么扣、怎么加（2026-11-10）

设置 → 关于 → 「账单」列出本月逐笔记录：

- `billType=2` → `consumeBillDTO`：`consumeMoney`（实扣，元）、`preDeductMoney`（预扣）、`consumeDate`、`orderId`
- `billType=1` → `rechargeBillDTO`：`dealMoney`、`dealDate`、`typeName`（如「支付宝代扣充值」）
- `billType=3` → `refundBillDTO`：结算差额退回

扣款链路（服务端记账，客户端只触发）：

1. `POST /order/ali/downRate/bluetooth/rateOrder` → 返回 `preDeductMoney`、`useCount`、`rate`、`downData`；
   钱包立刻 `4.600 → 0.000`（冻结）
2. `POST /order/upload/bluetooth/data`（上传 85 流水）→ 返回 `consumeMoney`、`preDeductMoneyAfter`、`clData`
3. 结算后钱包回到 `4.600`（`consumeMoney=0` 时全额退回）

加钱只有两条服务端路径：支付渠道回调（充值）、结算差额退回（退款）。`/account/wallet` 是只读接口，
`accountRealMoney` 单位是厘，客户端没有「设置余额」的入口。

## 0.12.7 日志一键分享（2026-11-10）

以前只能「导出成文件」，还得自己去文件夹翻。现在：

- `DebugLog.writeShareFile()` 把整份日志写到 `files/share/logs-时间.txt`（只留最近 5 份）
- `LogProvider`（`content://com.yg.watervalve.logs/…`）把文件暴露给系统分享面板，
  绕开 API 24+ 的 `file://` 限制；`LogShare` 用 `ACTION_SEND` + `ClipData` 把读权限授给微信/QQ
- 入口两处：主界面日志卡片右上角「分享」、设置 → 调试「分享到微信/QQ」
- 崩溃时 `DebugLog` 的 uncaught handler 自动打包一份并写 `pending_share`，
  下次启动弹「上次异常退出，要分享日志吗」，点一下直接发
- 日志卡片的「复制」「收起」从 `Appearance` 的 ghosts 列表里摘掉，不再套胶囊底

## 0.12.6 起步价不当成用水量（2026-11-10）

真机日志（13236239712 / 42B 老固件）里 `rate1=25 rate2=25`，按 `rate1/rate2` 会算成 1.00 L ——
但 25 厘正好是起步价 0.025 元，这说明设备报的是「最低收费」，不是真实用量。

- `MIN_CHARGE_LI = 25`：`rate1 <= 25` 时按流速估算并标「约」，不再把 1.00 L 当实测
- `rate1 > 25` 才走「设备计量」，并且只在时长 ≥ 45 秒时用来校准 `flow_lpm`，短会话不参与学习
- 修 `[snap] 下单后` 的取值时机：`lastRateOrder` / `lastOrderUseCount` 赋值后再拍快照
- 设置页不再在 onCreate 和 onResume 各刷一遍外观

## 0.12.5 用水量取真实值（2026-11-10）

以前只有「按秒数 × 8 L/min 估算」和「计数差值」两种，估算值还冒充实测。现在按可信度四档：

1. **设备计量**：85 流水里 `rate1` 是设备自己算的金额（厘），`rate2` 是单价（元/吨），
   `liters = rate1 / rate2`。朋友那台 `rate1=2500 rate2=25` → 100 L（979 秒 ≈ 6.1 L/min），
   和淋浴的实际流速对得上
2. **云端账单**：`applyCloudUsage()` 在结算后 2.5 秒查 `/order/query/account/bill/list`，
   用 `Usage.litersFrom()` 从字段名（`useWater`/`waterNum`/…）或描述文本（`3.87L`/`3870ml`/`0.00387吨`）里取
3. **计数差值**：≥2 时按 1 计数 = 1 升
4. **流速估算**：以上都没有才用；并且用第 1 档的会话自动校准 `flow_lpm`（0.6 旧 + 0.4 新），越用越准

账本标注来源：`3.870 L（设备）` / `（云端）` / `（计数）` / `（约）`。
`[bill]` 日志会把下单、上传、账单三处返回的字段原样打出来，字段名对不上时能直接看出来。

## 0.12.4 长写能力记住、23 帧重试（2026-11-10）

- `BleClient` 把「这块表不吃整帧长写」记进 prefs（`ww_broken_<MAC>`）：连错两次之后，
  下次连同一块表直接用 20 字节分片，不再每帧白等 2~3 秒（vivo 上 21/86 帧都走分片，照样能开阀清账）
- `queryStatus()` 在 23 帧超时后重试一次（等 1.2 秒再问），避免「连接后查询失败」直接空手 10 秒
- 正在放水时点「实测一次」，确认框会先说明会关掉当前放水再重开

## 0.12.3 日志不再抢页面（2026-11-10）

结算时一秒几十行日志，原来每行都 `setText` + `fullScroll(FOCUS_DOWN)`，日志卡片一展开整页就被往下拽。

- `appendLog()` 只往 `logBuf` 里攒；卡片收起时**完全不碰界面**，展开时按 250ms 合并成一次 `flushLog()`
- `flushLog()` 先记下外层 `scroll` 的 `scrollY`，刷完再放回去，日志重排带不动整页
- 给 `logScroll` 挂了滚动监听维护 `logStickBottom`：只有本来就贴着底部才自动跟到底，往上翻看不会被拽回去
- 展开日志时把 `logStickBottom` 复位为 true 并立刻刷一次，不用等下一批

## 0.12.2 计费自检修「假测」（2026-11-10）

真机日志（vivo / 22.800 元余额）里自检结果写成「云端扣了 ¥-5.000」，原因有两个：

- **阀根本没开**：手上有未确认关水的会话时，`openInternal()` 会走 `recoverIfOpen()` 分支直接返回，
  自检随后 sleep 8 秒再「关阀」，测的是旧会话。现在 `billingTest()` 先 `closeInternal("清理上次会话")`
  把旧账结干净，再重新下单开阀
- **预扣退回被当成扣款**：下单预扣 5 元、结算后原路退回，`before - after = -5`。现在 `paid < -0.001`
  单独判断，结论写「预扣的 ¥5.000 已退回、实扣 ¥0 → 不用开这个开关」
- 顺带：`Updater.check()` 在 `api.github.com` 超时时改用 `releases?per_page=10` 兜底，不再先报一条 SocketTimeout
- 顺带：`BleClient` 整帧长写连错两次就固定走 20 字节分片（vivo 上长写每次失败，分片能开阀也能清账），
  省掉每帧 3 秒空等

## 0.12.1 会话失效与主题补漏（2026-11-10）

真机日志（vivo V2505A / Android 16）暴露了两件事：`errorCode=8 登录失效` 之后应用还在用死会话，
而且重新登录被自己的防重复逻辑挡住了。

- `Cloud.watchSession()` + `checkSession()`：`request()` 解析出 `errorCode=8` 就回调一次（5 秒节流），
  `ValveManager.onSessionExpired()` 清 `session`、清 prefs、`onSession(null)`；
  `MainActivity.onSession(null)` 直接把登录页顶上来，提示「登录已失效，请重新获取验证码登录」
- `doGateLogin()` 不再用 `getSession() != null` 拦截，改成「同一个验证码 20 秒内只提交一次」，
  会话失效后可以正常重登；`lookupCloudFor()` 的 MAC 变体循环里也加了 `session == null` 就 break，
  一次扫描不再打十几次 `device/info/mac`
- 主题补漏：`btnScan` / `btnQuery` 加进 accents；`retint()` 通过 `looksThemed()` 认得
  「静态主色 / 任意预设色 / 自定义色 / 上次用的主色」，换第二次主题也能重染，不会只生效一次
- `BillingCheck` 遇到 `errorCode=8` 直接提示先重新登录

## 0.12.0 换主题/背景图实时生效（2026-11-09）

以前 `Appearance.apply()` 只在 `onCreate` 跑一次，设置里改完必须重启才看得到。现在：

- 两个 Activity 都在 `onResume` 重新 `apply()`：从设置页返回主界面立刻换色、换背景图
- 面（surface）也改成运行期生成：`cardBg` 白底掺 2.5% 主色、`bottomBarBg`、`inputBg`、
  `ghostBg`、`logBg`、`noticeBg`、`chipBg(色)`；卡片边框、输入框描边都跟着主色走
- `retint()` 递归遍历视图树，把布局里写死的 `@color/primary` / `success` / `danger`
  文字色换成当前配色；语义色用 `harmonize()` 只往主色偏 10~12%，保留红绿的可读性
- 背景图解码结果按「路径 + 修改时间」缓存，回到前台不再重新解 1600px 大图
- 清除背景不再要求重启：返回主界面即恢复默认底色，提示语同步改了

## 0.11.9 计费自检：要不要开实验（2026-11-09）

「零用量连费率一起清零」这个开关不是所有设备都需要，乱开会撞上云端 226。所以加了一个自检：

- **只读判断**：`Cloud.bills()` 查 `/order/query/account/bill/list`（`billType == 2` 的
  `consumeBillDTO`，金额单位是元），和本地账本最后一笔（`ts` / `official` / `paid`）对齐时间后给结论：
  - 账本里 `paid > 0`（上次真被扣了）→ 需要开；
  - 最近一笔消费时间晚于上次结算且金额 > 起步价 → 需要开；
  - 只扣了 0.025 这种起步价 → 零用量只清掉一部分，建议开着再测；
  - 金额为 0 → 不需要开；
  - 本月没有消费记录 → 判断不了，走实测
- **实测**：`ValveManager.billingTest(8, cb)` 开阀 8 秒 → `closeInternal()` 正常结算 →
  再查一次余额，`paid = before - after`；`paid > 0.03` 就是需要开，≈0 就是不需要
- 结论写进日志：`[check] 实测结果…（实扣 0.0）`，配合 `[bal]` 的余额前后行可以复核

## 0.11.8 小圆孔不再顶内容（2026-11-09）

0.11.7 把所有挖孔都按「刘海」处理：整页内容按 `safeInsetTop` 下移，小圆孔机型上等于白白空出一条，
看着像没全屏。现在按挖孔形状分开处理：

- `FullScreen` 遍历 `DisplayCutout.getBoundingRects()`：某一块宽度 ≥ 屏宽 40% 才算刘海，
  这种 `contentTop = cutoutTop` 整条让开；小圆孔 `contentTop = 0`，页面顶到屏幕最上沿
- 居中的内容（登录页的图标与表单）仍用 `cutoutTop` 让位，避免被孔压住
- 删掉 `statusBarScrim`：这条不透明色带盖在背景图/渐变上，看起来就像内容被顶下去一截
- `onWindowFocusChanged(true)` 也调用 `hideBars()`，弹窗、键盘、通知栏回来都不会把状态栏留在屏幕上
- 调试日志加 `[ui] insets cutout=… content=… bottom=… ime=…`，换机时直接看这台机器报的挖孔高度

## 0.11.7 全屏与挖孔适配（2026-11-09）

以前用 `getSystemWindowInsetTop()` 把内容整体下移一个状态栏高度，挖孔机上这个值更大，
顶部就空出一条。现在窗口直接画进挖孔区，状态栏/导航栏收起：

- `FullScreen.apply()`：`layoutInDisplayCutoutMode = ALWAYS`（API 30+）/ `SHORT_EDGES`（28+），
  `setDecorFitsSystemWindows(false)`（30+），`hide(statusBars | navigationBars)` +
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（上沿下滑可临时唤出）；API 26–29 走 IMMERSIVE_STICKY 老旗标
- insets 只取 `displayCutout()` 和 `systemGestures()`：没有挖孔的机型 top=0，内容顶到屏幕最上沿；
  挖孔机按挖孔高度让位。`statusBarScrim` 高度也改成挖孔高度，摄像头那一条仍铺页面底色
- 主界面：标题行 + 挖孔、底栏 + 手势条、登录页内容 + 挖孔与键盘高度；设置页：内容容器 + 挖孔，
  底部留手势条，返回箭头不再压在摄像头下面
- 两个 Activity 都在 `onResume` 重新 `hideBars()`，弹过对话框、收过键盘后不会把系统栏留在屏幕上

## 0.11.6 清除背景后的重启提示（2026-11-09）

背景图是主界面 `onCreate` 时贴到 `bgImage` 上的，`clearBgImage()` 只删文件 + 清 prefs，
已经开着的界面还留着旧图。所以点「清除背景」不再默默 `recreate()`：

- 弹窗写明「需要重启软件才会恢复默认背景」，主按钮是「立即重启」
  （`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` 起一个新的 MainActivity，外观重新生成）
- 点「取消」/ 点空白关掉弹窗，则只 `recreate()` 设置页（`confirm()` 新增 `onClose` 回调，
  用 `handled` 标记避免主按钮路径重复执行）
- 动作同时写进调试日志：`[op] 清除自定义背景图`

## 0.11.5 调试日志补全（2026-11-09）

0.11.4 只记了请求和蓝牙帧，这一版把「谁在什么时候点了什么、点完钱有没有变」补齐：

- **操作日志**：`tap()` 统一记一条 `[op] 点击 btnWater`；弹窗按钮没有资源 id 就退回按钮文字
  （`点击 安装更新`）。登录被忽略、手机号不够 11 位、切前后台、界面销毁、onNewIntent 都有记录
- **余额快照**：开水、关水、查询、结算后各查一次云端钱包并落盘
  `[bal] 余额[关水后]：5.300 → 2.800 元`；下单前、结算后、结算未确认分别标不同 why
- **账号/设备快照**：`[snap]` 一行给全 账号、userId、accountId、项目、余额、网络、连接、
  放水中、安全未关、mac、deviceId、上次流水（useCount/预扣/rate1/rate2）、下单参数
- **结算明细**：`[settle]` 记计时、计数差值、采用费率、计算过程（用量 × 费率 → 起步价 → 应扣），
  以及实扣金额与「零用量是否生效」的判断
- **设备帧解析**：`[dev]` 把 23 帧的 returnCode / onCharge / deviceId / productId / accountId /
  mac / commMode / macTime / tacTimes / macType / chargeMethod 全部展开
- **启动头**：机型、fingerprint、屏幕、内存、存储、电池、运营商、本机 IP、时区、开机时长、
  上次连过的水表与清账形态；设置「关于」里也显示机型与系统版本

## 0.11.4 调试日志（2026-11-09）

用来查「同一套流程，朋友的账号一直被扣钱」。设置 → 调试里打开开关，日志落到应用私有目录
`files/debug/`，关掉软件、重启手机都还在，只有卸载/清数据才会没。

- **记什么**：云端每次请求的方法、URL、表单体、HTTP 码、返回体、耗时、网络类型；
  蓝牙每一条通知的原始字节、每一帧收发、GATT 状态码、写完成状态；界面日志全文；
  未捕获异常堆栈；启动时的机型 / 系统 / 版本 / 主题 / 账号（含 loginCode、余额）
- **写盘策略**：单文件 2MB 换新文件，最多留 15 个（约 30MB），每行立即 flush，闪退不丢尾部
- **导出**：设置里「导出日志」走系统保存对话框存成单个 txt；「复制最近」把内存里最近 400 行贴到剪贴板
- 关闭调试后停止写文件，已有日志保留；`DebugLog.line/hex/http/exc` 是统一入口

## 0.11.3 零用量费率实验（2026-11-09）

现场日志：结算后余额从 5.300 掉到 2.800，正好是 `rate1/1000`，而账本还写着「省 ¥3.263」。

1. **重复登录去重**：验证码自动提交后手点「登录」会再发一次，第二次拿着用过的验证码必然回
   `验证码已失效（29）`。现在 `isBusy()` 或已有会话时直接忽略，日志写「忽略重复的登录响应」。
2. **账本按云端实扣记账**：下单前记 `balanceBeforeOrder`，结算后再查余额，差值为真扣金额；
   扣了就改写最后一条记录为「实扣 ¥X · 省 ¥0」，并在日志提示「云端实际扣了 ¥X（零用量结算没生效）」。
3. **结算原始参数进日志**：`useCount / 预扣 / rate1 / rate2` 一行打完，对不上时能直接看。
4. **实验开关**：设置 → 实验 → 零用量连费率一起清零。部分固件把要扣的钱放在 `rate1`，
   只清 `useCount + prepay` 仍会扣款；打开后 `rate1/rate2` 一起清零，云端若回 `226` 就关掉。

## 0.11.1 / 0.11.0 设置页与外观（2026-11-09）

- 右上角齿轮进设置页：外观（7 套配色 / 自定义颜色 / 自定义背景图，直接拉起相册选图）、
  实验开关、版本与「检查更新」入口
- 外观全部运行时生成 `GradientDrawable`，不依赖每套主题的独立资源；背景图按 1600px 采样解码
- 主界面文案改成金额优先：`本次：省 ¥0.025 · 42 秒 · 0.350 L`，最低按 0.025 元计

## 0.10.x 主题与启动体验（2026-11-09）

- 启动 0.3 秒查更新并主动弹窗，支持「跳过这个版本」
- 自适应图标回到旧版白描边水滴，蓝色底铺满整块画布，不再有白边
- 深色模式跟随系统，页面底色 / 卡片 / 按钮渐变都走主题色

## 0.7.8 账本与交互（2026-11-09）

- **连上水表后清空设备列表**：列表只在「选表」阶段有用，连上就不再占屏幕；再点「扫描水表」才会重新出现
- **账本按真实用量记**：本次用量 = 设备计数 − 下单时云端 useCount（跟 PC 版同一套口径），
  不再拿累计计数直接 ×0.5 导致「每次固定 1 升」；计数拿不到时按 `时长 × 12 L/min` 估算并标注「约」
- 账本新增「最近记录」：时间 · 用量 · 时长 · 省下，最多 5 条
- **开水/关水按钮不再禁用**（禁用会让焦点乱跳、整页被滚走），改成点击回调里挡重复；
  收起登录页时清焦点 + 收键盘，避免键盘收起引发页面滚动

## 0.7.7 界面修正（2026-11-09）

- 登录页验证码输入框提示被「获取验证码」按钮挤掉：提示缩成「6 位验证码」，输入框最小 110dp、
  单行省略，按钮最小宽度收到 88dp，360dp 宽的手机上也不会截断
- 登录页整体收高（顶部留白 64→36dp、图标 56→48dp、卡片内边距 18→16dp），小屏不会顶到屏幕外

## 0.7.5 交互与防呆（2026-11-09）

1. **独立登录页**：没登录时整屏盖住主界面，标题就是「登录后才能开水」。手机号 → 获取验证码
   （60 秒倒计时）→ **收到 6 位验证码自动提交**，不用再找「登录」按钮；失败留在页面上直接显示原因。
   「切换账号」也走同一个页面。
2. **连接有反馈**：点设备后弹「正在连接 …」进度框（提示别让官方 App 抢连接），连上自动查询状态并
   提示「已连接 · 空闲」；失败弹「连不上」对话框，按 GATT 状态给出可操作原因（133=被别的设备占着、
   超时=信号弱/被抢、找不到 FF02/FF01=连错表），带「重试 / 排查」两个按钮。
3. **状态更醒目**：开水按钮上方的状态改成 14sp 加粗、按状态变色 ——
   `未连接水表` / `已连接 · 空闲，可以开水` / `已连接 · 结算中（点开水会自动补结算）` /
   `放水中` / `放水中 · 连接已断开，正在自动恢复`。
4. **断线未结算防呆**：连接断开且上次开阀还没收尾时，底部常驻红色横幅
   「⚠ 连接断开，水可能还在流 —— 正在自动重连并补结算」，配合原有的自动重连 + 补结算。
5. 没登录点「开水」→ 直接弹登录页；没连表点「开水」→ 提示并闪一下「扫描水表」按钮。

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
- APK 版本 0.12.13（versionCode 73），`minSdk 26`（Android 8+），`targetSdk 35`。

## 安全说明

- 关阀始终走 `zero_usage=True`，不会上传真实用量。
- 开阀前校验设备 `deviceId` 与账号项目，避免开错表。
- 放水中退出/划掉任务会尝试零用量关阀。
- 仅用于自己有权控制的水表；协议为逆向所得，与厂商无关联。
