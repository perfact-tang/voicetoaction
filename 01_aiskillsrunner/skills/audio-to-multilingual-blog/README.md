# audio-to-multilingual-blog

一个 [DSH](https://github.com/deepseek-ai/deepseek-harness) skill：把 `aiskillsrunner` 传入的
**语音转文字（STT / Fast Whisper）原始文本**，处理成日 / 中 / 英三语博客文章并自动上传到
VibeCoding Japan 博客。

- 处理步骤来自仓库的 `01_音声から原稿へ変換/step1.md` ~ `step4.md`（原样保存在 `references/`）。
- 生成的文件正好是 [`vibecodingjapan-upblog`](https://github.com/) 所需的 4 个输入。
- 临时目录按 **DocID** 组织：`~/Documents/tmpaiskill/<DocID>/`。
- 最后**只调用 1 次** `vibecodingjapan-upblog` 完成上传。

## 目录结构

```
audio-to-multilingual-blog/
├── SKILL.md                       # skill 指令（含 Step1〜Step4 的完整提示词）
├── references/                    # 原始 step 文件，作为提示词的 source of truth
│   ├── step1.md                   # STT → 简体中文精炼原稿
│   ├── step2.md                   # → 母语级日文
│   ├── step3.md                   # → 母语级英文
│   └── step4.md                   # → 3 语言 blogtitle / blogsummary / blogseo
├── examples/
│   └── stt-sample.txt             # 带口语杂讯的示例 STT 文本
└── scripts/
    ├── upload-blog.sh             # 只调用 1 次 vibecodingjapan-upblog
    └── run-with-aiskillsrunner.sh # 便捷入口：带正确沙箱权限调用 aiskillsrunner
```

用示例文本跑通整条链路（建议先空跑，不写 Firebase）：

```bash
AISKILLS_BLOG_DRY_RUN=1 \
  scripts/run-with-aiskillsrunner.sh --reffilepath examples/stt-sample.txt
```

## 生成的文件

```
~/Documents/tmpaiskill/<DocID>/
├── 中文.md        # Step1 输出（简体中文）
├── 日文.md        # Step2 输出（日文）
├── 英文.md        # Step3 输出（英文）
└── 文章信息.json   # Step4 输出（translations.ja / .zh / .en）
```

`<DocID>` 默认是执行时的 Unix 毫秒时间戳（与 `vibecodingjapan-upblog` 新建文章的 docid 规则一致），
也会被作为 `--docid` 传给上传工具，因此**文件夹名 = 文章 docid = `/blog/<DocID>`**。
如果输入文本里出现 `DocID: 1758…` / `docid=1758…` / `--docid 1758…`，则优先使用其中的值。

## 安装

skill 需要放在 `dsh` 与 `aiskillsrunner` 都能扫描到的目录。`aiskillsrunner` 的默认安装目录是
`~/.agents/skills`，也是 `dsh-skill-filesystem` 的 `user-agents` 根：

```bash
# 从仓库安装（目录型 skill：<name>/SKILL.md）
mkdir -p ~/.agents/skills
cp -r skills/audio-to-multilingual-blog ~/.agents/skills/

# 确认可被发现
ls ~/.agents/skills/audio-to-multilingual-blog/SKILL.md
```

也可以打包成 zip 后用 `aiskillsrunner --installurl` 分发：

```bash
cd skills && zip -r audio-to-multilingual-blog.zip audio-to-multilingual-blog
```

## 前置条件

1. `vibecodingjapan-upblog` 已可用（`vibecodingjapan-upblog --version`）。
   ```bash
   cd <vibecodingjapan>/09_cli_upblog_tool && npm install
   cd <vibecodingjapan> && npm i -g ./09_cli_upblog_tool
   ```
2. Firebase 服务账号密钥文件。默认路径
   `~/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json`，
   可用 `AISKILLS_BLOG_KEY` 覆盖。
3. 作者 uid。默认 `KYTF9y43qgc39vKn0sI3qWpsjRE2`，可用 `AISKILLS_BLOG_UID` 覆盖。
4. **放开沙箱**：skill 要写 `~/Documents/tmpaiskill`（工作区之外）并读取 `~/Downloads` 的密钥，
   而 `dsh` 默认是 `workspace-write`，因此调用时必须给 headless 放开权限。

## 运行

推荐用便捷入口（自动设置 `DSH_PERMISSION_MODE=danger-full-access`）：

```bash
# STT 文本直接作为 prompt
skills/audio-to-multilingual-blog/scripts/run-with-aiskillsrunner.sh \
  --prompt "（这里放 Fast Whisper 的原始文本）"

# STT 文本放在文件里
skills/audio-to-multilingual-blog/scripts/run-with-aiskillsrunner.sh \
  --reffilepath ./stt.txt

# 指定 DocID
skills/audio-to-multilingual-blog/scripts/run-with-aiskillsrunner.sh \
  --prompt "DocID: 1758000000000" --reffilepath ./stt.txt
```

等价的手写命令：

```bash
DSH_PERMISSION_MODE=danger-full-access \
  aiskillsrunner --skill audio-to-multilingual-blog --prompt "（STT 原始文本）"
```

## 上传脚本

skill 的最后一步会执行：

```bash
bash ~/.agents/skills/audio-to-multilingual-blog/scripts/upload-blog.sh <DocID>
```

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `AISKILLS_FIREBASE_ACCOUNT` | 未设置 | 服务账号 JSON 路径；由 `aiskillsrunner --firebasekey` 设置，**优先级最高** |
| `AISKILLS_USERID` | 未设置 | 作者 uid；由 `aiskillsrunner --userid` 设置，**优先级最高** |
| `AISKILLS_BLOG_TMPDIR` | `~/Documents/tmpaiskill` | 临时根目录 |
| `AISKILLS_BLOG_KEY` | `~/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json` | 服务账号 JSON（全局默认；直接运行脚本时生效） |
| `AISKILLS_BLOG_UID` | `KYTF9y43qgc39vKn0sI3qWpsjRE2` | 作者 uid（全局默认） |
| `AISKILLS_BLOG_CLI` | 从 PATH 查找 | 上传 CLI 路径 |
| `AISKILLS_BLOG_DRY_RUN` | 未设置 | 设为 `1` 时只做 `--dry-run`，不写 Firebase |

取值优先级：`aiskillsrunner --firebasekey/--userid` > `AISKILLS_BLOG_KEY`/`AISKILLS_BLOG_UID` > 内置默认值。

> ⚠️ **不要用 `*_KEY` 这类名字传密钥路径**。`dsh` 的 subprocess 层会把名字匹配
> `/KEY|PASSWORD|SECRET|TOKEN/i` 的环境变量从子进程环境里剔除，所以经 `dsh` 传到 skill 的
> 变量名是 `AISKILLS_FIREBASE_ACCOUNT`。`AISKILLS_BLOG_KEY` 只在**直接**运行
> `upload-blog.sh`（不经过 dsh）时有效。

手动单跑（不经过 skill）：

```bash
# 空跑校验，不写任何数据
bash scripts/upload-blog.sh 1758000000000 --dry-run

# 真实上传
bash scripts/upload-blog.sh 1758000000000
```

> 上传成功后会打印 `/blog/<DocID>` 与 `/editblog/<DocID>`。
> 继续编辑同一篇文章时，再次运行并沿用同一个 DocID 即可（脚本会自动传 `--docid`）。

## 注意事项

- **不要重复上传**：`upload-blog.sh` 每次只调用一次 CLI；失败时请先排查原因再重跑，
  避免产生重复文章。
- **封面图**：`vibecodingjapan-upblog` 不会生成封面图。JSON 里 `coverImageMode` 固定为 `mindmap`、
  `mindmapurl` 为空，因此博客卡片会显示渐变占位图。需要封面时，可在 `/editblog/<DocID>`
  用 MindMap 重新生成并保存。
- **`blogseo` 必须用半角逗号 `,`**，不能用全角「，」。
- Step1〜Step4 的原始提示词保存在 `references/`，`SKILL.md` 里是内联副本；
  修改措辞时请两边同步。
