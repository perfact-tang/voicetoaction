# aiskillsrunner

一个 Ubuntu 下的命令行工具（CLI），用于调用本机已安装的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）来执行指定的 skill，并在执行结束后输出处理后的报告结果。

## 前置条件

1. 已安装 DeepSeek Harness 的 `dsh` 命令，且 `dsh` 在 `PATH` 中（或用环境变量 `AISKILLSRUNNER_DSH` 指定路径）。
2. Node.js >= 18（`dsh` 本身依赖 Node 运行时）。
3. 已配置好模型凭据（`~/.dsh/.credentials.yaml` 或环境变量），保证 `dsh --profile headless` 可正常运行。

> 说明：首次使用 headless 模式时，`dsh` 会在 `$DSH_HOME/profiles/headless` 下自动初始化 profile。

## 安装

方式一：直接运行脚本

```bash
chmod +x aiskillsrunner.js
./aiskillsrunner.js --skill firebase-basics --prompt "初始化一个项目"
```

方式二：安装为全局命令

```bash
npm install -g .
aiskillsrunner --skill firebase-basics --prompt "初始化一个项目"
```

方式三：软链接

```bash
sudo ln -s "$(pwd)/aiskillsrunner.js" /usr/local/bin/aiskillsrunner
```

## 参数

| 参数 | 必填 | 说明 |
|---|---|---|
| `--skill <名称>` | 是 | 已安装的 skill 名称 |
| `--prompt <提示词>` | 否 | 传给 skill 的提示词 |
| `--reffilepath <路径>` | 否 | 接在提示词之后的参考文件的本地地址（内容会被读取并附加到提示词之后） |
| `--installurl <URL>` | 否 | 当没有 skills 时，用于安装 skills 的 zip 文件 URL |
| `--force-install` | 否 | 配合 `--installurl`：无论本地是否已有同名 skill 都重新安装（先删除安装目录里的旧文件，用于版本升级） |
| `--firebasekey <路径>` | 否 | Firebase 服务账号密钥 JSON（别名 `--key`）。校验存在后以环境变量 `AISKILLS_FIREBASE_ACCOUNT` 传给 skill |
| `--userid <ID>` | 否 | 用户 ID，以环境变量 `AISKILLS_USERID` 传给 skill |
| `--env <KEY=VALUE>` | 否 | 额外传给 skill 的环境变量，可重复 |
| `-h, --help` | 否 | 显示帮助 |
| `-V, --version` | 否 | 显示版本 |

### 把运行参数透传给 skill

`--firebasekey` / `--userid` / `--env` 不会拼进提示词去猜，而是**直接注入子进程环境变量**，
skill 里的脚本按约定读取即可（父进程环境变量会被保留，同名时命令行参数优先）：

| 命令行参数 | 注入的环境变量 |
|---|---|
| `--firebasekey ~/Downloads/xxx.json` | `AISKILLS_FIREBASE_ACCOUNT=/abs/path/xxx.json` |
| `--userid AbCdEf123` | `AISKILLS_USERID=AbCdEf123` |
| `--env FOO=bar` | `FOO=bar` |

密钥路径会做存在性校验（支持 `~`，即使加了引号也会展开），不存在则直接报错退出（退出码 2）。

> ⚠️ **变量名不要用 `*_KEY` / `*_SECRET` / `*_TOKEN` / `*_PASSWORD`**。
> `dsh` 的 subprocess 层（`@deepseek-ai/dsh-subprocess` 的 `SENSITIVE_ENV_PATTERN = /KEY|PASSWORD|SECRET|TOKEN/i`）
> 会把这类名字的环境变量从子进程环境里剔除，参数到不了 skill 的 bash。
> 因此密钥路径用的是 `AISKILLS_FIREBASE_ACCOUNT` 而不是 `AISKILLS_FIREBASE_KEY`。

## 用法示例

```bash
# 执行已安装的 skill
aiskillsrunner --skill firebase-basics --prompt "帮我初始化一个 Firebase 项目"

# 带参考文件（参考文件内容会附加在提示词之后）
aiskillsrunner --skill my-skill --prompt "分析这份报告" --reffilepath ./report.txt

# 没有 skills 时先安装再执行；已安装则跳过安装直接执行
aiskillsrunner --skill my-skill --prompt "开始处理" --installurl https://example.com/skills.zip

# 覆盖升级：删除安装目录里的旧版本后重新安装（用于版本更新）
aiskillsrunner --skill my-skill --installurl https://example.com/skills-v2.zip --force-install

# 只装到后端私有目录，不影响 ~/.agents/skills
AISKILLSRUNNER_SKILLS_DIR=/path/to/backend/skills \
  aiskillsrunner --skill my-skill --installurl https://example.com/skills.zip --force-install

# 透传 Firebase 密钥与作者 uid
aiskillsrunner --skill audio-to-multilingual-blog --reffilepath ./stt.txt \
  --firebasekey ~/Downloads/vibecodingjapan-firebase-adminsdk-xxxx.json \
  --userid KYTF9y43qgc39vKn0sI3qWpsjRE2
```

