#!/usr/bin/env bash
# ============================================================
#  VoiceToAction AI Monitor — 幂等启动脚本
#
#  供 systemd / GNOME 自启 / cron 调用。已运行则直接退出，
#  避免重复启动导致端口冲突。
#
#  用法：
#    scripts/start-monitor.sh
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NODE_BIN="${NODE_BIN:-$(command -v node || echo "$HOME/.nvm/versions/node/v24.15.0/bin/node")}"
PORT="${PORT:-15888}"
PIDFILE="$ROOT/monitor-data/server.pid"

mkdir -p "$ROOT/monitor-data"

alive() {
  local pid="${1:-}"
  [[ -n "$pid" ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  # 确认该进程的 cwd 是本项目（防止 PID 复用误判）
  [[ "$(readlink "/proc/$pid/cwd" 2>/dev/null)" == "$ROOT" ]] || return 1
  return 0
}

if alive "$(cat "$PIDFILE" 2>/dev/null)"; then
  echo "VoiceToAction Monitor 已在运行 (pid $(cat "$PIDFILE"))，跳过启动。"
  exit 0
fi

# 二次保险：健康检查通过说明已有实例在跑（例如手动启动、未写 pidfile）
if curl -sf -m 2 "http://127.0.0.1:${PORT}/api/health" >/dev/null 2>&1; then
  echo "VoiceToAction Monitor 已在运行（端口 ${PORT} 健康检查通过），跳过启动。"
  exit 0
fi

echo "启动 VoiceToAction Monitor..."
cd "$ROOT"

cleanup() {
  rm -f "$PIDFILE"
}
trap cleanup EXIT TERM INT

echo $$ > "$PIDFILE"
exec "$NODE_BIN" server.js
