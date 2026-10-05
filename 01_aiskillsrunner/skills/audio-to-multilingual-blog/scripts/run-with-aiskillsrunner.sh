#!/usr/bin/env bash
#
# 便捷入口：用正确的沙箱权限调用 aiskillsrunner 执行本 skill。
#
# 因为本 skill 要写工作区之外的 ~/Documents/tmpaiskill，并读取 ~/Downloads 下的
# Firebase 密钥，headless 运行需要 danger-full-access；这个脚本负责把该环境变量设好。
#
# 用法（与 aiskillsrunner 完全一致，会自动补上 --skill）:
#   run-with-aiskillsrunner.sh --prompt "<STT文本>"
#   run-with-aiskillsrunner.sh --reffilepath ./stt.txt
#   run-with-aiskillsrunner.sh --prompt "DocID: 1758000000000" --reffilepath ./stt.txt
#
# 已有 DSH_PERMISSION_MODE 时不会被覆盖。

set -euo pipefail

SKILL_NAME="audio-to-multilingual-blog"

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
