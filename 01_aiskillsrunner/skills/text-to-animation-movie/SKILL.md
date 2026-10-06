---
name: text-to-animation-movie
description: >-
  把外部传入的文本（故事、文章、解说文案、产品说明、会议记录）导演成一支手绘白板风格的
  讲解动画：拆分镜、写台词、生成配音与 LRC 字幕、用 Remotion 渲染成竖屏 MP4，
  然后把视频上传到 Firebase Storage 的 movie/ 目录并取得 public url，
  最后把一条 note 写入 /users/[userid]/notes/[noteid] 并发送 FCM 通知。
  默认 9:16 竖屏、手绘风格；也可按指示文改成其它画幅。
metadata:
  category: Content
  version: "1.0.0"
---

# 文本 → 手绘讲解动画 → 上传

## 0. 这个 skill 做什么

| | |
|---|---|
| 输入 | 一段外部文本（--prompt 或 --reffilepath 提供） |
| 输出 | 一个 DocID 目录：渲染好的 MP4 + LRC 字幕 + 说明文，并已**上传 1 次**到 Firebase |

```
~/Documents/tmpaiskill/<DocID>/
├── 输入文本.md      # 收到的原文，原样留档
├── 分镜.md          # 论证链拆解 + 分镜表（人看的）
├── 台词.lrc         # 带时间戳的台词，与配音逐行对齐
├── 动画说明.json     # title / body / tags，body 里含 #tag
├── 动画说明.md       # 同上，人读的版本
├── movie.mp4        # 成品动画
└── engine/          # 本次使用的渲染工程（含 source，可复现）
```

严格按 Step1（读文本、定结构）→ Step2（写台词）→ Step3（画分镜）→ Step4（生成时间轴与配音）
→ Step5（渲染）→ Step6（写说明文）→ Step7（上传）的顺序执行。

**原文是素材，不是待照搬的段落。** 先判断作者最值得传达的论点、读者需要理解的问题，
再决定分镜结构；不要把文章按小标题逐段翻译成画面。

---

## 1. 前置条件（先检查，缺一不可）

```bash
# 1. 运行环境
node -v                      # 需要 Node 18+（Remotion 4 要求）
python3 -V
ffmpeg -version | head -1
ffprobe -version | head -1   # 时间轴测量依赖它

# 2. 中文字体（没有 CJK 字体会渲染成方框）
fc-list :lang=zh 2>/dev/null | head -3 || echo "缺 fontconfig"
# 缺字体时：sudo apt-get install -y fonts-noto-cjk

# 3. 语音合成：优先 MeloTTS（本机神经网络 TTS，中文自然），没有才回退 espeak
"${MELOTTS_PYTHON:-$HOME/melotts-venv/bin/python}" -c "import melo; print('MeloTTS OK')" 2>/dev/null \
  || echo "无 MeloTTS -> 会回退 espeak-ng（中文是机械音）"
command -v espeak-ng || command -v espeak || command -v say || echo "无 TTS -> 纯字幕模式"
# 装 MeloTTS（无需 sudo，约 1.5GB 含模型）：bash scripts/install-melotts.sh
# 需要旁白但连 espeak 也没有时：sudo apt-get install -y espeak-ng

# 4. 工作根目录
mkdir -p "$HOME/Documents/tmpaiskill"

# 5. Firebase 密钥
ls -l "${AISKILLS_FIREBASE_ACCOUNT:-${AISKILLS_MOVIE_KEY:-$HOME/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json}}"

# 6. 磁盘（node_modules 约 800MB + Headless Chrome 约 100MB）
df -h "$HOME" | tail -1
```

**沙箱权限**：本 skill 需要写工作区之外的 `~/Documents/tmpaiskill`、在 skill 目录里
`npm install`、并读取 `~/Downloads` 下的 Firebase 密钥。因此启动方必须放开沙箱：

```bash
DSH_PERMISSION_MODE=danger-full-access \
  aiskillsrunner --skill text-to-animation-movie --prompt "<文本>"
```

