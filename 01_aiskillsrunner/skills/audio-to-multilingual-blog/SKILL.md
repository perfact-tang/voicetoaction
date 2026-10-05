---
name: audio-to-multilingual-blog
description: >-
  将 aiskillsrunner 或人工提供的语音转文字（STT / Fast Whisper）素材审阅、重组为有明确主线的
  简体中文博客，审定后分别本地化翻译为日文和英文（含俚语、网络用语的自然表达），
  生成三语文章信息 JSON，写入 ~/Documents/tmpaiskill/ 的 DocID 目录，
  最后调用一次 vibecodingjapan-upblog 上传到 VibeCoding Japan 博客。
metadata:
  category: Content
  version: "1.2.0"
---

# 音声テキスト → 多言語ブログ → アップロード

## 0. 这个 skill 做什么

| | |
|---|---|
| 输入 | 一段语音转文字（ASR / STT）原始文本 |
| 输出 | 一个 DocID 目录 + 4 个文件，并已调用 **1 次** `vibecodingjapan-upblog` 完成上传 |

```
~/Documents/tmpaiskill/<DocID>/
├── 中文.md        # Step1：STT → 审阅、重组、写作后的简体中文定稿
├── 日文.md        # Step2：Step1 内容 → 母语级日文
├── 英文.md        # Step3：Step1 内容 → 母语级英文
└── 文章信息.json   # Step4：3 语言的 blogtitle / blogsummary / blogseo / blogmindmap
```

严格按 Step1（审阅素材 → 重组提纲 → 撰写中文 → 审定）→ Step2（日文）→ Step3（英文）→ Step4（元信息）→ 上传 的顺序执行。中文定稿通过审阅后才能开始翻译；日文和英文都直接依据同一份中文定稿，不得从 STT 各自写作或经日文转译英文。

录音是写作素材，录音的发言顺序不等于文章的逻辑顺序。先判断作者最值得传达的观点和读者需要理解的问题，再选择材料与结构。

---

## 1. 前置条件（先检查，缺一不可）

在开始生成之前，先确认下面 3 件事；任何一项不满足就**立即停止**并原样报告原因，不要继续生成：

1. `vibecodingjapan-upblog` 可用：
   ```bash
   command -v vibecodingjapan-upblog && vibecodingjapan-upblog --version
   ```
2. 临时根目录可写（不存在就创建）：
   ```bash
   mkdir -p "$HOME/Documents/tmpaiskill"
   ```
3. **沙箱权限**：本 skill 需要写工作区之外的 `~/Documents/tmpaiskill`，并读取 `~/Downloads` 下的 Firebase 密钥。
   因此启动方必须放开沙箱，例如：
   ```bash
   DSH_PERMISSION_MODE=danger-full-access aiskillsrunner --skill audio-to-multilingual-blog --prompt "<STT文本>"
   ```
   如果写文件时报 `sandbox: file access denied` / `Operation not permitted`，说明没有放开沙箱；
   请停止并在最终报告里写明这条命令，不要改用其它路径绕过。

---

## 1.5 执行纪律（headless 单回合语义，**必须遵守**）

本 skill 由 `aiskillsrunner` 通过 `dsh --profile headless <task>` 启动，这是**单回合**执行：

- **你一旦结束回合，进程立即退出。** 此时仍在运行的 `subagent`（后台子代理）会被一并杀掉，
  它们还没写完的文件不会落盘 —— 但退出码依然是 `0`，调用方会误以为成功。
  这正是「Blog 上没有文章，但任务记录显示 success」这类静默故障的成因（2026-10-05 真实发生过）。
- 因此：**在 Step8 上传成功、Step9 输出最终报告之前，绝对不要结束回合。**
  也不要把「稍等 / 正在等待子代理 / Progress so far」这类中间状态当作回合结尾 ——
  headless 下不会再有「收到通知后继续」的第二次机会。

翻译步骤（Step2 / Step3）的硬性要求：

