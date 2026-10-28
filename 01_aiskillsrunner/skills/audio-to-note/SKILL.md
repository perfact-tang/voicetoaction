---
name: audio-to-note
description: >-
  把语音转文字（STT / Whisper / SenseVoice）的原始结果整理成一篇通顺、忠实的笔记：
  按上传时选择的语言（简体中文 / 日语 / 英语 / 韩语）输出，删掉口头语与 ASR 噪声，
  保留原意、细节和说话人的叙事顺序，不添加原文没有的理论、标题或结论；
  然后写入 Firestore 的 /users/<userid>/notes/<noteid>（与 text-to-animation-movie
  写入的是同一个 Note 集合），并给该用户的手机发送一条 FCM 通知。
metadata:
  category: Content
  version: "1.0.0"
---

# 语音 → 整理后的 Note → 手机通知

## 0. 这个 skill 做什么

| | |
|---|---|
| 输入 | 一段语音转文字（STT / ASR）原始文本；上传时选择的语言（zh / ja / en / ko） |
| 输出 | 一篇整理后的笔记，写入 Firestore 的 `/users/<userid>/notes/<noteid>`，并发送 FCM |

```
~/Documents/tmpaiskill/<DocID>/
├── 输入文本.md      # 收到的 STT 原文，原样留档
├── 笔记.md          # 人读版：标题 + 整理后的正文
├── 笔记.json        # 机器版：title / body / language / tags / ...
└── 发布结果.json    # 发布脚本写回的结果（Firestore 路径、FCM 统计）
```

执行顺序固定为 **Step1 读懂并整理 → Step2 校验 → Step3 发布 → Step4 报告**。
中途不要结束回合，也不要把「稍等 / 正在处理」当作回合结尾（见 1.5）。

**和 audio-to-multilingual-blog 的区别**：那个 skill 会重组文章结构；**本 skill 不重组**。
说话人的顺序就是笔记的顺序。只做「去掉口语噪声、补标点、分段、必要时翻译成目标语言」，
不做摘要、不换结构、不添加理论、不写原文没有的标题和结论。

---

## 1. 前置条件（先检查，缺一不可）

```bash
node -v                 # 需要 Node 18+（脚本使用内置 fetch / crypto）
command -v node
mkdir -p "$HOME/Documents/tmpaiskill"

# Firebase 服务账号密钥。优先级见 scripts/publish-note.sh
KEY="${AISKILLS_FIREBASE_ACCOUNT:-${AISKILLS_NOTE_KEY:-${AISKILLS_MOVIE_KEY:-$HOME/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json}}}"
[ -s "$KEY" ] || { echo "✖ 找不到 Firebase 密钥: $KEY" >&2; }
```

**沙箱权限**：本 skill 需要写工作区之外的 `~/Documents/tmpaiskill`，并读取
`~/Downloads` 下的 Firebase 密钥。启动方必须放开沙箱：

```bash
DSH_PERMISSION_MODE=danger-full-access \
  aiskillsrunner --skill audio-to-note --reffilepath ./stt.txt --env AISKILLS_LANGUAGE=ja
```

如果写文件时报 `sandbox: file access denied` / `Operation not permitted`，说明没有放开沙箱；
请停止并在最终报告里写明这条命令，**不要改用其它路径绕过**。

发布脚本**不需要 npm install**：它用 Node 内置的 `crypto` / `fetch` 直接调用
Firestore REST 与 FCM v1，所以 skill 目录里没有 `node_modules` 也能跑。

---

## 1.5 执行纪律（headless 单回合语义，**必须遵守**）

本 skill 由 `aiskillsrunner` 通过 `dsh --profile headless <task>` 启动，这是**单回合**执行：

- **你一旦结束回合，进程立即退出。** 之后不会再有机会「继续」。
- 因此：**在 Step3 发布成功、Step4 输出最终报告之前，绝对不要结束回合。**
- 必须用工具（`write` / `bash`）把内容**真正写到磁盘**；禁止把整篇笔记只写在 reasoning 里。
- 每写完一个文件，就用 `bash` 确认它存在且非空，再进入下一步。
- 每个 `bash` 调用都是**新的 shell**：`DOCID` / `DOC_DIR` / `NOTE_LANG` / `SKILL_DIR`
  不会自动保留；后续命令里重新设置，或把相关命令写在同一次 `bash` 调用里。