如果写文件时报 `sandbox: file access denied` / `Operation not permitted`，说明没有放开沙箱；
请停止并在最终报告里写明这条命令，**不要改用其它路径绕过**。

如果 `node`、`ffmpeg`、`ffprobe` 任一缺失 → 立即停止并报告，不要继续。

### 配音后端（MeloTTS 优先，自动判定）

`build_audio.py` **自己会判定本机有没有 MeloTTS**，不需要你在 Step4 里做判断：

```bash
# 想自己确认一下（可选）
python3 - <<'EOF'
import importlib.util
spec = importlib.util.spec_from_file_location("ba", "scripts/build_audio.py")
ba = importlib.util.module_from_spec(spec); spec.loader.exec_module(ba)
print("MeloTTS 解释器:", ba.find_melo_python() or "（无）")
print("实际会用的后端:", (ba.detect_tts("auto", "auto") or type("x", (), {"name": "none"})()).name)
EOF
```

判定顺序（`find_melo_python()`）：

1. 环境变量 `MELOTTS_PYTHON`（或 `AISKILLS_MELOTTS_PYTHON`）指定的解释器；
2. 跑 `build_audio.py` 的那个 `python3`；
3. `PATH` 上的 `python3` / `python`；
4. 约定安装位置：`~/melotts-venv`、`~/.local/share/melotts/venv`、`~/.venvs/melotts`、`~/melotts/.venv`。

**判定标准是「这个解释器能不能 `import melo`」**，不是「目录存不存在」，所以路径写错
不会有副作用，最多退回到下一个候选。

| 情况 | 行为 |
|---|---|
| 找到 MeloTTS | `TTS = melo (ZH, speaker 0, speed 1)` —— 一次性加载模型，把全部台词合成完再拼轨 |
| 没找到 | 回退 `espeak-ng (cmn)`；macOS 上回退 `say` |
| MeloTTS 装上了但合成报错 | 打印 warning，退回 `espeak-ng`，视频照常产出（报告里要写明） |
| 什么 TTS 都没有 | 纯字幕版，**必须在报告里写明** |

> ⚠️ **报告里必须写清楚实际用了哪个后端。** 如果 cue sheet 里是 `TTS = espeak-ng (cmn)`
> 而不是 `melo (...)`，说明 MeloTTS 没被找到或跑挂了 —— 先跑
> `bash scripts/install-melotts.sh --check` 看是哪一种。

**worker 自带的两道保险**（`melo_tts.py`，不用你操心）：

1. **静音重试**：MeloTTS 偶发会输出「长度正确、但采样全是 0」的波形（实测 31 行里中招 6 行，
   峰值 −91dB）。每条合成完都会检查峰值电平和有效样本占比，不合格就**换随机种子重试**
   （默认 4 次），全部失败才报错。
2. **不在逗号处断**：MeloTTS 会在 `，` 处切句、段与段之间只插 **0.05 秒**停顿（正常逗号停顿
   是 0.2–0.3 秒），听起来就是「词没说完就下一句」。worker 默认先把行内 `，`/`,`/`；`/`;`
   去掉，让模型自己断句；万一还有别的切点，就用自己的 0.25 秒停顿拼接。

**常用调参**（环境变量，`build_audio.py` 会读）：

| 变量 | 默认 | 作用 |
|---|---|---|
| `MELOTTS_SPEAKER` | `0` | 音色 id（**这个中文模型有 256 个**）；语气不对先换这个 |
| `MELOTTS_SPEED` | `1.0` | 语速（夹在 0.5–2.0） |
| `MELOTTS_SDP_RATIO` | `0.2` | 韵律随机性；**调低更平稳**，`0` 最稳最「念稿」 |
| `MELOTTS_NOISE_SCALE` | `0.6` | 语调起伏；调低更平 |
| `MELOTTS_RETRIES` | `4` | 静音重试次数 |
| `MELOTTS_GAP_S` | `0.25` | 残留切点处的停顿秒数 |
| `MELOTTS_STRIP_PUNCT` | `，,；;` | 合成前去掉的行内标点；设成空值 = 不去 |
| `MELOTTS_PYTHON` | 自动探测 | 指定带 MeloTTS 的解释器 |

