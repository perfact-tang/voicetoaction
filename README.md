# VoiceToAction AI — Monitor 监控程序（Firebase Admin 自动监听）

纯 Node.js 服务。**node server.js 就是"声音监控程序"本体**：启动即自动监听 Firestore，处理录音并生成 Google Doc；**Web 页面只是监视仪表盘**，开不开都不影响后台运行。

## 工作流程（全自动）

呼叫对象来自 CMS（`02_appcms` 的 `/allalservice`）。App 上传录音时会把服务的
`serviceType` / `serviceId` 一起写进 `users/{uid}/recordings/{id}`，后端据此走两条流程之一：

```
服务启动 → Firebase Admin（服务账号）连接
        → 自动监听 Firestore collectionGroup("recordings")
        → 只处理 .env 中 MONITOR_USER_IDS 指定的用户（文档字段 MONITOR_USER_ID_FIELD）的录音
        → 下载 storageUri（gs://）
        →
        ├─ kind = "Markdown"（storageUri 是文本 md 文件，不是录音）
        │    → 跳过 FFmpeg / STT，直接读取 md 文本 → 继续下面的 Action 处理
        └─ 其他 kind（录音）
             → FFmpeg 转 16k wav → faster-whisper 转录
        →
        ├─ serviceType = google_workspace_studio（默认，兼容旧的 aicalling 记录）
        │    → sidecar 创建 Google Doc（Drive OAuth）→ 回写 googleDocUrl
        │
        └─ serviceType = deepseek_harness（CMS 里的 Skills 服务）
             → STT 文本留在本机 monitor-data/stt/<uid>/*.txt
             → 备份一份到 Firebase Storage（stt/...），gs:// 地址写回 Firestore
             → 比对本地版本与 CMS 的 skillActiveVersion：
                  不一致 → 删除本地旧版本 → 重新下载安装（局部装在 monitor-data/skills）
             → 调 aiskillsrunner 执行 skill（dsh --profile headless）
             → 回写 skillOutput / skillVersion / sttTextPath

        → 回写 Firestore（state: success/failed）
        → FCM 推送通知到 App（「○○ 执行完毕」/「○○ 执行失败」）
        → Web 页面（SSE）实时显示：后台状态 / 处理历史 / 运行日志
```

### 输入类型：`kind`（录音 / Markdown 文本）

`users/{uid}/recordings/{id}` 的 `kind` 字段决定 `storageUri` 指向什么：

| `kind` | `storageUri` 指向 | 后端行为 |
| :--- | :--- | :--- |
| `Markdown`（也兼容 `MARKDOWN` / `md` / `text/markdown`） | 一份 UTF-8 Markdown 文本文件 | **不做 STT**：下载后直接读取文本，进入后续 Action 处理（Google Doc 或 Skill），任务阶段显示 `reading_text` |
| 其他（`ORIGINAL` / `MERGED` 等） | 录音文件 | FFmpeg 转 16k wav → faster-whisper 转录 |

两种情况之后的处理完全一致，Markdown 输入同样会生成 Google Doc / 执行 Skill 并回写 `state: success`。


## 架构

| 组件 | 说明 |
| :--- | :--- |
| `server.js` | 入口：读 `.env` → 启动 Monitor → HTTP + SSE |
| `monitor.js` | Firebase Admin：自动监听、用户过滤、任务队列、两条流程的编排、推送 |
| `monitor-skills.js` | Skills：本地版本登记、版本比对、删除重装、调用 `aiskillsrunner` |
| `monitor-notify.js` | FCM：读取 `users/{uid}/fcmTokens`、发送通知、清理失效令牌 |
| `monitor-sidecar/` | Google Drive / Docs 侧车（OAuth 授权、创建文档） |
| `scripts/faster_whisper_transcribe.py` | Python 转录进程（.venv 内 faster-whisper） |
| `01_aiskillsrunner/` | 调用本机 dsh 执行 skill 的 CLI（支持 `--installurl` / `--force-install`） |
| `public/` | 只读监视仪表盘（状态卡片 + 处理历史 + 日志流） |

## 快速开始

