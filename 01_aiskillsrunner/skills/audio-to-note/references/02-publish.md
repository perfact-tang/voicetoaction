# 发布：Firestore Note + FCM 手机通知

本文件说明 `publish-note.sh` / `publish-note.mjs` 的行为，以及出问题时怎么排查。
Skill 主体只需要按 SKILL.md 的 Step3 调用一次，不需要自己拼 curl。

---

## 1. 目标位置

```
Firestore: /users/<userid>/notes/<noteid>
```

- `<userid>`：录音所属用户的 Firebase Auth uid（`AISKILLS_USERID`）。
- `<noteid>`：默认等于本次 DocID（Unix 毫秒），与 `~/Documents/tmpaiskill/<DocID>/` 对齐。
- 集合与 `text-to-animation-movie` 写入的 Note **完全相同**，所以 App 的笔记列表会直接显示。

文档字段（与 Android `NoteItem` / `FirebaseRepository.writeNote` 对齐）：

| 字段 | 类型 | 值 |
|---|---|---|
| `id` | string | 与 `noteid` 相同 |
| `title` | string | 笔记标题 |
| `body` | string | 整理后的正文（只放正文） |
| `tags` | array<string> | `["voice", "note"]` |
| `color` | string | `"paper"` |
| `hidden` | boolean | `false`（必须是 false，App 查询条件是 `whereEqualTo("hidden", false)`） |
| `language` | string | `zh` / `ja` / `en` / `ko` |
| `updatedAt` | timestamp | Firestore 服务端时间（`REQUEST_TIME`） |

> 不使用 Firebase Storage：本 skill 只写文本 Note，不上传音频/视频。

---

## 2. 零依赖实现

`publish-note.mjs` 不依赖 `firebase-admin`，只用 Node 内置模块：

1. `crypto` 读服务账号 JSON，签 RS256 JWT；
2. `fetch` 调 `https://oauth2.googleapis.com/token` 换 access token；
3. `fetch` 调 Firestore REST `documents:commit` 写入（带 `updateTransforms` 设置 `updatedAt`）；
4. `fetch` 调 Firestore REST `GET` 读回校验；
5. `fetch` 列出 `users/<uid>/fcmTokens`；
6. `fetch` 调 FCM v1 `messages:send` 逐台发送。

这样 skill zip 里不需要 `node_modules`，安装后即可运行。

需要的服务账号权限：

- Cloud Datastore / Firestore 读写；
- Firebase Cloud Messaging 发送。

服务账号 JSON 里的 `project_id` 必须是 Note 所在的 Firebase 项目（默认 `vibecodingjapan`）。

---

## 3. 命令行用法

```bash
bash scripts/publish-note.sh <笔记.json> [选项]
```

| 参数 | 说明 |
|---|---|
| `--noteid <id>` | 覆盖 Note 文档 id；默认取 JSON 的 `noteid` / `docid`，再退化到当前毫秒 |
| `--userid <uid>` | 覆盖用户 uid |
| `--key <path>` | 覆盖服务账号 JSON 路径 |
| `--language <code>` | 覆盖语言（`zh` / `ja` / `en` / `ko`）；默认取 JSON |
| `--dry-run` | 只校验并打印，不认证、不写 Firebase、不发通知 |
| `--no-notify` | 写 Note，但不发 FCM |
| `--notify-topic <t>` | 没有设备 token 时发到 topic（也可用 `AISKILLS_FCM_TOPIC`） |
| `--notify-token <t>` | 指定设备 token（可重复；给了就只发这些 token） |
| `--result <path>` | 结果 JSON 路径；默认 `<笔记.json 所在目录>/发布结果.json` |
| `--json` | 只把结果 JSON 打到 stdout（日志走 stderr） |

示例：

```bash
# 空跑
AISKILLS_NOTE_DRY_RUN=1 bash scripts/publish-note.sh ~/Documents/tmpaiskill/1759/笔记.json

# 真实写入 + 通知（uid/key 从环境变量取）
AISKILLS_FIREBASE_ACCOUNT=~/Downloads/vibecodingjapan-....json \
AISKILLS_USERID=KYTF9y43qgc39vKn0sI3qWpsjRE2 \
  bash scripts/publish-note.sh ~/Documents/tmpaiskill/1759/笔记.json --noteid 1759
```

---

## 4. 环境变量