- 不要用后台子代理等待通知；所有步骤在本回合内同步完成。

---

## 2. 解析输入：正文、DocID、目标语言

你看到的任务文本形如：

```
/audio-to-note

请使用已安装的 skill "audio-to-note" 完成以下任务……
执行纪律……

任务提示词：
输出语言：ja（……）
运行参数（由 aiskillsrunner 以环境变量传入……）：
- AISKILLS_LANGUAGE：ja（环境变量 AISKILLS_LANGUAGE）

参考文件（/path/to/stt.txt）内容：
<STT 正文>
```

### 2.1 正文

**严格照做**：

1. 存在「参考文件（…）内容：」段落 → **优先读取 header 里的绝对路径**（`参考文件（/path/to/stt.txt）内容：`）
   指向的文件；读不到时，才用该段落之后的文本。若后面还有「运行参数（…）」段落，
   **到那里就停止** —— 那是 aiskillsrunner 的参数说明，不是 STT 正文。
2. 否则 → 「任务提示词：」之后的全部文本是 STT 正文；
   同样在遇到「运行参数（…）」/「执行纪律」段落时停止。
3. 正文为空 / 只有空白 → 停止，输出「没有收到 STT 文本，未写入任何 Note」，**禁止编造内容**。

把正文原样写入 `$DOC_DIR/输入文本.md`（留档，便于复现和校验）。
**不要**把「任务提示词」「执行纪律」「运行参数」这几段写进 `输入文本.md`。

### 2.2 DocID

出现以下任一写法时，用其中的数字：

- `DocID: 1758…` / `DocID=1758…`
- `docid: 1758…` / `docid=1758…`
- `--docid 1758…`

没有时自动生成：

```bash
DOCID=$(date +%s%3N)
```

`<DocID>` 必须是纯数字（Unix 毫秒）。它同时是**工作目录名**和 **Note 文档 id**：

```bash
DOC_DIR="$HOME/Documents/tmpaiskill/$DOCID"
mkdir -p "$DOC_DIR"
```

### 2.3 目标语言（**优先级从高到低**）

1. **环境变量 `AISKILLS_LANGUAGE`**（后端从录音文档的 `language` 字段传下来，
   例如 `zh` / `ja` / `en` / `ko` / `kr`）。
2. **任务提示词里的显式写法**，例如：
   - `输出语言：ja` / `语言：日语` / `Language: English` / `--language ko` / `lang=zh`
3. **没有显式语言时**，根据正文判断主要语言（`zh` / `ja` / `en` / `ko`），
   并在报告里写「语言：自动判断」。

归一化规则：

| 输入写法 | 目标代码 | 输出语言 |
|---|---|---|
| `zh` / `cn` / `zh-CN` / 中文 / 简体中文 / Chinese | `zh` | 简体中文 |
| `ja` / `jp` / `ja-JP` / 日语 / 日本語 / Japanese | `ja` | 日语 |
| `en` / `en-US` / 英语 / English | `en` | 英语 |
| `ko` / `kr` / `ko-KR` / 韩语 / 韓国語 / Korean | `ko` | 韩语 |

把归一化后的结果记为 **`NOTE_LANG`**（不要用 `$LANG`，那是系统 locale 变量）：

```bash
NOTE_LANG="${AISKILLS_LANGUAGE:-}"   # 再按上表归一化；为空则按正文判断
```

**关键判断**：

- 如果 STT 正文已经是目标语言 → **只整理，不翻译**。
- 如果 STT 正文不是目标语言（例如选了日语，但转写混进了中文/英文）→
  **按目标语言逐段忠实翻译**，保持原顺序、原段落、原语气；专名和技术词保留原样。
- 不要因为目标语言是英语就把日文原名改成英文解释；不要添加原文没有的背景知识。

---

## 3. Step1 — 读懂并整理 → `笔记.md` + `笔记.json`

开始前先读 [references/01-cleanup.md](references/01-cleanup.md)，严格按其中的规则做。

### 3.1 先读完整篇，再动手

- 先把 `$DOC_DIR/输入文本.md` 从头读到尾，理解说话人讲了哪几件事、每件事的顺序。
- 可以在心里列一个「要点顺序」清单，但**不要把它写进交付文件**，也不要据此重排段落。
- 不要一边读一边改写；读完再写。

