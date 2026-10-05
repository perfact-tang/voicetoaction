# VoiceToAction AI — Ubuntu 运行指南（Monitor · Firebase Admin 自动监听版）

> 本项目是**声音监控程序**：Node 服务启动即用 **Firebase Admin（服务账号）** 自动监听 Firestore
> `recordings` 集合，只处理 **`.env` 中 `MONITOR_USER_IDS`** 指定的用户的录音，自动完成
> 下载 → faster-whisper 转录 → 生成 Google Doc → 回写 Firestore。
> **Web 页面只是监视仪表盘**：页面开不开，后台都持续运行。
>
> 文档中的命令已在以下环境实测通过：
> **Ubuntu 24.04.4 LTS · Node.js v24.15.0 · FFmpeg 6.1.1 · Python 3.12.3 · firebase-admin 13.10.0（无 GPU）**

---

## 0. 特性与流程

| 特性 | 说明 |
| :--- | :--- |
| Firebase | **Admin SDK（服务账号）**，路径写于 `.env`（`FIREBASE_ADMIN_KEY_PATH`），程序自读 |
| 监听 | 启动即自动监听 `collectionGroup("recordings")`，只处理新出现的记录 |
| 用户过滤 | 只处理 `MONITOR_USER_IDS`（逗号分隔复数）中的用户；字段名 `MONITOR_USER_ID_FIELD`（默认 uid） |
| 音频源 | Firebase Storage（`storageUri` / `recordFile` 为 `gs://`，服务端直接下载） |
| 转录 | **faster-whisper**（Python + CTranslate2），无 GPU 自动 CPU int8 |
| 输出 | Google Doc（Drive OAuth，客户端 JSON 路径写于 `.env`），回写 Firestore `state: success/failed` |
| Web 页面 | 只读仪表盘：Admin/Drive/监视用户状态 + 处理历史 + 实时日志（SSE） |

流水线：`监听新记录 →（用户过滤）→ 下载 GCS 音频 → FFmpeg 转 16k wav → faster-whisper 转录 → 创建 Google Doc → 回写 Firestore`。

---

## 1. 快速开始（一键脚本）

```bash
# 1) 配置（复制模板并填写）
cp .env.example .env
#    必填：FIREBASE_ADMIN_KEY_PATH（服务账号 JSON 路径）
#         GOOGLE_OAUTH_CLIENT_PATH（Drive OAuth 客户端 JSON 路径）
#    建议：MONITOR_USER_IDS=uid1,uid2（只监视这些用户）
#    可选：GOOGLE_DRIVE_FOLDER_ID / MONITOR_USER_ID_FIELD / WHISPER_MODEL

# 2) 一键安装 + 启动（启动即自动监听）
./run-ubuntu.sh
```

浏览器打开 **http://localhost:15888** 查看仪表盘；日志打印 Admin 连接与监听信息。

> 后台常驻：`nohup ./run-ubuntu.sh > monitor.log 2>&1 &`（或配置 systemd）。

---

## 2. 手动步骤

```bash
# 1) 系统依赖
sudo apt update
sudo apt install -y ffmpeg python3 python3-venv
# Node.js >= 20（推荐 nvm）：
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
nvm install 22 && nvm use 22

# 2) 项目依赖 + 配置
cd /path/to/voicetoaction
npm install
cp .env.example .env   # 编辑填入键值

# 3) faster-whisper 转录后端
npm run setup:faster-whisper

# 4) 启动（自动监听）
npm run dev     # 等价于 node server.js
```

启动成功输出示例：

```
[monitor] Firebase Admin 已初始化（项目 vibecodingjapan）
[monitor] Firestore 监听已自动启动（collectionGroup: recordings；监视用户: uid1, uid2）
faster-whisper backend: ready
Firebase Admin: ready (project vibecodingjapan)
监视用户: uid1, uid2 | 用户字段: uid
Drive: tan@example.com | 模型: faster:small
================================================
VoiceToAction AI Monitor 起動しました。
IPアドレスは 192.168.0.161 です。          ← 局域网 IP（自动检测）
ポート番号は 15888 です。                 ← 端口
Web ページ: http://192.168.0.161:15888
================================================
```

> 启动后会**自动播报**局域网 IP 与端口：控制台以日文打印（`IPアドレスは … です。ポート番号は … です。`）；
> 语音朗读用**英文**（espeak-ng 内置 ja 语音质量差、读不出标准日语）：
> 「Media Splitter Monitor started. IP address is **one nine two dot one six eight dot zero dot one six one**.
> Port number is **one five eight eight eight**.」——数字**逐位慢读**（`-s 100`），清晰可辨；
> 无音频设备时静默，不影响服务。

---

## 3. .env 配置说明

| 变量 | 必填 | 说明 |
| :--- | :--- | :--- |
| `FIREBASE_ADMIN_KEY_PATH` | ✅ | Firebase 服务账号 JSON 路径（控制台 → 项目设置 → 服务账号 → 生成新的私钥） |
| `GOOGLE_OAUTH_CLIENT_PATH` | ✅ | Google Drive OAuth 客户端 JSON 路径（Google Cloud → 凭据 → OAuth 客户端 ID → **桌面应用**） |
| `GOOGLE_DRIVE_FOLDER_ID` | 可选 | 默认输出文件夹 ID；记录文档 `aicallingid` 字段可逐条覆盖 |
| `MONITOR_USER_IDS` | 建议 | **只监视的用户 ID**，多个用英文逗号分隔；留空 = 监视全部 |
| `MONITOR_USER_ID_FIELD` | 可选 | 记录文档中用户 ID 字段名（默认 `uid`） |
| `WHISPER_MODEL` | 可选 | faster:tiny / base / small / medium / large-v3 / distil-large-v3 |
| `PORT` / `HOST` | 可选 | 服务端口 / 监听地址 |

