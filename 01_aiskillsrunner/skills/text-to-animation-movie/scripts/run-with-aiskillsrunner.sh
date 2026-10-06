#!/usr/bin/env bash
#
# 便捷入口：用正确的沙箱权限调用 aiskillsrunner 执行本 skill。
#
# 本 skill 需要：
#   - 在 ~/Documents/tmpaiskill 下写工作目录（工作区之外）
#   - 读取 ~/Downloads 下的 Firebase 密钥
#   - 在 skill 目录里 npm install / 下载 Headless Chrome
# 因此 headless 运行需要 danger-full-access；这个脚本负责设置该环境变量。
#
# 用法（与 aiskillsrunner 完全一致，会自动补上 --skill）:
#   run-with-aiskillsrunner.sh --prompt "<文本>"
#   run-with-aiskillsrunner.sh --reffilepath ./story.txt
#   run-with-aiskillsrunner.sh --prompt "DocID: 1758000000000" --reffilepath ./story.txt
#
# 已有 DSH_PERMISSION_MODE 时不会被覆盖。

set -euo pipefail

SKILL_NAME="text-to-animation-movie"

export DSH_PERMISSION_MODE="${DSH_PERMISSION_MODE:-danger-full-access}"

RUNNER="${AISKILLSRUNNER_BIN:-}"
if [ -z "$RUNNER" ]; then
  if command -v aiskillsrunner >/dev/null 2>&1; then
    RUNNER="$(command -v aiskillsrunner)"
  else
    RUNNER="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)/aiskillsrunner.js"
  fi
fi

if [ ! -e "$RUNNER" ] && ! command -v "$RUNNER" >/dev/null 2>&1; then
  echo "✖ 找不到 aiskillsrunner（可用 AISKILLSRUNNER_BIN 指定路径）" >&2
  exit 127
fi

echo "→ DSH_PERMISSION_MODE=$DSH_PERMISSION_MODE" >&2
case "$RUNNER" in
  *.js) exec node "$RUNNER" --skill "$SKILL_NAME" "$@" ;;
  *)    exec "$RUNNER" --skill "$SKILL_NAME" "$@" ;;
esac
