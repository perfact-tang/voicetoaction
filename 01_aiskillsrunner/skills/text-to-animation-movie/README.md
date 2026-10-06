# text-to-animation-movie

一个 [DSH](https://github.com/deepseek-ai/deepseek-harness) skill：把外部传入的
**文本**（故事、文章、解说文案、产品说明、会议记录）做成一支出**手绘白板风格**的讲解动画，
上传到 Firebase Storage 的 `movie/` 目录，取得 public url 后把一条 note 写入
`/users/[userid]/notes/[noteid]`，并发送 FCM 通知。

- **默认 9:16 竖屏 1080×1920，手绘白板风格**；指示文里写了画幅就按指示文改。
- 先把文本拆成论证链、再写台词、再画分镜 —— 不把文章按小标题逐段翻译成画面。
- 台词用 TTS 配音，并输出**与配音逐行对齐**的 `.lrc` 字幕文件。
- 用 Remotion 在本机渲染成 MP4（无需云端渲染服务）。
- 目标执行环境是 **Ubuntu**；配音在 Linux 上走 `espeak-ng`，没有 TTS 时自动退化为纯字幕版。
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
sudo apt-get install -y espeak-ng        # 可选：旁白；没有则纯字幕
```

Firebase 服务账号密钥默认路径
`~/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json`，
可用 `AISKILLS_MOVIE_KEY` 覆盖。作者 uid 默认 `KYTF9y43qgc39vKn0sI3qWpsjRE2`。

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
- 没有 `espeak-ng` 时视频会退化成**纯字幕版**，LRC 依然准确；报告里必须写明。
- 分镜必须**实际渲染静帧检查**：文字超框、手遮文字是最常见的两类问题。
- 中文写作、分镜画法、上传细节的完整规范在 `references/` 下，`SKILL.md` 按步骤要求读取。