```bash
# 1) 配置（复制模板并填写）
cp .env.example .env
#    FIREBASE_ADMIN_KEY_PATH    服务账号 JSON 路径（必填）
#    GOOGLE_OAUTH_CLIENT_PATH   Drive OAuth 客户端 JSON 路径（必填）
#    GOOGLE_DRIVE_FOLDER_ID     输出文件夹 ID（可选，可用 aicallingid 覆盖）
#    MONITOR_USER_IDS           只监视的用户 ID，逗号分隔（如 uid1,uid2）
#    MONITOR_USER_ID_FIELD      用户 ID 字段名（默认 uid）
#    WHISPER_MODEL              转录模型（默认 faster:small）
#    SKILLS_DIR                 skill 安装目录（默认 monitor-data/skills，只给本后端用）
#    FCM_ENABLED                任务结束推送 FCM 通知（默认 true）

# 2) 一键安装并启动
./run-ubuntu.sh
```

启动后自动开始监听（日志会打印 Admin 连接与监听信息）。浏览器打开 http://localhost:15888 查看仪表盘。

**唯一一次性操作**：首次需要点击仪表盘上的 **Connect Google** 完成 Drive 授权（OAuth 客户端须为"桌面应用"类型，重定向 URI 含 `http://localhost`）。授权后 token 存于 `monitor-data/drive/oauth-token.json`，之后无需再操作。

## 局域网访问（端口 15888）

- 服务监听 **`0.0.0.0:15888`**（`.env` 中 `PORT=15888`、`HOST=0.0.0.0`），同局域网其他电脑可用
  `http://<本机IP>:15888` 打开监视仪表盘。
- **防火墙**：本机 UFW 已启用，需开放端口（一次性，需要 sudo）：

  ```bash
  sudo ./scripts/open-firewall.sh      # = sudo ufw allow 15888/tcp
  ```

- **启动播报（日文）**：服务启动后自动输出并朗读：

  ```
  IPアドレスは 192.168.0.161 です。
  ポート番号は 15888 です。
  ```

  - 控制台文字用日文打印（见上）；**语音朗读用英文**（espeak-ng 内置 ja 语音读不准日语），数字**逐位慢读**：
    `Media Splitter Monitor started. IP address is one nine two dot one six eight dot zero dot one six one. Port number is one five eight eight eight.`
  - 语音实现：espeak-ng 生成 WAV → `aplay -D` 直出 ALSA 硬件设备（默认 `plughw:0,0` 自带扬声器，可用 `.env` 的 `AUDIO_DEVICE` 指定），**绕过 PulseAudio/PipeWire 用户会话，未登录也能响**。
  - 无音频设备时语音静默，不影响服务（文字播报一定打印）。

## .env 配置项

| 变量 | 必填 | 说明 |
| :--- | :--- | :--- |
| `FIREBASE_ADMIN_KEY_PATH` | ✅ | Firebase 服务账号 JSON 路径（控制台 → 项目设置 → 服务账号） |
| `GOOGLE_OAUTH_CLIENT_PATH` | ✅ | Google Drive OAuth 客户端 JSON 路径（桌面应用类型） |
| `GOOGLE_DRIVE_FOLDER_ID` | 可选 | 默认输出文件夹 ID（记录文档 `aicallingid` 可覆盖） |
| `MONITOR_USER_IDS` | 建议 | 只监视的用户 ID，**逗号分隔复数**；留空 = 全部用户 |
| `MONITOR_USER_ID_FIELD` | 可选 | 记录文档中用户 ID 字段名（默认 `uid`） |
| `WHISPER_MODEL` | 可选 | faster:tiny / base / small / medium / large-v3 / distil-large-v3 |
| `SKILLS_DIR` | 可选 | skill 安装目录（默认 `monitor-data/skills`，**只给本后端用**，不污染 `~/.agents/skills`） |
| `SKILLS_RUNNER_BIN` | 可选 | aiskillsrunner 路径（默认用仓库里的 `01_aiskillsrunner/aiskillsrunner.js`，由同一个 node 执行） |
| `DSH_BIN` | 可选 | dsh 可执行文件（默认由 aiskillsrunner 从 PATH 查找；子进程 PATH 会自动补上当前 node 目录） |
| `SKILLS_RUNNER_TIMEOUT_MS` | 可选 | 单次 skill 执行超时（默认 1800000 = 30 分钟） |
| `STT_OUTPUT_DIR` | 可选 | STT 文本在本机的保存目录（默认 `monitor-data/stt`） |
| `DSH_PERMISSION_MODE` | 可选 | 传给 `dsh` 的权限模式（默认 `danger-full-access`） |
| `FCM_ENABLED` | 可选 | 任务结束后是否推送 FCM 通知（默认 `true`，设 `false` 关闭） |
| `PORT` / `HOST` | 可选 | 服务端口 / 监听地址 |

