#!/usr/bin/env bash
# ============================================================
#  MediaSplitter AI — Ubuntu 一键安装并启动脚本
#
#  用法：
#    ./run-ubuntu.sh            # 安装依赖并启动（可重复执行，已装好的会跳过）
#    ./run-ubuntu.sh --no-install   # 只启动，不检查/安装任何东西
#
#  启动后浏览器打开: http://localhost:5173  （Ctrl+C 停止）
#  环境变量：PORT（默认 5173）、HOST（默认 127.0.0.1）
# ============================================================
set -euo pipefail

cd "$(dirname "$0")"

log()  { printf '\033[1;32m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
warn() { printf '\033[1;33m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
die()  { printf '\033[1;31m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

SKIP_INSTALL=0
if [[ "${1:-}" == "--no-install" ]]; then
  SKIP_INSTALL=1
fi

# 无 sudo 时用 get-pip 引导 .venv 的备用路径（仅安装 python 后端时需要）
VENV_BOOTSTRAP_WITHOUT_SUDO=0

# ------------------------------------------------------------
# 1. Node.js >= 20
# ------------------------------------------------------------
node_major() {
  command -v node >/dev/null 2>&1 || return 1
  node -p "process.versions.node.split('.')[0]" 2>/dev/null || return 1
}

if [[ $SKIP_INSTALL -eq 0 ]]; then
  if major="$(node_major)" && [[ "$major" -ge 20 ]]; then
    log "Node.js 已就绪: $(node --version)"
  else
    log "未检测到 Node.js >= 20，开始安装..."
    if [[ -s "$HOME/.nvm/nvm.sh" ]]; then
      # shellcheck disable=SC1091
      . "$HOME/.nvm/nvm.sh"
      nvm install 22 >/dev/null
      nvm use 22 >/dev/null
    elif command -v sudo >/dev/null 2>&1; then
      sudo apt-get update -y
      sudo apt-get install -y ca-certificates curl gnupg
      curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
      sudo apt-get install -y nodejs
    else
      log "未找到 sudo，改用 nvm 安装 Node.js（仅当前用户）..."
      curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
      # shellcheck disable=SC1091
      . "$HOME/.nvm/nvm.sh"
      nvm install 22 >/dev/null
      nvm use 22 >/dev/null
    fi
    major="$(node_major)" || die "Node.js 安装失败，请手动安装 Node.js >= 20 后重试。"
    [[ "$major" -ge 20 ]] || die "Node.js 版本过低（$(node --version)），请安装 >= 20。"
    log "Node.js 已就绪: $(node --version)"
  fi

  # ------------------------------------------------------------
  # 2. FFmpeg
  # ------------------------------------------------------------
  if command -v ffmpeg >/dev/null 2>&1; then
    log "FFmpeg 已就绪: $(ffmpeg -version 2>/dev/null | head -1 | awk '{print $3}')"
  else
    log "未检测到 FFmpeg，开始安装..."
    command -v sudo >/dev/null 2>&1 || die "需要 sudo 安装 FFmpeg，请先手动运行: sudo apt-get install -y ffmpeg"
    sudo apt-get update -y
    sudo apt-get install -y ffmpeg
    log "FFmpeg 已就绪: $(ffmpeg -version 2>/dev/null | head -1 | awk '{print $3}')"
  fi

  # ------------------------------------------------------------
  # 3. Python 3
  # ------------------------------------------------------------
  command -v python3 >/dev/null 2>&1 || die "未找到 python3，请先安装: sudo apt-get install -y python3"
  log "Python 已就绪: $(python3 --version 2>&1)"

  # ------------------------------------------------------------
  # 4. npm install
  # ------------------------------------------------------------
  if [[ -d node_modules ]]; then
    log "node_modules 已存在，跳过 npm install。"
  else
    log "安装项目依赖 (npm install)..."
    npm install
  fi

  # ------------------------------------------------------------
  # 5. faster-whisper 转录后端
  # ------------------------------------------------------------
  if [[ -x .venv/bin/python ]] && .venv/bin/python -c "import faster_whisper" >/dev/null 2>&1; then
    log "faster-whisper 后端已就绪。"
  else
    # 需要创建/修复 .venv：先保证 python3 能创建带 pip 的虚拟环境
    if ! python3 -c "import ensurepip" >/dev/null 2>&1; then
      if command -v sudo >/dev/null 2>&1 && { sudo -n true 2>/dev/null || sudo -v 2>/dev/null; }; then
        log "安装 python3-venv..."
        sudo apt-get update -y >/dev/null 2>&1 || true
        sudo apt-get install -y python3-venv >/dev/null
      else
        warn "python3-venv 缺失且无法通过 sudo 安装（无交互终端或无权限），改用 get-pip 引导 .venv（无需 sudo）。"
        VENV_BOOTSTRAP_WITHOUT_SUDO=1
      fi
    fi
    if [[ $VENV_BOOTSTRAP_WITHOUT_SUDO -eq 1 ]] && [[ ! -x .venv/bin/python ]]; then
      log "创建 .venv 并引导 pip..."
      python3 -m venv --without-pip .venv
      curl -sS https://bootstrap.pypa.io/get-pip.py -o /tmp/mediasplitter-get-pip.py
      .venv/bin/python /tmp/mediasplitter-get-pip.py
      rm -f /tmp/mediasplitter-get-pip.py
    fi
    log "安装 faster-whisper 转录后端..."
    npm run setup:faster-whisper
  fi
else
  log "--no-install 模式：跳过所有安装检查。"
fi

# ------------------------------------------------------------
# 6. 启动（自动监听模式：Firebase Admin 连接后即开始监听）
# ------------------------------------------------------------
log "启动服务: http://${HOST:-127.0.0.1}:${PORT:-5173}  （Ctrl+C 停止）"
if [[ ! -f .env ]]; then
  warn "未找到 .env：请复制 .env.example 为 .env 并填写 FIREBASE_ADMIN_KEY_PATH / GOOGLE_OAUTH_CLIENT_PATH / MONITOR_USER_IDS 后重启。"
fi
exec node server.js