| 变量 | 说明 |
|---|---|
| `AISKILLS_FIREBASE_ACCOUNT` | 服务账号 JSON 路径；由 `aiskillsrunner --firebasekey` 设置，优先级最高 |
| `AISKILLS_USERID` | 用户 uid；由 `aiskillsrunner --userid` 设置，优先级最高 |
| `AISKILLS_LANGUAGE` | 上传时选择的语言（`zh` / `ja` / `en` / `ko` / `kr`） |
| `AISKILLS_NOTE_KEY` | 直接运行脚本时的密钥路径（经 dsh 会被过滤） |
| `AISKILLS_NOTE_UID` | 直接运行脚本时的 uid |
| `AISKILLS_NOTE_DRY_RUN` | `1` = 只校验，不写 Firebase |
| `AISKILLS_NOTE_NOTIFY` | `0` = 不发 FCM |
| `AISKILLS_FCM_TOPIC` | 没有设备 token 时的 topic 兜底 |

> 变量名里不要出现 `KEY` / `SECRET` / `TOKEN` / `PASSWORD`（dsh 的 subprocess 层会剔除）。
> 所以经 dsh 传密钥路径时统一用 `AISKILLS_FIREBASE_ACCOUNT`。

---

## 5. FCM 行为

- 读取 `users/<uid>/fcmTokens`（App 登录后自动登记，退出登录时删除）。
- 每台设备发一条：
  - `notification.title` = 笔记标题；
  - `notification.body` = 目标语言的「语音笔记已保存」；
  - `android.priority` = `HIGH`；
  - `android.notification.channelId` = `luyin_job_result`（与 App 的通知渠道一致）；
  - `data` = `{ type: "note", noteId, documentId, userId, language }`。
- 发送成功数 > 0 → `notified=true`，skill 最终标记 `notified=1`。
- 没有 token → `skipped="no-tokens"`，`notified=false`，**不算失败**。
- 某个 token 失效（FCM 返回 `UNREGISTERED` / 404）→ 从 Firestore 删除该 token，继续发其它设备。
- 网络或其它 FCM 错误 → 记录 `notify.error`，`notified=false`；**Note 已经写入，不回滚**。

为什么用 `luyin_job_result`：Android 端 `PushNotifications.kt` 的
`JOB_CHANNEL_ID` 是 `luyin_job_result`，用同一个渠道用户才能在系统设置里统一管理；
用别的 channelId 会导致 Android 13+ 不展示通知。

---

## 6. 结果 JSON

```json
{
  "ok": true,
  "dryRun": false,
  "noteId": "1759700000000",
  "userId": "KYTF9y43qgc39vKn0sI3qWpsjRE2",
  "notePath": "users/KYTF9y43qgc39vKn0sI3qWpsjRE2/notes/1759700000000",
  "language": "ja",
  "title": "先别急着换工具，先把流程跑顺",
  "bodyChars": 1280,
  "tags": ["voice", "note"],
  "firestore": { "written": true, "verified": true },
  "notify": { "sent": 1, "failed": 0, "skipped": null },
  "notified": true,
  "timestamp": "2026-10-29T01:23:45.678Z"
}
```

失败时：

```json
{
  "ok": false,
  "error": "Firestore 写入失败: ...",
  "noteId": "...",
  "userId": "...",
  "notePath": "users/.../notes/..."
}
```

退出码：`0` 成功（含 no-tokens）；`1` 参数/JSON 错误；`2` 认证/Firestore/FCM 失败。

---

## 7. 排障

| 错误 | 检查 |
|---|---|
| `ENOENT` 密钥文件 | `--firebasekey` / `AISKILLS_FIREBASE_ACCOUNT` 路径是否正确、是否加了 `~` |
| `invalid_grant` / `Invalid JWT` | 服务账号 JSON 是否完整；系统时间是否准确；私钥是否被换行破坏 |
| `403 PERMISSION_DENIED` | 服务账号是否属于该项目；是否启用了 Firestore / FCM API |
| `404 NOT_FOUND` | 项目 id 是否正确；数据库是否是 `(default)` |
| `400 INVALID_ARGUMENT`（FCM） | token 是否失效；`message.android.priority` 是否 `HIGH` |
| `no-tokens` | App 是否登录；是否允许通知；`users/<uid>/fcmTokens` 是否有文档 |
| 通知到了但点不开 | `data.documentId` 是否设置；App 的 `MainActivity` 是否处理该 extra |
| 重复通知 | skill 是否输出了 `notified=1`；后端是否读到该标记并跳过通用推送 |