- **默认自己顺序翻译**：先写完 `日文.md`，再写 `英文.md`，全部在本回合内完成。
- 确实要用 `subagent` 时，只能用**前台阻塞**方式（`run_in_background: false`），
  并在同一回合内取回结果后再继续；**禁止**依赖「稍后收到通知再继续」。
- 两份译稿 + `文章信息.json` 全部写完并通过第 7 节自检后，才可以进入上传。
- 如果发现还有子代理在跑：等它结束、拿到结果再往下走，不要提前收尾。

---

## 2. 解析输入，确定 DocID

本 skill 由 `aiskillsrunner` 通过 `dsh --profile headless` 启动。你看到的任务文本形如：

```
/<skill 名称>

请使用已安装的 skill "..." 完成以下任务，并在完成后输出处理结果报告。

任务提示词：
<--prompt 的内容>

参考文件（<绝对路径>）内容：
<--reffilepath 文件的内容>
```

解析规则（**严格照做**）：

1. **STT 正文**
   - 如果存在「参考文件（…）内容：」段落 → 把该段落之后的**全部文本**作为 STT 正文；
     此时「任务提示词：」段落只用来提取 DocID / 附加指示。
   - 否则 → 把「任务提示词：」之后的全部文本作为 STT 正文。
2. **DocID（可选覆盖）**：在任务文本中出现以下任一写法时，用其中的数字作为 DocID：
   `DocID: 1758…`、`DocID=1758…`、`docid: 1758…`、`--docid 1758…`
3. **没有 DocID 时自动生成**（默认路径）：
   ```bash
   DOCID=$(date +%s%3N)      # 与 vibecodingjapan-upblog 新建文章时的 docid 规则一致（Unix 毫秒）
   ```
4. **STT 正文为空 / 只有空白** → 停止，输出“没有收到 STT 文本，未生成任何文件”，**禁止编造内容**。

确定 DocID 后创建目录，后续所有文件都写到这里：

```bash
DOC_DIR="$HOME/Documents/tmpaiskill/$DOCID"
mkdir -p "$DOC_DIR"
```

> `<DOCID>` 必须是纯数字字符串（Unix 毫秒）。上传时会把同一个值作为 `--docid` 传下去，
> 保证**文件夹名 = 文章 docid**，方便之后用 `/editblog/<DOCID>` 继续编辑。

---

## 3. Step1 — 审阅素材并写成中文博客 → `中文.md`

必须先阅读 [references/step1.md](references/step1.md)，以 **STT 正文**为素材执行其中的审阅、结构重组、写作和定稿检查。通过检查后的中文文章写入 `$DOC_DIR/中文.md`。

保留作者的核心观点、支撑它的事实、案例和限制条件；可合并重复论述、删除无关岔题。文章的深度来自解释素材中的因果、取舍与意义，不来自堆砌篇幅或编造证据。素材短、信息少时写成扎实的短文，不强行扩成长篇。编辑笔记和内部提纲不写入正文。

## 4. Step2 — 将中文定稿本地化为日文 → `日文.md`

必须先阅读 [references/step2.md](references/step2.md)。以 **审定后的 `中文.md` 全文**为唯一内容基准，翻译、审校后写入 `$DOC_DIR/日文.md`。

## 5. Step3 — 将同一中文定稿本地化为英文 → `英文.md`

必须先阅读 [references/step3.md](references/step3.md)。以 **同一份审定后的 `中文.md` 全文**为唯一内容基准，翻译、审校后写入 `$DOC_DIR/英文.md`。不得以日文译稿作为源稿。

翻译时保留论证、事实、条件、态度与表达强度；俚语和网络用语按语境选取目标语言贴切的说法。中文定稿如有实质修改，先同步两份译稿，再生成元信息。

---

## 6. Step4 — 生成 3 语言文章信息 → `文章信息.json`

以 **中文定稿和两份已审校的译稿**为输入，套用下面的要求；同时阅读 [references/step4.md](references/step4.md)：