- 安装/修复：`bash scripts/install-melotts.sh`（无需 sudo；优先用 **uv 管理的 Python**，
  这样系统 python 升级（如 Ubuntu 26.04 删掉 3.12）不会把 venv 打坏）。
- 体检：`bash scripts/install-melotts.sh --check`。
- 时间轴是从**实测音轨**反推的，改语速只会让视频变长/变短、不会让字幕错位；但总时长超过
  240s、单场超过 18s 会触发节奏护栏（脚本以退出码 1 结束），所以调完要看 Step4 的输出。
- 想强制某个后端：`--tts melo` / `--tts espeak-ng` / `--tts say`。

---

## 1.5 执行纪律（headless 单回合语义，**必须遵守**）

本 skill 由 `aiskillsrunner` 通过 `dsh --profile headless <task>` 启动，这是**单回合**执行：

- **你一旦结束回合，进程立即退出。** 此时仍在运行的 `subagent` 会被一并杀掉，
  它们还没写完的文件不会落盘 —— 但退出码依然是 `0`，调用方会误以为成功。
- 因此：**在 Step7 上传成功、Step8 输出最终报告之前，绝对不要结束回合。**
  也不要把「稍等 / 正在等待 / Progress so far」这类中间状态当作回合结尾。
- 渲染很慢（2 分钟动画在 4 核机器上约 8–15 分钟）：用 `run_in_background: true` 起渲染是允许的，
  但**必须在同一回合内**用 `job_output(wait: true)` 取回结果再继续，不要以「等通知」收尾。
- 需要并行时只用**前台阻塞**的子代理（`run_in_background: false`），取回结果后再往下走。

---

## 2. 解析输入，确定 DocID 与工作目录

你看到的任务文本形如：

```
/<skill 名称>

请使用已安装的 skill "..." 完成以下任务，并在完成后输出处理结果报告。

任务提示词：
<--prompt 的内容>

参考文件（<绝对路径>）内容：
<--reffilepath 文件的内容>
```

解析规则（**严格照做**）：

1. **正文**
   - 存在「参考文件（…）内容：」段落 → 该段落之后的**全部文本**为正文；
     「任务提示词：」段落只用来提取 DocID 与附加指示（画幅、时长、风格、语言等）。
   - 否则 → 「任务提示词：」之后的全部文本为正文。
2. **DocID（可选覆盖）**：出现 `DocID: 1758…` / `DocID=1758…` / `docid: 1758…` / `--docid 1758…`
   时用其中的数字。
3. **没有 DocID 时自动生成**：
   ```bash
   DOCID=$(date +%s%3N)
   ```
4. **正文为空 / 只有空白** → 停止，输出「没有收到文本，未生成任何文件」，**禁止编造内容**。

```bash
DOC_DIR="$HOME/Documents/tmpaiskill/$DOCID"
mkdir -p "$DOC_DIR"
```

把收到的正文原样写入 `$DOC_DIR/输入文本.md`（留档，便于复现）。

**附加指示的默认值**（指示文没写就用这些）：

| 项目 | 默认 | 可被指示文覆盖为 |
|---|---|---|
| 画幅 | **9:16 竖屏 1080×1920** | 16:9 → `1920×1080`；1:1 → `1080×1080`；4:5 → `1080×1350` |
| 风格 | **手绘白板（仅此一种引擎）** | 不支持其它风格，按白板执行并在报告里说明 |
| 时长 | 约 2–3 分钟（台词 480–700 字） | 「控制在 N 分钟」→ 按 3.55 字/秒反推字数 |
| 语言 | 中文 | 指示文指定的语言 |
| 帧率 | 30fps | 一般无需修改 |

---

## 3. Step1 — 读懂文本，定下结构 → `分镜.md` 的前半

