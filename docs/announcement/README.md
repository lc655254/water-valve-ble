# App 公告弹窗（GitHub 托管，无需服务器）

App 启动约 2.5 秒后读取：

```text
https://raw.githubusercontent.com/lc655254/water-valve-ble/main/docs/announcement.json
```

读取成功且 `enabled: true` 时弹出公告；用户关闭后，同一个 `id` 默认不再自动弹出，但账号卡片的「查看公告」仍可重新打开。

## 发一条公告

1. 在 GitHub 打开 `docs/announcement.json`，点编辑。
2. 把 `enabled` 改成 `true`。
3. 改一个**新的** `id`（例如 `2026-11-02-01`），否则会被当成同一条公告。
4. 填写 `title`、`body`。
5. 提交到 `main` 分支。
6. 下次打开 App（或等 CDN 刷新几十秒）即可看到。

## 加图片

1. 把图片上传到 `docs/announcement/` 目录，文件名用英文/数字，例如 `notice-1.jpg`。
2. 在 JSON 的 `images` 数组里写 raw 地址：

```json
"images": [
  "https://raw.githubusercontent.com/lc655254/water-valve-ble/main/docs/announcement/notice-1.jpg"
]
```

最多 4 张；App 会按顺序显示，也支持 `gh-proxy.com` / `ghproxy.net` 镜像回退。

## 字段说明

| 字段 | 说明 |
|---|---|
| `id` | 公告唯一 ID，换公告必须换 ID |
| `enabled` | `false` 关闭；`true` 显示 |
| `title` | 标题 |
| `body` | 正文，`\n` 换行 |
| `images` | HTTPS 图片地址数组，最多 4 张 |
| `link` | 可选，点击“查看详情”打开的网址 |
| `linkText` | 链接文字，默认“查看详情” |
| `button` | 关闭按钮文字，默认“我知道了” |
| `once` | `true` 同一 ID 只自动弹一次；`false` 每次启动都弹 |
| `minVersionCode` | 最低 Android versionCode，默认 0 |
| `maxVersionCode` | 最高 Android versionCode，默认 999999 |
| `platforms` | 可选，`["android","pc"]`；不填则两端都显示 |
| `minPcVersion` | 最低 Windows 版本，例如 `1.0.7` |
| `maxPcVersion` | 最高 Windows 版本，默认不限 |

## 关闭公告

把 `enabled` 改回 `false` 并提交即可。

## 注意事项

- JSON 必须是合法 JSON（双引号、最后一项不能有逗号）。
- 图片地址必须是 `https://`。
- 公告只显示纯文本和图片，不执行 HTML/JS。
- 如果 GitHub raw 被网络拦截，App 会自动尝试 `gh-proxy.com` 和 `ghproxy.net`。
