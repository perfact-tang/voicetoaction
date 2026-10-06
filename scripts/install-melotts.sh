#!/usr/bin/env bash
# 转发脚本：真正的实现在 skill 里，跟着 skill 包一起分发/更新。
#   bash scripts/install-melotts.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec bash "$ROOT/01_aiskillsrunner/skills/text-to-animation-movie/scripts/install-melotts.sh" "$@"
