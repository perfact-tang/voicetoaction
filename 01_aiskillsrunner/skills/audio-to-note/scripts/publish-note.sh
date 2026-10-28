#!/usr/bin/env bash
#
# 把整理后的语音笔记写入 Firestore 的 /users/<uid>/notes/<noteid>，并发送 FCM。
#
# 用法:
#   publish-note.sh <笔记.json> [选项...]
#
# 选项与 publish-note.mjs 一致（--noteid / --userid / --key / --language /
# --dry-run / --no-notify / --notify-topic / --notify-token / --result / --json）。
#
# 环境变量（优先级从高到低）:
#   AISKILLS_FIREBASE_ACCOUNT  Firebase 服务账号 JSON 路径（aiskillsrunner --firebasekey 会设置）
#   AISKILLS_USERID            用户 uid（aiskillsrunner --userid 会设置）
#   AISKILLS_LANGUAGE          上传时选择的语言（zh / ja / en / ko / kr）
#   AISKILLS_NOTE_KEY          直接运行时的密钥路径（经 dsh 会被过滤）
#   AISKILLS_NOTE_UID          直接运行时的 uid
#   AISKILLS_NOTE_DRY_RUN      设为 1 时只校验，不写 Firebase
#   AISKILLS_NOTE_NOTIFY       设为 0 时不发 FCM
#   AISKILLS_FCM_TOPIC         没有设备 token 时的 topic 兜底
#
# 退出码: 0 成功（含 no-tokens） 1 参数/JSON 错误 2 认证/Firestore 失败

set -euo pipefail

usage() {
  cat >&2 <<'EOF'
用法: publish-note.sh <笔记.json> [选项...]

  <笔记.json>           Step1 产出的笔记 JSON（必填）
  --noteid <id>         Note 文档 id（默认 note.noteid / note.docid / 当前毫秒）
  --userid <uid>        用户 uid
  --key <path>          服务账号 JSON
  --language <code>     zh / ja / en / ko
  --dry-run             只校验并打印，不写 Firebase、不发通知
  --no-notify           写 Note，但不发 FCM
  --notify-topic <t>    topic 兜底
  --notify-token <t>    指定设备 token（可重复）
  --result <path>       结果 JSON 路径
  --json                结果 JSON 打到 stdout

示例:
  publish-note.sh ~/Documents/tmpaiskill/1759/笔记.json --noteid 1759

  AISKILLS_NOTE_DRY_RUN=1 publish-note.sh ~/Documents/tmpaiskill/1759/笔记.json
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

NOTE_FILE="${1:-}"
if [ -z "$NOTE_FILE" ]; then
  echo "✖ 缺少参数：笔记 JSON" >&2
  usage
  exit 1
fi
shift

if [ ! -s "$NOTE_FILE" ]; then
  echo "✖ 找不到或为空: $NOTE_FILE" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PUBLISHER="$SCRIPT_DIR/publish-note.mjs"
if [ ! -f "$PUBLISHER" ]; then
  echo "✖ 找不到 publish-note.mjs: $PUBLISHER" >&2
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "✖ 找不到 node（需要 Node 18+）" >&2
  exit 1
fi

has_arg() {
  local needle="$1"
  shift
  for arg in "$@"; do
    case "$arg" in
      "$needle"|"$needle"=*) return 0 ;;
    esac
  done
  return 1
}

arg_value() {
  local needle="$1"
  shift
  local prev=""
  for arg in "$@"; do
    if [ "$prev" = "$needle" ]; then
      printf '%s' "$arg"
      return 0
    fi
    case "$arg" in
      "$needle"=*) printf '%s' "${arg#*=}"; return 0 ;;
    esac
    prev="$arg"
  done
  return 1
}

KEY="${AISKILLS_FIREBASE_ACCOUNT:-${AISKILLS_NOTE_KEY:-${AISKILLS_MOVIE_KEY:-$HOME/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json}}}"
KEY="${KEY/#\~/$HOME}"
if ARG_KEY="$(arg_value --key "$@")"; then
  KEY="$ARG_KEY"
fi

UID_VALUE="${AISKILLS_USERID:-${AISKILLS_NOTE_UID:-${AISKILLS_MOVIE_UID:-}}}"
if ARG_UID="$(arg_value --userid "$@")"; then
  UID_VALUE="$ARG_UID"
fi

DRY_RUN="${AISKILLS_NOTE_DRY_RUN:-0}"
if has_arg --dry-run "$@"; then
  DRY_RUN=1
fi

EXTRA=()
# 环境变量默认值；后面用户显式传入的参数会覆盖（mjs 的解析是后者优先）
if [ -n "$UID_VALUE" ]; then
  EXTRA+=(--userid "$UID_VALUE")
fi
if [ -n "${AISKILLS_LANGUAGE:-}" ] && ! has_arg --language "$@"; then
  EXTRA+=(--language "$AISKILLS_LANGUAGE")
fi
if [ "$DRY_RUN" = "1" ] || [ "$DRY_RUN" = "true" ]; then
  EXTRA+=(--dry-run)
fi
if [ "${AISKILLS_NOTE_NOTIFY:-}" = "0" ] && ! has_arg --no-notify "$@"; then
  EXTRA+=(--no-notify)
fi
if [ -n "${AISKILLS_FCM_TOPIC:-}" ] \
  && ! has_arg --notify-topic "$@" \
  && ! has_arg --no-notify "$@"; then
  EXTRA+=(--notify-topic "$AISKILLS_FCM_TOPIC")
fi

if [ "$DRY_RUN" != "1" ] && [ "$DRY_RUN" != "true" ]; then
  if [ ! -s "$KEY" ]; then
    echo "✖ 找不到 Firebase 服务账号密钥: $KEY" >&2
    echo "  用 --key 或 AISKILLS_FIREBASE_ACCOUNT 指定。" >&2
    exit 2
  fi
fi

echo "→ 笔记      : $NOTE_FILE" >&2
echo "→ 密钥      : $KEY" >&2
if [ -n "$UID_VALUE" ]; then echo "→ 用户 uid  : $UID_VALUE" >&2; fi
if [ -n "${AISKILLS_LANGUAGE:-}" ]; then echo "→ 语言      : $AISKILLS_LANGUAGE" >&2; fi
if [ "$DRY_RUN" = "1" ] || [ "$DRY_RUN" = "true" ]; then echo "→ 模式      : dry-run" >&2; fi
echo >&2

exec node "$PUBLISHER" \
  --file "$NOTE_FILE" \
  --key "$KEY" \
  ${EXTRA[@]+"${EXTRA[@]}"} \
  "$@"