### 3.2 整理规则（硬性）

**只做这些：**

1. 删除口头语、语气词、结巴、重复起头、ASR 噪声：
   - 中文：嗯、啊、呃、那个、就是、然后（纯填充时）、这个、怎么说呢、对吧、你知道吗；
   - 日文：えーと、あの、まあ、その、なんか、ちょっと（纯填充时）、ですね；
   - 英文：um、uh、like、you know、I mean、sort of、kind of、right?（纯填充时）；
   - 韩文：음、어、그、저、뭐、약간（纯填充时）。
2. 修正明显的 STT 错字、同音字、断句错误；补上正常的标点。
3. 把一口气说很长的一段，按**说话人自己的话题转换**分成自然段；不改变段落先后。
4. 统一数字、日期、单位的写法；原文明确说出的数字必须保留。
5. 保留第一人称、口吻、情绪、限定条件（「可能」「大概」「我觉得」「前提是」）。
6. 如果目标语言与正文语言不同，按目标语言翻译；逐段对应，不合并、不拆分原意。

**绝对不要做这些：**

- ❌ 不要重排顺序、不要按「问题—原因—结论」重组、不要合并相隔很远的段落。
- ❌ 不要写摘要、不要删掉具体例子/细节/数字/专名来「变简洁」。
- ❌ 不要添加原文没有的理论、概念、定义、框架、建议、行动项、背景知识。
- ❌ 不要添加小标题、编号、项目符号（除非说话人自己就在逐条列举）。
- ❌ 不要写「以下是整理后的内容」「综上所述」「希望对你有帮助」这类编辑话术。
- ❌ 不要把「我觉得可能」改成「事实证明」，不要把相关性改成因果。
- ❌ 不要编造原文没有的数据、案例、引语、产品能力或验证结果。
- ❌ 不要为了凑长度扩写；也不要为了短而删掉实质内容。

### 3.3 标题

- 用一句话概括说话人真正讲的事情，**从原文内容里来**，不要引入新概念。
- 中文/日文 8–30 字；英文 3–12 个词；韩文 8–30 字。
- 不要夸张、不要标题党、不要用「深度解析」「你必须知道」这类原文没有的包装。
- 标题不是正文的一部分；`笔记.json` 的 `body` 里不要重复标题。

### 3.4 写文件

先写 `$DOC_DIR/笔记.json`（UTF-8，不要用会破坏多字节字符的转义方式）：

```json
{
  "docid": "1759700000000",
  "noteid": "1759700000000",
  "language": "ja",
  "title": "先别急着换工具，先把流程跑顺",
  "body": "整理后的正文。\n\n第二段……",
  "tags": ["voice", "note"],
  "color": "paper",
  "hidden": false
}
```

字段要求：

| 字段 | 要求 |
|---|---|
| `docid` / `noteid` | 与 `$DOCID` 完全一致（字符串） |
| `language` | `zh` / `ja` / `en` / `ko` |
| `title` | 非空，符合 3.3 |
| `body` | 非空，**只放正文**，用 `\n\n` 分段；不要放标题、标签、URL |
| `tags` | 固定 `["voice", "note"]` |
| `color` | 固定 `"paper"` |
| `hidden` | 固定 `false`（缺这个字段 App 会查不到） |

再写人读版 `$DOC_DIR/笔记.md`：

```markdown
# {title}

{body}
```

写完后立刻确认两个文件都非空：

```bash
for f in 笔记.md 笔记.json; do
  [ -s "$DOC_DIR/$f" ] && echo "✔ $f $(wc -m < "$DOC_DIR/$f") 字" || echo "✖ 缺失或为空：$f"
done
```

---

## 4. Step2 — 本地校验 → 不合格就改

