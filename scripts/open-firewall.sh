#!/usr/bin/env bash
# ============================================================
#  VoiceToAction AI Monitor — 开放防火墙端口（局域网访问）
#
#  用法（需要 sudo，仅首次执行一次）：
#    sudo ./scripts/open-firewall.sh
#    （或手动：sudo ufw allow 15888/tcp）
# ============================================================
set -euo pipefail

PORT="${PORT:-15888}"

echo "开放 UFW 端口 TCP ${PORT}..."
sudo ufw allow "${PORT}"/tcp
echo
echo "当前防火墙规则（含 ${PORT} 的行）："
sudo ufw status | grep "${PORT}" || true
echo
echo "✅ 完成。同局域网其他电脑可通过 http://<本机IP>:${PORT} 访问。"