## Skills 流程（呼叫对象类型 = DeepSeek Harness）

App 的呼叫对象选择 CMS 里 `type = deepseek_harness` 的服务时，**不使用 Google Drive**：

1. **STT 留在本地**：转录文本写入 `STT_OUTPUT_DIR/<uid>/<recordingId>.txt`，不会被删除；
   同时**备份一份**到 Firebase Storage 的 `stt/<uid>/<recordingId>.txt`，把 `gs://` 地址写回
   该录音文档的 `sttTextPath`（另附 `sttTextUrl` 与 `sttTextLocalPath`）。
2. **局部安装**：skill 装到 `SKILLS_DIR`（默认 `monitor-data/skills`），通过环境变量
   `AISKILLSRUNNER_SKILLS_DIR` 告诉 aiskillsrunner，因此只影响本后端。
3. **版本维护**：本地登记表 `SKILLS_DIR/.installed.json` 记录每个 skill 的版本号；
   与 CMS 的 `allalservice/{serviceId}.skillActiveVersion` 比对。
4. **更新机制**：版本不一致（或下载地址变化 / 本地文件缺失）时，先删除本地旧版本，
   再用 `aiskillsrunner --installurl <skillZipUrl> --force-install` 重新下载安装。

实际执行的命令与手写完全一致，只是参数由后端从 CMS 和 `.env` 取：

```bash
DSH_PERMISSION_MODE=danger-full-access aiskillsrunner \
  --skill <CMS 的 applicationName> \
  --reffilepath <本机 STT txt 路径> \
  --firebasekey <FIREBASE_ADMIN_KEY_PATH> \
  --userid <录音所属 uid> \
  --installurl <CMS 的 skillZipUrl> [--force-install]
```

> 后端默认执行**仓库里的** `01_aiskillsrunner/aiskillsrunner.js`（用运行后端的那份 node），
> 因此不依赖 `PATH`，也不会用到全局安装的旧版本；想改用全局命令就设 `SKILLS_RUNNER_BIN=aiskillsrunner`。
> 子进程 PATH 会自动前置当前 node 所在目录，保证 `aiskillsrunner` 能找到 `dsh`。

> `--force-install` 会删除 `SKILLS_DIR` 里的旧版本再安装。即使全局 `~/.agents/skills`
> 里有同名 skill，也会保证用的是本地这份。

**运行前提（很容易踩）**：后端进程必须以**登录用户**运行，不能用 root ——
`dsh` / `aiskillsrunner` 的凭据与 profile 在 `$HOME/.dsh` 下（`~/.dsh/profiles/headless`），
root 的 `/root/.dsh` 是空的，以 root 运行时 skill 根本起不来。systemd 单元已带
`User=` / `Group=` / `HOME=`，详见 `AUTOSTART_GUIDE.md`。

**执行过程中的可见性**：aiskillsrunner / dsh 的 stderr / stdout 会**实时**写进监控日志
（形如 `[skill:stderr] …`），每 60 秒还有一条 `skill 仍在执行…已运行 Ns` 心跳；
一旦超过 `SKILLS_RUNNER_TIMEOUT_MS`（默认 30 分钟），会**杀掉整个 skill 进程组**
并把任务标记为失败，绝不会永远卡在 `running_skill`。

