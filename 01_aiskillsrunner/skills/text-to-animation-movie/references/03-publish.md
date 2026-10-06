# 说明文与上传

## 1. 动画说明文（`动画说明.json`）

```json
{
  "title": "星巴克凭什么卖得更贵？用一个案例读懂商业模式",
  "body": "……（1000 字以内，含 #tag）",
  "color": "paper",
  "hidden": false,
  "tags": ["movie"]
}
```

### title

- 一行的、说清内容的标题。包含最有信息量的关键词（便于检索）。
- 不要营销腔（「震惊」「必看」「全网最全」），不要 emoji。

### body

这是**给 SNS 投放用的介绍文**，不是视频的字幕稿。要求：

- **1000 字以内**（中文字符按 1 字计；`upload-movie.js` 超过会告警但照写，请自己守住）。
- **至少 3 个 `#tag`**，半角 `#`，直接写在句子里或末尾，例如：
  `#商业模式 #星巴克 #第三空间 #案例拆解`
- 结构建议（4 段左右，250–600 字最舒服）：
  1. 一句话抛出问题或结论（钩子）
  2. 视频讲了什么、按什么顺序讲（3–5 句）
  3. 谁适合看、看完能得到什么
  4. `#tag` 组
- **不要**在 body 里写 public url，也不要预留 `[URL]` 之类的占位符 ——
  上传脚本会自动把 url 追加到 body 末尾（以 `\n\n` 分隔）。
- 不要写「点击链接」这类依赖具体平台的话术，url 是脚本加的。

### 其它固定字段

| 字段 | 值 | 说明 |
|---|---|---|
| `color` | `"paper"` | 固定，对应白板纸质主题 |
| `hidden` | `false` | 固定 |
| `tags` | `["movie"]` | 固定 |

### 人读版

同时写一份 `动画说明.md`，内容与 JSON 一致（标题 + 说明文 + tags），方便人直接看。

## 2. 上传做了什么

`scripts/upload-movie.sh <noteid> <movie.mp4> --note <动画说明.json>` 会调用
`scripts/upload-movie.js`，它依次执行：

### 2.1 Storage 上传

- 目标路径：**`movie/<noteid>.mp4`**（noteid 默认等于 DocID，纯数字，URL 安全）
- `contentType: video/mp4`，`cacheControl: public, max-age=31536000`
- 给对象写入 `firebaseStorageDownloadTokens`，这样不需要把桶设成公开也能拿到 URL
- 大于 5MB 自动走 resumable upload

### 2.2 public url

```text
https://firebasestorage.googleapis.com/v0/b/<bucket>/o/movie%2F<noteid>.mp4?alt=media&token=<token>
```

桶名会依次尝试 `<project>.firebasestorage.app` 与 `<project>.appspot.com`。
需要对象本身也公开可读时加 `--public`（桶启用 uniform bucket-level access 时该步骤会告警但不失败）。

### 2.3 Firestore 文档

路径：`/users/<userid>/notes/<noteid>`

| 字段 | 类型 | 值 |
|---|---|---|
| `body` | string | 说明文 + `"\n\n"` + public url |
| `color` | string | `"paper"` |
| `hidden` | boolean | `false` |
| `id` | string | `<noteid>` |
| `tags` | array | `["movie"]` |
| `title` | string | 说明文标题 |
| `updatedAt` | timestamp | `FieldValue.serverTimestamp()` |

写入用 `set()`，所以**用同一个 noteid 重跑会覆盖同一条笔记**，不会产生重复。

### 2.4 FCM 通知

- 有 `--notify-token`（可重复）→ 用 `sendEachForMulticast` 逐个设备发
- 否则有 `--notify-topic` / `AISKILLS_FCM_TOPIC` → 发到 topic
- 两者都没有 → **跳过**并在输出里说明，这不算失败
- 通知内容：`title` = 笔记标题，`body` = 「新的动画已上传」，
  `data` = `{ noteId, userId, url, type: "movie" }`

> FCM 失败**不会**让上传失败：此时 Storage 与 Firestore 已经写入成功，
> 脚本只打印告警。报告里要如实写明「上传成功、通知失败」。

## 3. 密钥与 uid 的取值

优先级（脚本内部实现，skill 不要写死）：

1. `aiskillsrunner --firebasekey <路径>` / `--userid <ID>`
   → 环境变量 `AISKILLS_FIREBASE_ACCOUNT` / `AISKILLS_USERID`
2. 全局环境变量 `AISKILLS_MOVIE_KEY` / `AISKILLS_MOVIE_UID`（直接运行脚本时）
3. 内置默认值：
   - 密钥 `~/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json`
   - uid `KYTF9y43qgc39vKn0sI3qWpsjRE2`

> ⚠️ **不要用 `*_KEY` 命名环境变量传密钥路径。**
> dsh 的 subprocess 层会把名字匹配 `/KEY|PASSWORD|SECRET|TOKEN/i` 的变量从子进程环境剔除，
> 所以经 dsh 传来的名字是 `AISKILLS_FIREBASE_ACCOUNT`；`AISKILLS_MOVIE_KEY` 只在直接
> 运行 `upload-movie.sh` 时有效。

## 4. 空跑与排错

```bash
# 不写任何数据，只校验参数与文件
AISKILLS_MOVIE_DRY_RUN=1 \
  bash "$SKILL_DIR/scripts/upload-movie.sh" "$DOCID" "$DOC_DIR/movie.mp4" --note "$DOC_DIR/动画说明.json"

# 真实上传
bash "$SKILL_DIR/scripts/upload-movie.sh" "$DOCID" "$DOC_DIR/movie.mp4" --note "$DOC_DIR/动画说明.json"

# 只要机器可读结果
... --json
```

| 现象 | 原因 / 处理 |
|---|---|
| `找不到 firebase-admin` | `cd <skill>/scripts && npm install --omit=dev` |
| `找不到 Firebase 服务账号密钥` | 用 `AISKILLS_MOVIE_KEY` 指定路径，或放宽沙箱 |
| `密钥文件无法解析` | 拿到的不是服务账号 JSON（可能是 web config） |
| `找不到 storage bucket` | 项目的默认存储桶不存在或名字不同；检查 Firebase 控制台 |
| `⚠ 无法设为公开可读` | 桶启用了 uniform bucket-level access；token URL 仍然可用，可忽略 |
| `⚠ FCM 发送失败` | 上传已完成，检查 topic/token 是否正确 |
| 退出码 2 | 上传或写入失败；**不要立刻重试**，先看错误信息 |

## 5. 报告里的上传信息

最终报告必须包含：

- public url（完整，不要截断）
- Firestore 路径 `/users/<uid>/notes/<noteid>`
- FCM 状态：发到哪个 topic/token，或为什么跳过

最后一行必须是：

```text
SKILL_RESULT: OK docid=<DOCID> note=<noteid> url=<public url>
```