## 工作流程

1. 解析命令行参数。
2. 若传入了 `--installurl`：
   - 带 `--force-install` 时，先删除安装目录里的同名 skill，再下载 zip、解压并安装；
   - 否则仅在目标 skill 尚未安装时才安装。
3. 若传入了 `--reffilepath`，读取参考文件内容并附加到提示词之后。
4. 组合任务文本（以 `/技能名` 令牌确定性加载 skill），调用 `dsh --profile headless "<任务>"` 执行。
5. `dsh` 执行结束后，将处理后的最终结果（报告）打印到标准输出；退出码与 `dsh` 一致（成功为 0）。

> `--force-install` 只影响 `AISKILLSRUNNER_SKILLS_DIR` 指向的安装目录，不会动
> 全局 `~/.agents/skills` 里的同名 skill。VoiceToAction AI 后端就是用它来做
> 「CMS 版本变了 → 删掉本地旧版本 → 重新下载安装」的。

## Skills 安装说明

- skills 的安装目录默认为 `~/.agents/skills`，可通过环境变量 `AISKILLSRUNNER_SKILLS_DIR` 覆盖。
- 该目录是 `dsh` 自动发现的 skill 根目录之一（`dsh-skill-filesystem` 会扫描 `~/.dsh/skills`、`~/.agents/skills` 等位置）。
- zip 支持两种 DSH skill 格式：
  - 目录型：`<名称>/SKILL.md`
  - 扁平型：`<名称>.md`
- 工具会自动在解压后的目录树中查找上述格式并安装，可处理「zip 内直接是 skills」「zip 内套一层目录」「zip 内含 skills/ 子目录」等常见布局。

## 环境变量

| 变量 | 说明 |
|---|---|
| `AISKILLSRUNNER_DSH` | 指定 `dsh` 可执行文件路径（默认从 `PATH` 查找） |
| `AISKILLSRUNNER_SKILLS_DIR` | 指定 skills 安装目录（默认 `~/.agents/skills`） |

## 内置 Skill：audio-to-multilingual-blog

把语音转文字（STT / Fast Whisper）原始文本，按 `01_音声から原稿へ変換/step1.md` ~ `step4.md`
处理成日 / 中 / 英三语博客，写入 `~/Documents/tmpaiskill/<DocID>/`，最后调用**一次**
`vibecodingjapan-upblog` 完成上传。

源码在 [`skills/audio-to-multilingual-blog/`](skills/audio-to-multilingual-blog/)，详见该目录的 README。

```bash
# 安装到 ~/.agents/skills（aiskillsrunner 的默认安装目录，也是 dsh 的 user-agents 根）
mkdir -p ~/.agents/skills
cp -r skills/audio-to-multilingual-blog ~/.agents/skills/

# 运行：skill 要写工作区之外的 ~/Documents/tmpaiskill 并读取 ~/Downloads 的密钥，
# 所以必须给 headless 放开沙箱
DSH_PERMISSION_MODE=danger-full-access \
  aiskillsrunner --skill audio-to-multilingual-blog --reffilepath ./stt.txt
```

也可以用便捷脚本自动设置权限：

```bash
skills/audio-to-multilingual-blog/scripts/run-with-aiskillsrunner.sh --reffilepath ./stt.txt
```

生成的文件：

```
~/Documents/tmpaiskill/<DocID>/
├── 中文.md        # Step1：STT → 精炼简体中文原稿
├── 日文.md        # Step2：Step1 内容 → 母语级日文
├── 英文.md        # Step3：Step1 内容 → 母语级英文
└── 文章信息.json   # Step4：3 语言 blogtitle / blogsummary / blogseo / blogmindmap
```

`<DocID>` 默认是 Unix 毫秒时间戳，并作为 `--docid` 传给上传工具，
因此**目录名 = 文章 docid = `/blog/<DocID>`**。上传相关的环境变量
（`AISKILLS_BLOG_KEY` / `AISKILLS_BLOG_UID` / `AISKILLS_BLOG_TMPDIR` / `AISKILLS_BLOG_DRY_RUN`）
见 skill 的 README。

## License

MIT