```text
あなたはBlogの編集アシスタントです。
以下の本文を読み、登録に必要なメタ情報を作成してください。

[出力要件]
- blogtitle: ブログの内容を正確に表すタイトル。
- blogsummary: 120〜220文字程度の簡潔な日本語サマリー。
- blogseo: 検索されやすいキーワードを「,」区切りで8〜14個。余計な説明は入れない。特に「AI動向, 業界ニュース, 事例紹介, 開催イベント, 雑談」の内容で優先分類して欲しい。
- blogmindmap: 本文の構成を表す Markdown 形式のマインドマップ（`# テーマ` → `## 章` → `- 項目`）。本文にない事実は足さない。

[注意]
- 本文にない事実を追加しない。
- 誇張した宣伝表現は避ける。

[要求]
日本語版、簡体字中国語版、英語版、3つ作ってください。
- ja の blogsummary は 120〜220 文字（日本語）。
- zh / en の blogsummary も同じ情報量の簡潔な要約にすること。
- blogseo は必ず**半角カンマ `,`** で区切り、各言語 8〜14 個。全角「，」は使わない。
- blogtitle / blogsummary / blogseo / blogmindmap は、必ずその言語の本文（ja/zh/en）に対応させること。

[ブログ本文]
<中文、日文、英文三份最终正文>
```

把结果按下面的结构写入 `$DOC_DIR/文章信息.json`（用 `write` 工具直接写 UTF-8，不要用会破坏多字节字符的转义方式）：

```json
{
  "docid": "<DOCID>",
  "userid": "",
  "ispublic": true,
  "createdat": <DOCID 的数字值>,
  "translations": {
    "ja": {
      "blogtitle": "…",
      "blogsummary": "…",
      "blogseo": "AI動向, 業界ニュース, …",
      "blogmindmap": "# …\n\n## …\n- …",
      "mindmapurl": "",
      "coverImageMode": "mindmap"
    },
    "zh": {
      "blogtitle": "…",
      "blogsummary": "…",
      "blogseo": "AI动态, 业界新闻, …",
      "blogmindmap": "# …\n\n## …\n- …",
      "mindmapurl": "",
      "coverImageMode": "mindmap"
    },
    "en": {
      "blogtitle": "…",
      "blogsummary": "…",
      "blogseo": "AI trends, industry news, …",
      "blogmindmap": "# …\n\n## …\n- …",
      "mindmapurl": "",
      "coverImageMode": "mindmap"
    }
  }
}
```

字段硬性要求：

- `docid` = 本次 DocID（字符串）；`createdat` = 同一个值（数字）。`userid` 留空字符串即可，
  上传时由 `--uid` 决定作者；`ispublic` 为 `true`。
- 3 个语言都必须有 `blogtitle`；`translations` 这一层不能省。
- `mindmapurl` 固定 `""`，`coverImageMode` 固定 `"mindmap"`（本 CLI 不生成封面图）。
- 正文不要写进 JSON（`markdown` 字段由 `--ja/--zh/--en` 的 Markdown 文件提供）。
- 不要写 `blogseo` 以外的自定义字段（拼错的字段会被 CLI 忽略并告警）。

---

## 7. 上传前自检（本地校验，不调用上传工具）

先完成内容检查，再执行文件校验：

- 中文：主线清楚，每节都有具体论点或材料支撑；相隔很远的相关内容已按逻辑归并；原文的核心观点、关键事实和限定条件没有丢失或被改写成更强的结论。没有为凑篇幅加入空泛议论。
- 日文、英文：逐节对照中文定稿，核对数字、专名、否定、可能性、因果与作者立场；俚语的意义和语气相符，目标语言读起来像自然的博客文章。
- 元信息：三语标题、摘要和思维导图对应最终文章的逻辑结构，不沿用录音发言顺序。

发现问题先改正文；若改动中文的内容或结构，同步译稿与元信息，再检查。

```bash
DOC_DIR="$HOME/Documents/tmpaiskill/$DOCID"
for f in 中文.md 日文.md 英文.md 文章信息.json; do
  [ -s "$DOC_DIR/$f" ] || { echo "✖ 缺失或为空：$DOC_DIR/$f"; }