```bash
SKILL_ROOT="${AISKILLSRUNNER_SKILLS_DIR:-$HOME/.agents/skills}"
SKILL_DIR="$SKILL_ROOT/audio-to-note"
if [ ! -f "$SKILL_DIR/scripts/validate-note.mjs" ]; then
  SKILL_DIR=$(find "$HOME/.agents/skills" "$HOME/.dsh/skills" "$SKILL_ROOT" \
    "$PWD/monitor-data/skills" "$PWD/.agents/skills" "$PWD/.dsh/skills" \
    "$PWD/01_aiskillsrunner/skills" \
    -maxdepth 2 -type d -name audio-to-note 2>/dev/null | head -n 1)
fi
[ -f "$SKILL_DIR/scripts/validate-note.mjs" ] || { echo "找不到 audio-to-note 的 skill 目录" >&2; exit 1; }

node "$SKILL_DIR/scripts/validate-note.mjs" "$DOC_DIR/笔记.json" \
  --language "$NOTE_LANG" --source "$DOC_DIR/输入文本.md"
```

校验会检查：

- JSON 结构、必填字段、`hidden=false`；
- 正文/标题的语言是否与目标语言一致（脚本按 Unicode 字符集判断）；
- 是否出现「以下是整理」这类编辑话术、占位符、连续重复句；
- 原文里的数字是否基本都还在（漏了会 warning）；
- 同语言时正文长度是否在原文的 60%–140% 之间（防止过度摘要或扩写）。

**有 `✖`（error）必须改到通过；`⚠`（warning）要逐条判断**：
数字缺失、长度异常通常意味着漏内容或加内容，应回到 Step1 修正。
不要靠改校验脚本或删掉 `--source` 来「通过」。

---

## 5. Step3 — 发布：Firestore Note + FCM（**只调用 1 次**）

> **启动环境里的安全开关优先级最高，禁止覆盖**：
> - `AISKILLS_NOTE_DRY_RUN=1` → 本次只能 dry-run，**不要**改成 0，也不要用别的方式绕过；
>   报告里必须写「dry-run：未写 Firebase、未发通知」。
> - `AISKILLS_NOTE_NOTIFY=0` → 即使真实写入，也不能发通知。
>
> 只有启动环境没有设置这些变量（或明确为 0）时，才执行真实发布。

```bash
bash "$SKILL_DIR/scripts/publish-note.sh" "$DOC_DIR/笔记.json" \
  --noteid "$DOCID" --language "$NOTE_LANG"
```

脚本内部按顺序做：

1. 读取并做写入前的基本校验（title / body / language / hidden / 大小）；
2. 用服务账号换取 OAuth token（Node 内置 `crypto` 签 JWT，无第三方依赖）；
3. 写入 Firestore **`/users/<userid>/notes/<noteid>`**（与 `text-to-animation-movie`
   同一个 Note 集合），字段与 App 的 Note 模型对齐：
   `id` / `title` / `body` / `tags` / `color` / `hidden` / `language` / `updatedAt`；
4. 读回该文档确认写入成功；
5. 读取 `users/<userid>/fcmTokens`，给该用户的每台设备发 FCM：
   - 标题 = 笔记标题；
   - 正文 = 目标语言的「语音笔记已保存」；
   - 通知渠道 = `luyin_job_result`（与 App 的 `PushNotifications.kt` 一致）；
   - data 里带 `type=note` / `noteId` / `documentId` / `language`；
6. 把结果写到 `$DOC_DIR/发布结果.json`。

密钥 / uid 的取值优先级（由脚本内部决定，skill 不要写死）：

1. `aiskillsrunner --firebasekey <路径>` / `--userid <ID>`
   → 环境变量 `AISKILLS_FIREBASE_ACCOUNT` / `AISKILLS_USERID`；
2. 全局环境变量 `AISKILLS_NOTE_KEY` / `AISKILLS_NOTE_UID`
   （直接运行脚本时用；经 dsh 时这些名字会被过滤，优先用上面的 `AISKILLS_*`）；
3. 脚本内置默认值（`~/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json`、
   uid `KYTF9y43qgc39vKn0sI3qWpsjRE2`）。

常用开关：

```bash
# 只校验、不写 Firebase、不发通知（开发/演示用）
AISKILLS_NOTE_DRY_RUN=1 bash "$SKILL_DIR/scripts/publish-note.sh" "$DOC_DIR/笔记.json"

# 写入但不发通知（排障用）
bash "$SKILL_DIR/scripts/publish-note.sh" "$DOC_DIR/笔记.json" --no-notify

# 指定 topic（没有设备 token 时的兜底）
AISKILLS_FCM_TOPIC=all bash "$SKILL_DIR/scripts/publish-note.sh" "$DOC_DIR/笔记.json"
```