先阅读 [references/01-script.md](references/01-script.md)，按其中的方法：提炼论证链、
决定分镜数量与顺序、控制台词长度。把以下内容写进 `$DOC_DIR/分镜.md`：

- 原文在回答哪几个问题（论证链拆解）
- 每个场景对应的原文位置
- 视觉与叙事的关键取舍（为什么这样画）

**不要**在这一步写台词文件，先把结构定下来。

---

## 4. Step2 — 写台词（场景 + 逐行台词）

按 [references/01-script.md](references/01-script.md) 的规范写台词，产出
`$DOC_DIR/engine/scripts/script.json`。格式：

```json
{
  "id": "Explainer",
  "title": "动画标题",
  "fps": 30,
  "width": 1080,
  "height": 1920,
  "audioTrack": "narration.wav",
  "outFile": "out/movie.mp4",
  "style": "whiteboard",
  "voice": "auto",
  "rate": 180,
  "scenes": [
    { "id": "S01", "visual": "hook", "lines": [["L01", "第一句台词。"]] }
  ]
}
```

硬性要求：

- 每个 `line` 是 `["L##", "台词"]`；**L 编号全局唯一且连续**。
- 每行台词 **12–20 字**；一行太长会让字幕换行难看，太短会让节奏碎。
- `visual` 是场景插图的名字，必须与 `src/scenes.tsx` 里 `VISUALS` 的键一致。
- 台词总量按 **3.55 字/秒** 估算；`--dry-run` 会给出实际预估时长。

写完先用节奏检查（不合成音频，秒级返回）：

```bash
cd "$DOC_DIR/engine"
python3 scripts/build_audio.py --dry-run
```

有 `!! over budget` 或某场 `<- too long` 就回去改台词。

---

## 5. Step3 — 画分镜 → `src/scenes.tsx`

必须先阅读 [references/02-scenes.md](references/02-scenes.md)（白板引擎的完整 API 与常见画法）。

在 `$DOC_DIR/engine/src/scenes.tsx` 的 `VISUALS` 里为 `script.json` 用到的每个 `visual`
实现一个插图，并在 `HandKey[]` 里给出笔尖轨迹（笔尖就是 `x, y`，应指向该帧正在出现的那一笔）。

**这一步必须实际看图，不能只靠想象。** 渲染静帧检查：

```bash
cd "$DOC_DIR/engine"
npx remotion still src/index.ts Explainer "$DOC_DIR/check/S01.png" --frame=<帧号>
```

逐场检查这四件事：

1. 文字有没有超出画幅边缘（1080 宽，居中文字左右各留 80px 安全边距）；
2. 文字有没有超出它所在的卡片/方框（CJK 全角字宽 ≈ 字号 × 1.0）；
3. 手有没有盖住正在讲的文字或关键标记；
4. 有没有渲染出 `Label` 文字（漏了通常是把它放在了 SVG 元素内部之外的坐标）。

发现问题改 `scenes.tsx` 再渲染，直到 20 多场都干净。

---

## 6. Step4 — 生成时间轴与配音

```bash
cd "$DOC_DIR/engine"
python3 scripts/build_audio.py
```

它会：逐行配音（有 TTS 时）→ 拼成一条连续音轨 → **在成品音轨上实测每行入点** →
吸附到帧网格 → 写出 `src/data/timing.ts`、`src/data/videoConfig.ts`、
`out/Explainer.lrc`、`out/Explainer-cue-sheet.md`。

关键点：入点只吸附一次、不累加，所以字幕不会越往后越偏；实测与网格的最大偏差应 ≤ 20ms。
脚本会把这一行打出来（`max |网格入点 - 实测入点|`），若超过 40ms 请停下来检查。

没有 TTS 时脚本会打印 `no TTS available -> generating silent clips`，
此时视频是**纯字幕**版（无旁白），LRC 仍然准确。要在报告里写明这一点。