> `.env` 含服务账号私钥，已在 `.gitignore` 中，请勿提交。

---

## 4. Firebase 侧准备（控制台）

1. **服务账号**：项目设置 → 服务账号 → 生成新的私钥 → JSON 路径填入 `.env`
2. **Firestore 已建库**（`recordings` 集合组查询通常无需额外索引，如提示按控制台指引创建）
3. **服务账号权限**：默认应有 Firestore/Storage 访问权限；如被限制，在 IAM 中授予对应角色

---

## 5. 页面使用（监视仪表盘）

1. 打开 http://localhost:15888 —— 显示 Firebase 项目、监听状态、**监视用户列表**、Drive 状态、模型
2. **Connect Google**：首次需要，点击后浏览器完成一次性 Drive 授权（token 存 `monitor-data/drive/oauth-token.json`）
3. 之后页面只读：处理历史（成功/失败/进度）与运行日志实时刷新

> 页面关闭/刷新不影响后台监听与处理。

---

## 6. 目录说明

| 路径 | 用途 |
| :--- | :--- |
| `public/` | 只读监视仪表盘 |
| `server.js` | 入口（读 .env、启动、HTTP/SSE） |
| `monitor.js` | Monitor 运行时（Admin、自动监听、用户过滤、队列、编排） |
| `monitor-sidecar/index.js` | Drive / Docs 侧车 |
| `scripts/faster_whisper_transcribe.py` | Python 转录进程 |
| `monitor-data/` | 任务历史（jobs.json）、Drive token、临时工作目录 |
| `models/faster-whisper/` | 模型下载目录 |
| `.env.example` / `.env` | 配置模板 / 实际配置 |
| `run-ubuntu.sh` | 一键安装 + 启动 |

### API 一览

| 接口 | 说明 |
| :--- | :--- |
| `GET /api/health` | 后端 / Admin / faster-whisper / 监视用户状态 |
| `GET /api/monitor/state` | 完整快照（config / jobs / logs） |
| `POST /api/monitor/command` | `monitor_connect_drive`（一次性 Drive 授权） |
| `GET /api/monitor/events` | SSE 事件流（snapshot / log） |

---

## 7. 无 GPU 说明

- faster-whisper（CTranslate2）无 GPU 自动 CPU + int8 量化，本项目**完全不需要 GPU**。
- CPU 转录速度参考（8 核）：tiny ≈ 实时 5~10 倍，base ≈ 2~4 倍，small ≈ 1~2 倍。
- 模型下载慢（国内网络）：`export HF_ENDPOINT=https://hf-mirror.com` 后重跑脚本。

---

## 8. 常见问题（FAQ）

| 现象 | 解决办法 |
| :--- | :--- |
| 启动日志 `FIREBASE_ADMIN_KEY_PATH 未设置或文件不存在` | 检查 `.env` 中路径是否正确（绝对路径） |
| 启动日志 `Firebase Admin 初始化失败` | 服务账号 JSON 无效/损坏，重新在控制台生成 |
| 页面显示"未授权" | 打开仪表盘点一次 **Connect Google**（OAuth 客户端须为桌面应用，重定向 URI 含 `http://localhost`） |
| 任务失败 `GOOGLE_DRIVE_FOLDER_ID 未设置` | 在 `.env` 填默认文件夹 ID，或用记录文档 `aicallingid` 指定 |
| 任务失败 `Download failed` | `recordFile` 非 `gs://`，或服务账号无 Storage 读取权限 |
| 只想处理特定用户 | `MONITOR_USER_IDS=uid1,uid2`；字段名不同则设 `MONITOR_USER_ID_FIELD` |
| 端口被占用 | 换端口：改 `.env` 的 `PORT` 后重启 |
| 局域网其他电脑无法访问 | ① 确认服务绑定 `0.0.0.0`（`.env` 的 `HOST`）；② 开放防火墙：`sudo ./scripts/open-firewall.sh`（= `sudo ufw allow 15888/tcp`）；③ 用 `http://<本机IP>:15888` 访问 |
| 页面空白 / 无法打开 | 确认服务在运行：`curl http://127.0.0.1:15888/api/health` |

---

## 9. 本次在目标机器上的验证记录

在「Ubuntu 24.04.4 LTS · 纯 CPU」上已完成：

1. ✅ 架构改回 **Firebase Admin**：`firebase-admin@13.10.0`，服务账号路径来自 `.env`
2. ✅ **启动即自动监听**：实测启动日志出现 `Firestore 监听已自动启动（collectionGroup: recordings；监视用户: ...）`
3. ✅ `.env` 读取：`FIREBASE_ADMIN_KEY_PATH` / `GOOGLE_OAUTH_CLIENT_PATH` / `GOOGLE_DRIVE_FOLDER_ID` / `MONITOR_USER_IDS`（逗号分隔解析正确） / `MONITOR_USER_ID_FIELD` / `WHISPER_MODEL`
4. ✅ 用户过滤逻辑：仅 `MONITOR_USER_IDS` 中的用户进入队列（模拟凭据启动验证状态输出）
5. ✅ `monitor_connect_drive` 生成授权链接（sidecar 正常拉起）
6. ✅ 仪表盘（状态卡片/处理历史/日志流）与 SSE 实时推送正常
7. ✅ `run-ubuntu.sh` 幂等执行通过

> 真实联调（Firestore 真实监听、GCS 下载、Drive 建文档）需你的真实凭据：按第 1、4 节配置 `.env` 与授权即可。

---

*文档更新：2025-09 · Node.js + Firebase Admin + 自动监听 + 只读仪表盘*