**发布失败时不要重复调用**：脚本失败会打印具体错误并返回非零；先读错误，
修好后再调用一次。重复调用会用同一个 noteid 覆盖同一条笔记（不会产生重复文档），
但可能重复发通知。

`发布结果.json` 里重点看：

```json
{
  "ok": true,
  "notePath": "users/KYTF.../notes/1759700000000",
  "firestore": { "written": true, "verified": true },
  "notify": { "sent": 1, "failed": 0, "skipped": null }
}
```

- `notify.skipped = "no-tokens"`：该用户还没有登记设备令牌（App 登录后会自动登记）；
  笔记已经保存成功，**不算发布失败**，但要在报告里写明「未找到设备令牌，通知未发送」。
- `notify.failed > 0`：FCM 失败不影响笔记；脚本会清理失效 token 并在报告里列出。

---

## 6. Step4 — 最终报告（必须输出）

用简洁的 Markdown 报告，至少包含：

1. **DocID** 与目录 `~/Documents/tmpaiskill/<DocID>/`；
2. **目标语言**（`zh` / `ja` / `en` / `ko`，以及是显式选择还是自动判断）；
3. `笔记.md` / `笔记.json` 是否存在、正文字符数、段落数、标题；
4. **Firestore 路径** `/users/<uid>/notes/<noteid>` 与写入/读回结果；
5. **FCM**：发送了几台设备、成功/失败、跳过原因（没有 token 要说明）；
6. 若中途失败：停在哪一步、具体错误、需要人做什么。

不要在报告里重复粘贴整篇正文。

### 报告最后一行：机器可读标记（**必需**）

```text
SKILL_RESULT: OK docid=<DOCID> note=<noteid> notified=<1|0>
```

- `notified=1`：至少一台设备收到了 FCM 受理；
- `notified=0`：笔记已保存，但没有可用 token / 发送失败（报告里要说明）。

中途失败时最后一行改为：

```text
SKILL_RESULT: FAILED step=<Step1|Step2|publish> reason=<一句话原因>
```

例：

```text
SKILL_RESULT: OK docid=1759700000000 note=1759700000000 notified=1
```

> 后端 `monitor.js` 看到 `notified=1` 会跳过它自己的通用「执行完毕」推送，
> 避免一次任务弹两条通知；没有这个标记时仍按原逻辑推送。

---

## 7. 目录结构参考

```
audio-to-note/
├── SKILL.md
├── README.md
├── references/
│   ├── 01-cleanup.md       # 逐语言的整理规则与对照示例
│   └── 02-publish.md       # Note 字段、Firestore 路径、FCM 细节
├── examples/
│   ├── stt-sample-zh.txt
│   ├── stt-sample-ja.txt
│   ├── stt-sample-en.txt
│   └── stt-sample-ko.txt
└── scripts/
    ├── publish-note.mjs    # Firestore REST + FCM v1（零依赖）
    ├── publish-note.sh     # 参数/密钥/uid 的解析与环境变量入口
    ├── validate-note.mjs   # 语言、长度、数字、编辑话术校验
    └── run-with-aiskillsrunner.sh
```

## 8. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| `sandbox: file access denied` | 没有用 `DSH_PERMISSION_MODE=danger-full-access` 启动；按 1 节命令重跑 |
| `找不到 Firebase 密钥` | 用 `--firebasekey` 或 `AISKILLS_FIREBASE_ACCOUNT` 指定；不要在命令里写死 |
| `Firestore 写入失败: 403` | 服务账号没有该项目的 Firestore 权限，或密钥文件不是这个项目的 |
| `notify.skipped = "no-tokens"` | 用户没有登记设备令牌；App 登录并允许通知后会自动登记 |
| `notify.failed = 1` | token 失效或网络问题；脚本会清理失效 token，笔记不受影响 |
| 校验报「语言不匹配」 | 目标语言和正文语言不一致；按 2.3 的规则翻译，不要改 `--language` 掩盖 |
| 校验报「可能过度摘要」 | 正文比原文短太多；回到 Step1 补回被删掉的细节，不要扩写 |
| 校验报「数字缺失」 | 原文的关键数字没保留；回查原文并补上 |
| 发布成功但 App 看不到 | 确认 `hidden=false`、路径是 `/users/<uid>/notes/<noteid>`，且 App 已刷新 |