> 想强制纯字幕版：加 `--no-audio`。想指定后端：`--tts melo` / `--tts espeak-ng`。
> 换后端或改 `MELOTTS_*` 参数后，**时间轴和字幕都会变**，必须重跑 Step4 再进 Step5 渲染。

---

## 7. Step5 — 渲染

```bash
cd "$DOC_DIR/engine"
# 首次运行需要下载 Headless Chrome（约 100MB）
npx remotion browser ensure
npx remotion render src/index.ts Explainer out/movie.mp4 \
    --codec=h264 --crf=17 --pixel-format=yuv420p --concurrency=8
```

- `--concurrency` **直接决定总时长**：每帧都在算 SVG 滤镜，是纯 CPU 开销。
  12 核机器实测 **8 比 4 快约 40%**（5038 帧 @8 = 66 分钟；3652 帧 @4 = 64 分钟）。
  取 `min(8, 核数 - 2)`；内存 8GB 以下减半。渲染期间别同时跑别的重活。
- 渲染耗时长（约 1 小时），允许后台运行，但**必须在同一回合内取回结果**（见 1.5）。
- 完成后把成品复制到工作目录：
  ```bash
  cp out/movie.mp4 "$DOC_DIR/movie.mp4"
  ```
- 校验产物：
  ```bash
  ffprobe -v error -show_entries format=duration \
    -show_entries stream=codec_name,width,height,nb_frames \
    -of default=noprint_wrappers=1 "$DOC_DIR/movie.mp4"
  ```
  宽高、帧数必须与 `script.json` 一致；时长应与 `--dry-run` 的预估相差不大。

---

## 8. Step6 — 写动画说明文 → `动画说明.json`

按 [references/03-publish.md](references/03-publish.md) 的要求写，产出：

```json
{
  "title": "动画说明 Title",
  "body": "适合 SNS 投放的动画说明文，1000 字以内，含有 #tag。",
  "color": "paper",
  "hidden": false,
  "tags": ["movie"]
}
```

硬性要求：

- `body` **不超过 1000 字**（中文字符按 1 字计），必须是适合 SNS 投放的介绍文，
  **至少含 3 个 `#tag`**（半角 `#`，例如 `#商业模式 #星巴克 #第三空间`）。
- `title` 简洁准确，不要夸张宣传。
- `color` 固定 `"paper"`，`hidden` 固定 `false`，`tags` 固定 `["movie"]`。
- **public url 由上传脚本自动追加到 body 末尾**，不要自己编造 URL，也不要在 body 里预留占位符。
- 另外写一份 `动画说明.md`（人读版：title + body + tags），内容与 JSON 一致。

---

## 9. Step7 — 上传（**只调用 1 次** `upload-movie.sh`）

```bash
SKILL_DIR="$HOME/.agents/skills/text-to-animation-movie"
if [ ! -f "$SKILL_DIR/scripts/upload-movie.sh" ]; then
  SKILL_DIR=$(find "$HOME/.agents/skills" "$HOME/.dsh/skills" "$PWD/.agents/skills" "$PWD/.dsh/skills" \
    -maxdepth 2 -type d -name text-to-animation-movie 2>/dev/null | head -n 1)
fi
bash "$SKILL_DIR/scripts/upload-movie.sh" "$DOCID" "$DOC_DIR/movie.mp4" --note "$DOC_DIR/动画说明.json"
```

脚本内部**只执行一次**上传，依次完成：

1. 上传视频到 Firebase Storage 的 **`movie/<noteid>.mp4`**；
2. 取回带 token 的 **public url**；
3. 写入 Firestore 文档 **`/users/<userid>/notes/<noteid>`**：

   | 字段 | 值 |
   |---|---|
   | `body` | 说明文 + `\n\n` + public url |
   | `color` | `"paper"` |
   | `hidden` | `false` |
   | `id` | `<noteid>` |
   | `tags` | `["movie"]` |
   | `title` | 说明文标题 |
   | `updatedAt` | Firestore `serverTimestamp()` |

4. 发送 FCM 通知（有 topic 或 token 时）。

