# VoiceToAction AI Monitor — Ubuntu 开机自启指南

> 目标：**开机后无需手动操作，Monitor 自动运行**（Firebase Admin 自动监听 → faster-whisper 转录 → Google Doc）。
> Web 页面（http://localhost:15888）只是监视仪表盘，开不开都不影响后台。

---

## 0. 三种方式怎么选

| 方式 | 是否需要 root | 何时启动 | 适用 |
| :--- | :--- | :--- | :--- |
| **A. systemd 服务（推荐）** | 需要（一次 sudo） | **开机即启**，崩溃自动重启，随系统停止 | 服务器 / 长期运行，最正规 |
| **B. GNOME 桌面自启** | 不需要 | 登录桌面后启动 | 有图形界面的个人电脑（本机已配置好） |
| **C. cron @reboot** | 不需要 | 开机后由 cron 拉起 | 备选 / 不想装服务的场景 |

> 三者都调用同一个幂等脚本 `scripts/start-monitor.sh`：**已在运行则跳过**，不会重复启动、不会端口冲突，
> 可以同时配置 A + B（先启动的生效，另一个自动跳过）。

---

## A. systemd 服务（推荐）

### A.1 安装（一次性，需要 sudo）

```bash
# 先停掉当前手动运行的服务（避免端口冲突）
pkill -f "node server.js"

# 安装并启动（拷贝单元文件 → 启用 → 启动）
sudo ./scripts/install-systemd-service.sh
```

安装脚本做的事：
1. 把 `deploy/mediasplitter-monitor.service` 拷贝到 `/etc/systemd/system/`（自动替换项目路径与运行用户）
2. 把 `monitor-data/` 的属主改成该用户（之前若以 root 跑过，里面会有 root 属主的文件）
3. `systemctl daemon-reload && systemctl enable && systemctl restart`

> ⚠️ **仓库改名或移动到别的路径后，必须重新执行一次 A.1 的安装命令。**
> systemd 单元里保存的是**绝对路径**（`WorkingDirectory` / `ExecStart`），不会跟着文件夹改名走。
> 不改的表现：`systemctl status` 显示 `activating (auto-restart)`、`status=203/EXEC`（找不到 `start-monitor.sh`），
> 每 5 秒重启一次，开机自启失效（但手动跑着的实例仍在工作，所以不易察觉）。
> 自查：`systemctl cat mediasplitter-monitor | grep -E "WorkingDirectory|ExecStart"` 是否指向当前目录。

> ⚠️ **服务必须以登录用户运行，不能用 root。**
> `dsh` / `aiskillsrunner` 的凭据和 profile（`~/.dsh/profiles/headless` 等）都在用户 home 下，
> root 的 `/root/.dsh` 是空的 —— 以 root 运行时 Skills 流程（`deepseek_harness`）无法执行。
> 安装脚本会自动写入 `User=` / `Group=` / `HOME=`。若你手工改过单元文件，请确认这三项存在。
> 启动播报用 `aplay` 直出 ALSA 设备，用户不在 `audio` 组时语音会静默（不影响服务）：
> `sudo usermod -aG audio $USER`（之后需重新登录）。

### A.2 日常管理命令

```bash
systemctl status mediasplitter-monitor      # 查看状态
sudo systemctl restart mediasplitter-monitor # 重启
sudo systemctl stop mediasplitter-monitor    # 停止
journalctl -u mediasplitter-monitor -f       # 实时看日志（开机自启时的输出都在这）
journalctl -u mediasplitter-monitor -n 50    # 看最近 50 行日志
```

### A.3 开机自启验证

```bash
systemctl is-enabled mediasplitter-monitor   # 应输出 enabled
curl http://127.0.0.1:15888/api/health        # 应返回 {"ok":true,...}
```

> 服务单元已随仓库保存：`deploy/mediasplitter-monitor.service`
> （`ExecStart` 指向幂等脚本；`Restart=on-failure` 崩溃自动拉起；
>  `Environment=PATH` 已包含 nvm 的 node 路径）。

### A.4 卸载

```bash
sudo systemctl disable --now mediasplitter-monitor
sudo rm /etc/systemd/system/mediasplitter-monitor.service
sudo systemctl daemon-reload
```

---

## B. GNOME 桌面自启（无需 root，本机已配置 ✅）

桌面登录后自动启动。文件：`~/.config/autostart/mediasplitter-monitor.desktop`