done
node -e "const j=require(process.argv[1]);const t=j.translations;for(const l of ['ja','zh','en']){if(!t[l]?.blogtitle)throw new Error(l+' 缺少 blogtitle');if(!t[l]?.blogseo)throw new Error(l+' 缺少 blogseo');console.log(l,'ok',t[l].blogseo.split(',').length,'keywords')}" "$DOC_DIR/文章信息.json"
```

任何一项失败 → 修正后重新生成对应文件，再校验一次；仍然失败就停止并报告，**不要上传残缺内容**。

---

## 8. 上传（**只调用 1 次** `vibecodingjapan-upblog`）

```bash
SKILL_DIR="$HOME/.agents/skills/audio-to-multilingual-blog"
if [ ! -f "$SKILL_DIR/scripts/upload-blog.sh" ]; then
  SKILL_DIR=$(find "$HOME/.agents/skills" "$HOME/.dsh/skills" "$PWD/.agents/skills" "$PWD/.dsh/skills" \
    -maxdepth 2 -type d -name audio-to-multilingual-blog 2>/dev/null | head -n 1)
fi
bash "$SKILL_DIR/scripts/upload-blog.sh" "$DOCID"
```

脚本内部**只执行一次** `vibecodingjapan-upblog`，并传入：

```
--ja <DOC_DIR>/日文.md  --zh <DOC_DIR>/中文.md  --en <DOC_DIR>/英文.md
--info <DOC_DIR>/文章信息.json  --docid <DOCID>  --key <密钥>  --uid <uid>
```

- 密钥 / uid 的取值优先级（由脚本内部决定，skill 不要自己写死）：
  1. `aiskillsrunner --firebasekey <路径>` / `--userid <ID>`
     → 环境变量 `AISKILLS_FIREBASE_ACCOUNT` / `AISKILLS_USERID`；
  2. 全局环境变量 `AISKILLS_BLOG_KEY` / `AISKILLS_BLOG_UID`（直接运行脚本时用）；
  3. 脚本内置默认值（`~/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json`、`KYTF9y43qgc39vKn0sI3qWpsjRE2`）。
- **不要用 `*_KEY` 命名的环境变量**：dsh 的 subprocess 层会把名字匹配
  `/KEY|PASSWORD|SECRET|TOKEN/i` 的变量从子进程环境里剔除，所以密钥路径固定用
  `AISKILLS_FIREBASE_ACCOUNT`。
- 想先空跑验证（不写 Firebase）时加 `--dry-run`，或设 `AISKILLS_BLOG_DRY_RUN=1`。
- **不要因为失败就重复调用上传**。上传失败时直接停止并报告脚本输出；重复调用可能产生重复文章。

上传成功后 CLI 会打印文章路径 `/blog/<DOCID>` 与编辑路径 `/editblog/<DOCID>`。

---

## 9. 最终报告（必须输出）

用简洁的 Markdown 报告，至少包含：

1. **DocID** 与目录 `~/Documents/tmpaiskill/<DOCID>/`；
2. 4 个文件是否存在、各自字符数（`wc -m`）；
3. `文章信息.json` 的 3 语言标题与关键词个数；
4. 上传命令与结果（成功 / 失败原因），成功时给出 `/blog/<DOCID>`；
5. 若中途失败：停在哪一步、具体错误、需要人做什么。

不要在报告里重复粘贴整篇正文。

### 报告最后一行：机器可读标记（**必需**）

报告的**最后一行**必须是下面这条标记 —— 后端（mediasplitter-monitor）用它做产物校验：
它会拿 `<DOCID>` 去查博客数据库，确认文章真的存在；标记缺失或格式不对会被判为任务失败。

```
SKILL_RESULT: OK docid=<DOCID> blog=/blog/<DOCID>
```

中途失败（包括上传失败）时，最后一行改为：

```
SKILL_RESULT: FAILED step=<Step1|Step2|Step3|Step4|upload> reason=<一句话原因>
```

例：

```
SKILL_RESULT: OK docid=1791208565661 blog=/blog/1791208565661
```