> ⚠️ **`.env` 里不能出现 `DSH_*` / `XDG_*` / `DYLD_*` / `BASH_FUNC_*` 变量。**
> `dsh` 会把「工作目录/.env」当作 project 层读取，这些前缀只允许来自**真正 export 的环境变量**，
> 否则 dsh 会直接报错退出：
> `dsh: .../.env sets "DSH_PERMISSION_MODE", which only the launching environment may set`。
> 因此权限模式用的是 **`SKILLS_DSH_PERMISSION_MODE`**（后端再以环境变量 `DSH_PERMISSION_MODE` 传给 dsh），
> dsh 可执行文件用 **`SKILLS_DSH_BIN`**。后端启动时会自动扫描 `.env` 并对这类键名告警。

## 消息推送（FCM）

任务结束后（成功或失败）后端都会推送一条通知，例如「audio-to-multilingual-blog 执行完毕」。

- App 登录后把 FCM 令牌登记到 `users/{uid}/fcmTokens/{token}`（规则：仅本人可读写），
  退出登录时删除，避免换账号后收到上一个人的通知。
- 后端用 admin SDK 读取令牌并调用 `admin.messaging()` 推送；令牌失效会自动从 Firestore 清理。
- 关闭推送：`.env` 里设 `FCM_ENABLED=false`。

### 手机收不到通知时怎么排查

`推送已受理` 只说明 **FCM 收下了**（令牌有效），**不等于手机弹了通知**。按下面顺序查：

1. **通知权限（最常见）**：Android 13+ 没有 `POST_NOTIFICATIONS` 时，系统会**静默丢弃**推送。
   App 现在会在登录后主动申请该权限；若被拒绝会弹提示。也可以手动确认：
   手机「设置 → 应用 → ideavox → 通知」总开关打开，且「处理结果」渠道未被关闭。
2. **跑一次测试推送**（不用等真实任务）：

   ```bash
   node scripts/send-test-push.mjs              # 用 .env 里的 MONITOR_USER_IDS
   node scripts/send-test-push.mjs <uid> "标题" "正文"
   ```

   脚本会发送并等待 App 的**回执**（App 收到后会往 `fcmTokens/{token}` 写 `lastPushAt`）：
   - **有回执** → 消息到了手机，没弹就是权限/渠道问题（见上一条）；
   - **没回执** → 消息没到 App：手机离线、App 被「强行停止」、或 App 尚未安装含 FCM 的新版本。
3. **确认 App 已登录过**：`users/{uid}/fcmTokens` 里必须至少有一个 `platform=android` 的令牌。

> 注意：在系统设置里对 App 点「强行停止」后，FCM 不再接收消息，直到用户再次手动打开 App。

## 目录说明

| 路径 | 用途 |
| :--- | :--- |
| `public/` | 只读监视仪表盘 |
| `server.js` | 入口（读 .env、启动、HTTP/SSE） |
| `monitor.js` | Monitor 运行时（Admin、监听、队列、编排、推送） |
| `monitor-skills.js` | Skills 本地版本登记 / 比对 / 重装 / 调用 aiskillsrunner |
| `monitor-notify.js` | FCM 令牌读取与推送 |
| `scripts/send-test-push.mjs` | 手动发测试推送 + 等待手机回执（排查通知问题） |
| `monitor-sidecar/index.js` | Drive / Docs 侧车 |
| `monitor-data/` | 任务历史（jobs.json）、Drive token、临时目录、`skills/`（Skill 安装）、`stt/`（本地 STT 文本）（gitignore） |
| `models/faster-whisper/` | 模型下载目录 |
| `.env.example` / `.env` | 配置模板 / 实际配置（gitignore） |

## API

- `GET /api/health` — 后端 / Admin / faster-whisper / 监视用户状态
- `GET /api/monitor/state` — 完整快照（config / jobs / logs）
- `POST /api/monitor/command` — `monitor_connect_drive`（一次性 Drive 授权）
- `GET /api/monitor/events` — SSE（snapshot / log 实时推送）

## 说明

- 无 GPU 也能运行：faster-whisper 在 CPU 上自动 int8 量化。
- 监听只处理**新出现的**录音记录（跳过首次快照的存量文档与 `state: success` 记录）。
- 服务进程就是监控程序：`nohup ./run-ubuntu.sh &` 或 systemd 可后台常驻；页面只是查看器。
- 模型下载慢（国内网络）：`export HF_ENDPOINT=https://hf-mirror.com` 后重跑脚本。
