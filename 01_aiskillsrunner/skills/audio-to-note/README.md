# audio-to-note

一个 [DSH](https://github.com/deepseek-ai/deepseek-harness) skill：把 `aiskillsrunner` 传入的
**语音转文字（STT / Whisper / SenseVoice）原始文本**整理成一篇忠实、通顺的笔记，
按上传时选择的语言输出，写入 **Firestore 的 `/users/<uid>/notes/<noteid>`**
（与 `text-to-animation-movie` 写入的是同一个 Note 集合），然后用 FCM 给手机发一条通知。

- **不重组、不摘要、不加理论**：说话人的顺序就是笔记的顺序；只删除口头语、结巴、ASR 噪声，
  补标点、分段，必要时逐段翻译成目标语言。
- **语言自适应**：`zh` / `ja` / `en` / `ko`（`kr` 归一为 `ko`）。优先读 `AISKILLS_LANGUAGE`，
  也支持任务提示词里的 `语言：ja` / `Language: Japanese`，没有显式语言时自动判断。
- **零依赖发布**：`publish-note.mjs` 只用 Node 内置的 `crypto` / `fetch` 调 Firestore REST
  和 FCM v1，skill zip 安装后不需要 `npm install`。
- **和动画 Note 同一个地方**：`/users/<uid>/notes/<noteid>`，字段与 App 的 `NoteItem` 对齐。
- 工作目录按 **DocID** 组织：`~/Documents/tmpaiskill/<DocID>/`。
- 最后**只调用 1 次** `publish-note.sh`，完成 Firestore 写入 + 读回校验 + FCM。

## 目录结构

```
audio-to-note/
├── SKILL.md                       # 完整流程（Step1..Step4）与硬性要求
├── README.md
├── references/
│   ├── 01-cleanup.md              # 逐语言整理规则与前后对照示例
│   └── 02-publish.md              # Note 字段、Firestore 路径、FCM 细节
├── examples/
│   ├── stt-sample-zh.txt          # 带口头语/重复/数字的示例 STT
│   ├── stt-sample-ja.txt
│   ├── stt-sample-en.txt
│   └── stt-sample-ko.txt
└── scripts/
    ├── publish-note.mjs           # Firestore REST + FCM v1（零依赖）
    ├── publish-note.sh            # 参数/密钥/uid/环境变量入口
    ├── validate-note.mjs          # 语言、长度、数字、编辑话术校验
    └── run-with-aiskillsrunner.sh # 便捷入口：带正确沙箱权限调用 aiskillsrunner
```

## 安装

`aiskillsrunner` 的默认安装目录是 `~/.agents/skills`，也是 `dsh-skill-filesystem` 的
`user-agents` 根：

```bash
mkdir -p ~/.agents/skills
cp -r audio-to-note ~/.agents/skills/

# 确认可被发现
ls ~/.agents/skills/audio-to-note/SKILL.md
```

或者把 `audio-to-note.zip` 上传到 CMS（`type = deepseek_harness`，
`applicationName = audio-to-note`），由后端按版本安装到 `monitor-data/skills`。
zip 根目录必须是 `audio-to-note/`（内含 `SKILL.md`）。

> `applicationName` 必须精确写成 `audio-to-note`：后端只对这个名字加
> `输出语言：…` 提示词和 Note 产物校验。

## 前置条件

```bash
node -v                 # Node 18+（脚本使用内置 fetch / crypto）
mkdir -p "$HOME/Documents/tmpaiskill"
ls -l ~/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json
```

不需要 `npm install`，也不需要 `firebase-admin`。

## 运行

推荐用便捷入口（自动设置 `DSH_PERMISSION_MODE=danger-full-access`）：

```bash
# 指定语言（ja / zh / en / ko）
~/.agents/skills/audio-to-note/scripts/run-with-aiskillsrunner.sh \
  --reffilepath ./stt.txt --env AISKILLS_LANGUAGE=ja

# 语言也可以写在提示词里
~/.agents/skills/audio-to-note/scripts/run-with-aiskillsrunner.sh \
  --prompt "输出语言：zh。DocID: 1759700000000" --reffilepath ./stt.txt
```

等价的手写命令：

```bash
DSH_PERMISSION_MODE=danger-full-access \
  aiskillsrunner --skill audio-to-note --reffilepath ./stt.txt \
  --env AISKILLS_LANGUAGE=ja \
  --firebasekey ~/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json \
  --userid KYTF9y43qgc39vKn0sI3qWpsjRE2
```

后端（`monitor.js`）在 Skills 流程里会自动：

- 把录音文档的 `language`（`zh` / `ja` / `en` / `kr`）归一成 `ko` 后，以
  `AISKILLS_LANGUAGE` 传给 skill；
- 在任务提示词里加一行 `输出语言：…`，即使环境变量被过滤也能读到；
- 如果 skill 最终输出 `SKILL_RESULT: OK … notified=1`，跳过它自己的通用「执行完毕」推送，
  避免一次任务弹两条通知。

## 生成的文件

```
~/Documents/tmpaiskill/<DocID>/
├── 输入文本.md      # 收到的 STT 原文（原样留档）
├── 笔记.md          # 人读版：标题 + 整理后的正文
├── 笔记.json        # 机器版：title / body / language / tags / ...
└── 发布结果.json    # Firestore 路径、读回校验、FCM 统计
```

`<DocID>` 默认是 Unix 毫秒，也是 Note 文档 id，所以：

```
目录名 = DocID = /users/<uid>/notes/<noteid>
```

如果输入里出现 `DocID: 1758…` / `docid=…` / `--docid …`，优先使用其中的值。

## Note 字段

写入 `/users/<uid>/notes/<noteid>`：

| 字段 | 值 |
|---|---|
| `id` | noteid |
| `title` | 笔记标题 |
| `body` | 整理后的正文 |
| `tags` | `["voice", "note"]` |
| `color` | `"paper"` |
| `hidden` | `false` |
| `language` | `zh` / `ja` / `en` / `ko` |
| `updatedAt` | Firestore 服务端时间 |

> `hidden` 必须是 `false`：App 的 `refreshNotes()` 查询条件是
> `whereEqualTo("hidden", false)`，缺字段或为 `true` 都不会显示。

## 发布脚本

```bash
bash scripts/publish-note.sh <笔记.json> [选项]
```

| 参数/环境变量 | 说明 |
|---|---|
| `--noteid <id>` | Note 文档 id；默认 JSON 的 `noteid` / `docid`，再退化到当前毫秒 |
| `--userid <uid>` / `AISKILLS_USERID` | 用户 uid |
| `--key <path>` / `AISKILLS_FIREBASE_ACCOUNT` | 服务账号 JSON 路径 |
| `--language <code>` / `AISKILLS_LANGUAGE` | `zh` / `ja` / `en` / `ko` |
| `--dry-run` / `AISKILLS_NOTE_DRY_RUN=1` | 只校验并打印，不写 Firebase、不发通知 |
| `--no-notify` / `AISKILLS_NOTE_NOTIFY=0` | 写 Note，但不发 FCM |
| `--notify-topic <t>` / `AISKILLS_FCM_TOPIC` | 没有设备 token 时的 topic 兜底 |
| `--notify-token <t>` | 指定设备 token（可重复） |
| `--result <path>` | 结果 JSON 路径；默认 `<笔记.json 目录>/发布结果.json` |
| `--json` | 结果 JSON 打到 stdout（日志走 stderr） |

示例：

```bash
# 空跑：不写任何数据
AISKILLS_NOTE_DRY_RUN=1 bash scripts/publish-note.sh ~/Documents/tmpaiskill/1759/笔记.json

# 真实写入 + 通知
AISKILLS_FIREBASE_ACCOUNT=~/Downloads/vibecodingjapan-....json \
AISKILLS_USERID=KYTF9y43qgc39vKn0sI3qWpsjRE2 \
  bash scripts/publish-note.sh ~/Documents/tmpaiskill/1759/笔记.json --noteid 1759
```

## 校验脚本

```bash
node scripts/validate-note.mjs ~/Documents/tmpaiskill/1759/笔记.json \
  --language ja --source ~/Documents/tmpaiskill/1759/输入文本.md
```

检查：

- JSON 结构、`hidden=false`、`noteid`；
- 标题/正文语言是否与目标语言一致（Unicode 字符集启发式）；
- 编辑话术、占位符、连续重复句、Markdown 标题/项目符号；
- 原文数字是否基本保留；
- 同语言时正文长度是否在原文的 60%–140%（防止过度摘要或扩写）。

`✖` 必须修到通过；`⚠` 需要逐条判断，不要靠删 `--source` 绕过。

## FCM 通知

- 读取 `users/<uid>/fcmTokens`（App 登录后自动登记，退出登录时删除）。
- 每台设备发一条：标题 = 笔记标题；正文 = 目标语言的「语音笔记已保存」；
  渠道 = `luyin_job_result`（与 `PushNotifications.kt` 的 `JOB_CHANNEL_ID` 一致）。
- 没有 token → `notify.skipped = "no-tokens"`，笔记仍然保存成功。
- 失效 token（`UNREGISTERED` / 404）→ 自动从 Firestore 删除，继续发其它设备。
- FCM 失败不影响已写入的 Note；结果里会带 `notify.error`。

## 测试

```bash
chmod +x scripts/*.sh
bash -n scripts/publish-note.sh
node --check scripts/publish-note.mjs
node --check scripts/validate-note.mjs

# 用示例 STT 跑一遍完整流程（dry-run，不写 Firebase、不发通知）
AISKILLS_NOTE_DRY_RUN=1 \
  scripts/run-with-aiskillsrunner.sh \
  --reffilepath examples/stt-sample-ja.txt \
  --env AISKILLS_LANGUAGE=ja
```

> `AISKILLS_NOTE_DRY_RUN=1` / `AISKILLS_NOTE_NOTIFY=0` 是**启动环境的安全开关**，
> skill 必须遵守，不会为了「完成发布」把它们覆盖成真实写入。

## 注意事项

- **失败即报告，不要重复发布**：`publish-note.sh` 每次只调用一次；失败先读错误。
- **不要用同一个 noteid 反复发通知**：重跑会覆盖同一条 Note，但可能重复推送。
- **不要为了「更好读」重组内容**：这是本 skill 和博客 skill 最大的区别。
- **不要把正文只写在 reasoning 里**：必须用 `write` / `bash` 落盘。
- `AISKILLS_NOTE_KEY` / `AISKILLS_NOTE_UID` 只适合直接运行脚本；
  经 `dsh` 时请用 `AISKILLS_FIREBASE_ACCOUNT` / `AISKILLS_USERID`（避免 `KEY` 被过滤）。
