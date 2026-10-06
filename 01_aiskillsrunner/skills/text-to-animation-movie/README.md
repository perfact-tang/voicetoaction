# text-to-animation-movie

一个 [DSH](https://github.com/deepseek-ai/deepseek-harness) skill：把外部传入的
**文本**（故事、文章、解说文案、产品说明、会议记录）做成一支出**手绘白板风格**的讲解动画，
上传到 Firebase Storage 的 `movie/` 目录，取得 public url 后把一条 note 写入
`/users/[userid]/notes/[noteid]`，并发送 FCM 通知。

- **默认 9:16 竖屏 1080×1920，手绘白板风格**；指示文里写了画幅就按指示文改。
- 先把文本拆成论证链、再写台词、再画分镜 —— 不把文章按小标题逐段翻译成画面。
- 台词用 TTS 配音，并输出**与配音逐行对齐**的 `.lrc` 字幕文件。
- 用 Remotion 在本机渲染成 MP4（无需云端渲染服务）。
- 目标执行环境是 **Ubuntu**；配音**默认用 edge-tts**（微软在线 TTS，音色平稳自然，
  合成时需联网），没网时回退本地 MeloTTS，再不行回退 `espeak-ng`，都没有时退化为纯字幕版。
- 工作目录按 **DocID** 组织：`~/Documents/tmpaiskill/<DocID>/`。
- 最后**只调用 1 次**上传脚本，完成 Storage 上传 + Firestore 写入 + FCM。

## 目录结构

```
text-to-animation-movie/
├── SKILL.md                       # 完整流程（Step1..Step8）与硬性要求
├── references/
│   ├── 01-script.md               # 论证链拆解 + 台词写法
│   ├── 02-scenes.md               # 白板引擎 API 与分镜画法
│   └── 03-publish.md              # 说明文写法 + 上传/通知细节
├── examples/
│   ├── story-sample.md            # 示例输入文本
│   └── script-sample.json         # 示例 script.json
├── assets/engine/                 # 每次复制到工作目录的 Remotion 工程模板
│   └── scripts/
│       ├── build_audio.py         # 配音 + 时间轴（自动判定 MeloTTS / espeak）
│       └── melo_tts.py            # MeloTTS 批处理 worker（模型只加载一次）
└── scripts/
    ├── upload-movie.js            # Storage 上传 + Firestore note + FCM
    ├── upload-movie.sh            # 上面的安全封装（校验参数与依赖）
    └── run-with-aiskillsrunner.sh # 便捷入口：带正确沙箱权限调用 aiskillsrunner
```

## 安装

`aiskillsrunner` 的默认安装目录是 `~/.agents/skills`，也是 `dsh-skill-filesystem` 的
`user-agents` 根：

```bash
mkdir -p ~/.agents/skills
cp -r text-to-animation-movie ~/.agents/skills/

# 上传脚本的依赖（只装这一次；~66MB）
cd ~/.agents/skills/text-to-animation-movie/scripts
npm install --omit=dev

# 确认可被发现
ls ~/.agents/skills/text-to-animation-movie/SKILL.md
```

渲染工程的依赖（Remotion，约 800MB）由 skill 在运行时复制到工作目录后再安装，
不需要提前装到 skill 目录里。

## 前置条件

```bash
node -v                                  # Node 18+
python3 -V
ffmpeg -version | head -1
ffprobe -version | head -1
sudo apt-get install -y fonts-noto-cjk   # 中文字体（缺了会渲染成方框）
bash scripts/install-melotts.sh          # 推荐：装 edge-tts + MeloTTS（无需 sudo）
sudo apt-get install -y espeak-ng        # 可选：MeloTTS 缺失时的回退；都没有则纯字幕
```

Firebase 服务账号密钥默认路径
`~/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json`，
可用 `AISKILLS_MOVIE_KEY` 覆盖。作者 uid 默认 `KYTF9y43qgc39vKn0sI3qWpsjRE2`。

## 配音后端（edge-tts 优先，自动判定）

`assets/engine/scripts/build_audio.py` 自己挑后端，**不需要人工指定**：

| 顺序 | 后端 | 说明 |
|---|---|---|
| 1 | **`edge`** | edge-tts（微软在线 TTS）。默认音色 **`zh-CN-YunyangNeural`**（男声·新闻播报，平稳）。31 条台词约 **23 秒**合成完。**需要联网。** |
| 2 | `melo` | 本地 MeloTTS（离线兜底，首次要下模型） |
| 3 | `espeak-ng` / `say` | 机械音兜底 |
| 4 | 无 | 纯字幕版 |

失败会自动降级：edge 断网 → 本地 MeloTTS → espeak，每一步都有 warning，视频照常产出。
判定标准是「解释器能不能 `import edge_tts` / `import melo`」，探测顺序
`$AISKILLS_TTS_PYTHON` → `$MELOTTS_PYTHON` → 当前解释器 → `PATH` → `~/melotts-venv` 等约定位置。

```bash
bash scripts/install-melotts.sh                    # 装两个后端（无需 sudo，优先用 uv 管理的 Python）
bash scripts/install-melotts.sh --check            # 只体检
python3 scripts/build_audio.py --tts edge          # 强制 edge-tts（默认）
python3 scripts/build_audio.py --tts melo          # 强制本地 MeloTTS
```

**edge-tts 调参**：

| 变量 | 默认 | 作用 |
|---|---|---|
| `EDGE_TTS_VOICE` | `zh-CN-YunyangNeural` | 音色；也可在 `script.json` 的 `voice` 里直接写 `zh-CN-XiaoxiaoNeural` |
| `EDGE_TTS_RATE` | `+0%` | 语速，如 `+10%` |
| `EDGE_TTS_PITCH` | `+0Hz` | 音高，如 `-2Hz` |
| `EDGE_TTS_RETRIES` | `3` | 网络抖动重试次数 |

备选音色：`zh-CN-XiaoxiaoNeural`（女·温暖）、`zh-CN-YunxiNeural`（男·活泼）、
`zh-CN-YunjianNeural`（男·激情）；全部用 `edge-tts --list-voices | grep zh-CN` 看。

**MeloTTS 兜底时的两道保险**（`assets/engine/scripts/melo_tts.py`）：

1. **静音检测 + 换音色兜底**。MeloTTS 会输出「长度正确、但采样几乎全是 0」的波形。
   实测这不是随机现象：**换种子、把 `sdp_ratio`/`noise_scale` 归零、关掉 BERT、换 torch
   版本（2.2.2 vs 2.14.1 输出逐位相同）都不改变结果**；决定它的是「文本 × speaker」这个
   组合 —— 同一句在 speaker 1 上有声、speaker 0 上是静音。在 24 个音色上筛查 10 条难句，
   **只有 speaker 1 全部通过**，其余最多通过 2/10。所以默认用 speaker 1，并且主音色静音时
   自动换兜底音色（会在日志里标出来）。
2. **逗号不再被它切开**。MeloTTS 会在 `，` 处切句、段间只插 **0.05 秒**停顿（正常逗号停顿
   是 0.2–0.3 秒），听起来像吞字。worker 默认先把行内 `，`/`,`/`；`/`;` 去掉让模型自己断句，
   残留切点用自己的 0.25 秒停顿拼接。（edge-tts 不需要这处理，它自带 0.2–0.3 秒自然停顿。）

**MeloTTS 可调参数**：

| 变量 | 默认 | 作用 |
|---|---|---|
| `MELOTTS_SPEAKER` | `1` | 音色 id（0–255）。`scripts/melo_audition.py` 可试听 |
| `MELOTTS_FALLBACK_SPEAKERS` | `2,3,4,5,6` | 主音色静音时的兜底音色 |
| `MELOTTS_SPEED` | `1.0` | 语速（夹在 0.5–2.0） |
| `MELOTTS_SDP_RATIO` | `0.2` | 韵律随机性；调低更平稳，`0` 最稳 |
| `MELOTTS_NOISE_SCALE` | `0.6` | 语调起伏；调低更平 |
| `MELOTTS_GAP_S` | `0.25` | 残留切点处的停顿秒数 |
| `MELOTTS_STRIP_PUNCT` | `，,；;` | 合成前去掉的行内标点；空值 = 不去 |

试听挑 MeloTTS 音色：

```bash
~/melotts-venv/bin/python assets/engine/scripts/melo_audition.py \
  --text "九种模式不是九选一，一家公司常同时用好几种。" \
  --speakers 0-32:1 --outdir /tmp/melotts-audition
# 产出 spk000.wav…、all.wav（连续听）、index.txt（含 F0 起伏，越小越平稳）
```

实测（12 核 CPU、ZH 模型）：模型加载 17–33s，之后每条台词 1–4s，约 4–5 字/秒 ——
与 `--dry-run` 用的 3.55 字/秒估算接近，总时长不会因为换 TTS 而失控。

## 运行

推荐用便捷入口（自动设置 `DSH_PERMISSION_MODE=danger-full-access`）：

```bash
# 文本直接作为 prompt
~/.agents/skills/text-to-animation-movie/scripts/run-with-aiskillsrunner.sh \
  --prompt "（这里放文本）"

# 文本放在文件里
~/.agents/skills/text-to-animation-movie/scripts/run-with-aiskillsrunner.sh \
  --reffilepath ./story.txt

# 指定 DocID 与画幅
~/.agents/skills/text-to-animation-movie/scripts/run-with-aiskillsrunner.sh \
  --prompt "DocID: 1759700000000，做成 16:9 横屏" --reffilepath ./story.txt
```

等价的手写命令：

```bash
DSH_PERMISSION_MODE=danger-full-access \
  aiskillsrunner --skill text-to-animation-movie --prompt "（文本）"
```

## 生成的文件

```
~/Documents/tmpaiskill/<DocID>/
├── 输入文本.md      # 收到的原文
├── 分镜.md          # 论证链拆解 + 分镜表
├── 台词.lrc         # 带时间戳的台词
├── 动画说明.json     # title / body / tags
├── 动画说明.md       # 人读版
├── movie.mp4        # 成品动画
└── engine/          # 本次的渲染工程（含 source，可复现）
```

## 上传

```bash
bash scripts/upload-movie.sh <noteid> <movie.mp4> --note <动画说明.json>
```

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `AISKILLS_FIREBASE_ACCOUNT` | 未设置 | 服务账号 JSON 路径；由 `aiskillsrunner --firebasekey` 设置，**优先级最高** |
| `AISKILLS_USERID` | 未设置 | 作者 uid；由 `aiskillsrunner --userid` 设置，**优先级最高** |
| `AISKILLS_MOVIE_KEY` | `~/Downloads/vibecodingjapan-…json` | 服务账号 JSON（直接运行脚本时用） |
| `AISKILLS_MOVIE_UID` | `KYTF9y43qgc39vKn0sI3qWpsjRE2` | 作者 uid（直接运行时用） |
| `AISKILLS_MOVIE_DRY_RUN` | 未设置 | 设为 `1` 时空跑，不写 Firebase |
| `AISKILLS_FCM_TOPIC` | 未设置 | FCM topic；未设置且无 token 时跳过通知 |

> ⚠️ **不要用 `*_KEY` 这类名字传密钥路径**。`dsh` 的 subprocess 层会把名字匹配
> `/KEY|PASSWORD|SECRET|TOKEN/i` 的环境变量从子进程环境里剔除，所以经 `dsh` 传到 skill 的
> 变量名是 `AISKILLS_FIREBASE_ACCOUNT`。`AISKILLS_MOVIE_KEY` 只在**直接**运行
> `upload-movie.sh`（不经过 dsh）时有效。

手动单跑（不经过 skill）：

```bash
# 空跑校验
AISKILLS_MOVIE_DRY_RUN=1 bash scripts/upload-movie.sh 1759700000000 ./movie.mp4 \
  --note ./动画说明.json

# 真实上传 + 发通知
AISKILLS_FCM_TOPIC=all bash scripts/upload-movie.sh 1759700000000 ./movie.mp4 \
  --note ./动画说明.json
```

上传会写入：

- Storage `movie/<noteid>.mp4`
- Firestore `/users/<userid>/notes/<noteid>`（`body` 自动追加 public url）
- FCM（有 topic/token 时）

> 用同一个 `noteid` 重跑会**覆盖**同一条笔记与同一个 Storage 对象，不会产生重复。
> 但上传失败时不要立刻重试，先看错误信息。

## 注意事项

- **失败即报告，不要重复上传**：`upload-movie.sh` 每次只执行一次上传。
- **FCM 失败不影响上传**：Storage 与 Firestore 成功后，通知失败只打印告警。
- 渲染需要约 1GB 磁盘（`node_modules` + Headless Chrome），首次运行会下载 Chrome。
- 配音后端由 `build_audio.py` 自动判定：找到能 `import melo` 的解释器就用 **MeloTTS**，
  否则回退 `espeak-ng`，都没有时退化成**纯字幕版**；报告里必须写明用的是哪个。
- 分镜必须**实际渲染静帧检查**：文字超框、手遮文字是最常见的两类问题。
- 中文写作、分镜画法、上传细节的完整规范在 `references/` 下，`SKILL.md` 按步骤要求读取。