```ini
[Desktop Entry]
Type=Application
Name=VoiceToAction AI Monitor
Exec=/home/pengfei-mini/Documents/GitHub/voicetoaction/scripts/start-monitor.sh
Terminal=false
X-GNOME-Autostart-enabled=true
X-GNOME-Autostart-Delay=10
```

- **已在当前机器配置好**，下次登录桌面即自动生效
- 生效验证：`ls ~/.config/autostart/mediasplitter-monitor.desktop`
- 如需取消：删除该文件即可

> 手动启用当前会话：`nohup ~/.config/autostart/../scripts/start-monitor.sh &`
> （实际路径为仓库内 `scripts/start-monitor.sh`）

---

## C. cron @reboot（备选）

```bash
# 打开当前用户的 crontab
crontab -e
# 加入一行（注意：cron 环境 PATH 很精简，脚本内部会自动找 node）
@reboot /home/pengfei-mini/Documents/GitHub/voicetoaction/scripts/start-monitor.sh
```

取消：删掉该行。

---

## 幂等启动脚本说明

`scripts/start-monitor.sh` 负责所有入口的启动，行为：

1. 若 `monitor-data/server.pid` 存在且进程存活（且 cwd 为本项目）→ 跳过
2. 若 `http://127.0.0.1:15888/api/health` 健康检查通过 → 跳过（防手动实例冲突）
3. 否则前台启动 `node server.js` 并写入 pidfile（退出时自动清理）

> 若服务端口不是 15888，请同步修改 `.env` 的 `PORT` 与脚本中的默认值。

---

## 开机自启前 Checklist

- [ ] `.env` 已配置：`FIREBASE_ADMIN_KEY_PATH` / `GOOGLE_OAUTH_CLIENT_PATH` / `MONITOR_USER_IDS` 等
- [ ] Drive 已授权一次（`monitor-data/drive/oauth-token.json` 存在；页面 Drive 显示已连接）
- [ ] `scripts/start-monitor.sh` 可执行（`ls -l` 有 x 权限；`chmod +x` 补上）
- [ ] 手动验证能跑通：`./scripts/start-monitor.sh` → `curl http://127.0.0.1:15888/api/health`

---

## 启动播报（局域网 IP + 端口）说明

服务与播报是两回事：

| 需求 | 机制 | 说明 |
| :--- | :--- | :--- |
| **不登录也要运行** | systemd（`multi-user.target`，enabled） | 开机即启动，无需登录/输密码（日志：`journalctl -b -u mediasplitter-monitor`） |
| **开机后能看到文字播报** | server.js 启动横幅 | 写入 systemd 日志（日文文字），随时可查 |
| **能听到语音播报** | ① server.js 启动后 0s/60s/120s 尝试朗读 ② **登录后 GNOME 自启朗读**（`~/.config/autostart/mediasplitter-monitor-announce.desktop`，音频就绪才能真正听到） | 开机早期尚无用户音频会话，语音静默属正常；登录后自启会朗读一次 |

语音为**英文、数字逐位慢读**（`espeak-ng -v en-us -s 100`）：
> "Media Splitter Monitor started. IP address is one nine two dot one six eight dot zero dot one six one. Port number is one five eight eight eight."

> 说明：`espeak-ng` 内置日语（ja）不是真正的日语 TTS，读不准日语，故语音用英文；
> 手动播报：`./scripts/announce-ip.sh`。

---

## 局域网访问（防火墙）

- 服务监听 `0.0.0.0:15888`（`.env` 的 `HOST=0.0.0.0`、`PORT=15888`）
- 本机 UFW 已启用，需放行（一次性，sudo）：
  ```bash
  sudo ufw allow 15888/tcp
  ```
- 其他电脑访问：`http://<本机IP>:15888`（如 192.168.0.161）

---

## 本次操作记录（2025-09 · 本机 Ubuntu 24.04）

1. ✅ 创建幂等启动脚本 `scripts/start-monitor.sh` 并测试（已运行跳过 / 正常启动 / pidfile 均通过）
2. ✅ 创建 systemd 单元 `deploy/mediasplitter-monitor.service` + 安装脚本 `scripts/install-systemd-service.sh`
3. ✅ 配置 GNOME 桌面自启 `~/.config/autostart/mediasplitter-monitor.desktop`（登录即生效，无需 root）
4. ⏳ **待你执行一次**（需要 sudo，二选一或都做）：
   - 推荐：`sudo ./scripts/install-systemd-service.sh`（开机即启 + 崩溃自愈）
   - 或：仅保留桌面自启（登录后才启动，无需任何 root 操作）
5. ✅ 验证流水线已跑通：Drive 已授权（tan@azest.co.jp），7 条录音全部 success
