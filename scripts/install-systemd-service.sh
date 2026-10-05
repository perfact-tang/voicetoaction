#!/usr/bin/env bash
# ============================================================
#  MediaSplitter AI Monitor — systemd 服务安装脚本
#
#  用法（需要 sudo，仅首次执行一次）：
#    sudo ./scripts/install-systemd-service.sh
#
#  安装前请先停掉手动运行的服务，避免端口冲突：
#    pkill -f "node server.js"
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

UNIT="mediasplitter-monitor.service"
SRC="./deploy/$UNIT"
DEST="/etc/systemd/system/$UNIT"

if [[ ! -f "$SRC" ]]; then
  echo "错误：缺少 $SRC" >&2
  exit 1
fi

# 以「调用 sudo 的用户」运行服务，绝不能用 root：
# dsh / aiskillsrunner 的凭据与 profile 在 $HOME/.dsh 里，root 的 /root/.dsh 是空的，
# 以 root 运行时 skill 无法执行（会卡住或直接失败）。
RUN_USER="${SUDO_USER:-$(id -un)}"
RUN_GROUP="$(id -gn "$RUN_USER")"
RUN_HOME="$(getent passwd "$RUN_USER" | cut -d: -f6)"
if [[ -z "$RUN_HOME" ]]; then
  echo "错误：找不到用户 $RUN_USER 的 home 目录" >&2
  exit 1
fi

# 把单元文件里的项目路径与运行用户替换为实际值（防止仓库被移动 / 换用户后失效）
sed -e "s|/home/pengfei-mini/Documents/GitHub/mediaeditor|$ROOT|g" \
    -e "s|^User=pengfei-mini$|User=$RUN_USER|" \
    -e "s|^Group=pengfei-mini$|Group=$RUN_GROUP|" \
    -e "s|^Environment=HOME=/home/pengfei-mini$|Environment=HOME=$RUN_HOME|" \
    "$SRC" > "$DEST"

# 之前若以 root 运行过，monitor-data 下会有 root 属主的文件，会导致新用户写不进去
if [[ -d "$ROOT/monitor-data" ]]; then
  chown -R "$RUN_USER:$RUN_GROUP" "$ROOT/monitor-data"
fi

systemctl daemon-reload
systemctl enable "$UNIT"
systemctl restart "$UNIT"

echo
echo "✅ 已安装并启动：$UNIT（运行用户：$RUN_USER）"
echo "   - 状态：  systemctl status $UNIT"
echo "   - 日志：  journalctl -u $UNIT -f"
echo "   - 停止：  sudo systemctl stop $UNIT"
echo "   - 卸载：  sudo systemctl disable --now $UNIT && sudo rm $DEST && sudo systemctl daemon-reload"
echo
echo "提示：启动播报用 aplay 直出 ALSA 设备；若用户不在 audio 组，语音会静默（不影响服务）。"
echo "      需要语音时执行一次：sudo usermod -aG audio $RUN_USER  （之后需重新登录）"