`noteid` 默认等于 `DocID`（Unix 毫秒）。密钥 / uid 的取值优先级（由脚本内部决定，skill 不要写死）：

1. `aiskillsrunner --firebasekey <路径>` / `--userid <ID>`
   → 环境变量 `AISKILLS_FIREBASE_ACCOUNT` / `AISKILLS_USERID`；
2. 全局环境变量 `AISKILLS_MOVIE_KEY` / `AISKILLS_MOVIE_UID`（直接运行脚本时用）；
3. 脚本内置默认值（`~/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json`、
   uid `KYTF9y43qgc39vKn0sI3qWpsjRE2`）。

> ⚠️ **不要用 `*_KEY` 命名环境变量传密钥路径**：dsh 的 subprocess 层会把名字匹配
> `/KEY|PASSWORD|SECRET|TOKEN/i` 的变量从子进程环境里剔除，所以经 dsh 传进来的变量名是
> `AISKILLS_FIREBASE_ACCOUNT`。`AISKILLS_MOVIE_KEY` 只在**直接**运行脚本时有效。

- 想先空跑验证（不写 Firebase）加 `--dry-run`，或设 `AISKILLS_MOVIE_DRY_RUN=1`。
- 想发 FCM：设 `AISKILLS_FCM_TOPIC=<topic>`，或传 `--notify-topic <topic>` / `--notify-token <token>`。
  两者都没有时脚本跳过通知并在输出里说明（**不算失败**）。
- **不要因为失败就重复调用上传**。失败时直接停止并报告脚本输出；重复调用会产生重复的
  Storage 对象与 note 文档。

---

## 10. Step8 — 最终报告（必须输出）

用简洁的 Markdown 报告，至少包含：

1. **DocID** 与目录 `~/Documents/tmpaiskill/<DocID>/`；
2. 关键产物是否存在、大小（`movie.mp4` 的 MB 数、时长、分辨率、帧数）；
3. `台词.lrc` 的 cue 条数与末条时间戳；
4. 配音情况（用了哪个 TTS，或纯字幕模式）；
5. `动画说明.json` 的 title、body 字数、tags；
6. **public url** 与 Firestore 路径 `/users/<uid>/notes/<noteid>`；
7. FCM 是否发送（发送了用什么 topic/token，跳过要说原因）；
8. 若中途失败：停在哪一步、具体错误、需要人做什么。

不要在报告里重复粘贴整段说明文。

### 报告最后一行：机器可读标记（**必需**）

```text
SKILL_RESULT: OK docid=<DOCID> note=<noteid> url=<public url>
```

中途失败（包括上传失败）时，最后一行改为：

```text
SKILL_RESULT: FAILED step=<Step1..Step7|upload> reason=<一句话原因>
```

例：

```text
SKILL_RESULT: OK docid=1759700000000 note=1759700000000 url=https://firebasestorage.googleapis.com/v0/b/xxx/o/movie%2F1759700000000.mp4?alt=media&token=...
```

---

## 11. 目录结构参考

```
text-to-animation-movie/
├── SKILL.md
├── README.md
├── references/
│   ├── 01-script.md      # 论证链拆解 + 台词写法
│   ├── 02-scenes.md      # 白板引擎 API 与分镜画法
│   └── 03-publish.md     # 说明文写法 + 上传/通知
├── examples/
│   ├── story-sample.md   # 示例输入文本
│   └── script-sample.json# 示例 script.json
├── assets/engine/        # 每次复制到工作目录的渲染工程
│   ├── package.json
│   ├── remotion.config.ts
│   ├── tsconfig.json
│   ├── scripts/build_audio.py
│   └── src/{Root.tsx,Video.tsx,scenes.tsx,sketchDefs.tsx,data,whiteboard}
└── scripts/
    ├── upload-movie.js           # Storage 上传 + Firestore note + FCM
    ├── upload-movie.sh           # 上面的安全封装
    └── run-with-aiskillsrunner.sh
```
