---
name: audio-to-multilingual-blog
description: >-
  把 aiskillsrunner 传入的语音转文字（STT / Fast Whisper）原始文本，按固定 4 步处理成
  简体中文精炼原稿、母语级日文、母语级英文，生成 3 语言文章信息 JSON，写入
  ~/Documents/tmpaiskill/<DocID>/，最后调用一次 vibecodingjapan-upblog 上传到
  VibeCoding Japan 博客（Firestore + Cloud Storage）。
whenToUse: >-
  当 aiskillsrunner（或人工）提供一段语音转文字原稿，需要转成 ja/zh/en 三语博客并登记到
  VibeCoding Japan 博客时使用。
metadata:
  category: Content
  version: "1.0.0"
---

# 音声テキスト → 多言語ブログ → アップロード

## 0. 这个 skill 做什么

| | |
|---|---|
| 输入 | 一段语音转文字（ASR / STT）原始文本 |
| 输出 | 一个 DocID 目录 + 4 个文件，并已调用 **1 次** `vibecodingjapan-upblog` 完成上传 |

```
~/Documents/tmpaiskill/<DocID>/
├── 中文.md        # Step1：STT → 精炼简体中文原稿
├── 日文.md        # Step2：Step1 内容 → 母语级日文
├── 英文.md        # Step3：Step1 内容 → 母语级英文
└── 文章信息.json   # Step4：3 语言的 blogtitle / blogsummary / blogseo / blogmindmap
```

严格按 Step1 → Step2 → Step3 → Step4 → 上传 的顺序执行，不要跳步，不要合并步骤。

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

## 3. Step1 — 生成简体中文原稿 → `中文.md`

以 **STT 正文**为用户输入，套用下面的规则；结果写入 `$DOC_DIR/中文.md`。

```text
# Role
你是一名顶级的文本编辑与速记整理专家，专精于将粗糙的语音转文字（ASR）文本转化为高质量、易于阅读的精炼文章。使用简体中文输出。

# Goal
将用户提供的 Fast Whisper 原始转换文本进行重构。在[**绝对不删减任何核心信息和细节**]的前提下，极大地提升文章的可读性、逻辑性和流畅度。

# Optimization Rules (优化原则)
1. **信息零删减（最高原则）**：严禁删除任何有实质意义的词汇、数据、观点、细节或案例。即使原文表达啰嗦，也要将其中的核心信息保留下来。
2. **逻辑与顺序重构**：允许且鼓励打破原文凌乱的叙述顺序。请按照“因果、时间、递进或逻辑关联”重新调整段落和句子顺序，使其形成一条清晰的逻辑主线。
3. **口语化清理**：消除语音转文字带来的“呃、啊、然后、就是说、对”等语气词；修正重复发声；将大段大段无标点的“长难句”拆分为短小精悍、易于阅读的句子。
4. **错别字与同音字修正**：基于语境，自动修正 Fast Whisper 常见的同音错别字（例如将“做业务”误转为“坐义务”等）。
5. **排版优化**：使用合理的段落切分、加粗核心观点、或在必要时使用分条列点（Markdown 格式），让文章产生“一眼可见”的清晰结构。

# Workflow (工作流)
1. 深度通读用户给出的原始文本，提取出所有的核心事实和信息点。
2. 梳理这些信息点之间的内在逻辑，规划出最适合阅读的段落结构。
3. 撰写重构文本，润色文笔，确保语气自然（可根据原文判断是商务、科技还是日常分享语调）。
4. 对照原文进行最终检查，确保没有任何信息点被遗漏。

# 原始文本:
<STT 正文>
```

---

## 4. Step2 — 生成母语级日文 → `日文.md`

以 **Step1 生成的中文原稿全文**为输入：

```text
以下の簡体字中国語の原稿を、ネイティブな日本語に翻訳してください。
- 情報を追加・削除しないこと（Step1 の情報点をすべて保持する）。
- 直訳調ではなく、日本語の技術ブログとして自然な文体・用語にすること。
- Markdown の見出し・箇条書き・強調などの構造を維持すること。
- 本文だけを出力すること（説明や前置きは書かない）。

[原稿]
<Step1 の全文>
```

结果写入 `$DOC_DIR/日文.md`。

---

## 5. Step3 — 生成母语级英文 → `英文.md`

以 **Step1 生成的中文原稿全文**为输入：

```text
Translate the following Simplified-Chinese manuscript into native, publication-quality English.
- Do not add or remove any information (keep every point from the source).
- Use natural technical-blog English, not a literal translation.
- Keep the Markdown structure (headings, lists, emphasis).
- Output the article body only, with no preamble or explanation.

[Manuscript]
<Step1 の全文>
```

结果写入 `$DOC_DIR/英文.md`。

---

## 6. Step4 — 生成 3 语言文章信息 → `文章信息.json`

以 **Step1 的中文原稿全文**（参考文献：Step2/Step3 的译文）为输入，套用下面的要求：

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
<Step1 の全文>
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
