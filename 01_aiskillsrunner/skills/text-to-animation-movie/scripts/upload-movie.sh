#!/usr/bin/env bash
#
# Upload one rendered movie to Firebase Storage, publish it as a note under
# /users/<userid>/notes/<noteid>, and send an FCM notification.
#
# 用法:
#   upload-movie.sh <noteid> <movie.mp4> [--note note.json] [额外参数...]
#
# 环境变量（优先级从高到低）:
#   AISKILLS_FIREBASE_ACCOUNT  Firebase 服务账号 JSON 路径（aiskillsrunner --firebasekey 会设置它）
#   AISKILLS_MOVIE_KEY         同上；直接运行本脚本时的全局默认值
#   AISKILLS_USERID            作者 Firebase uid（aiskillsrunner --userid 会设置它）
#   AISKILLS_MOVIE_UID         同上；直接运行时的全局默认值
#   AISKILLS_MOVIE_DRY_RUN     设为 1 时只校验并打印，不写 Firebase
#   AISKILLS_FCM_TOPIC         FCM topic（未设置且未给 token 时跳过通知）
#
# 退出码: 0=成功 1=输入错误 2=上传/写入失败

set -euo pipefail

usage() {
  cat >&2 <<'EOF'
用法: upload-movie.sh <noteid> <movie.mp4> [--note note.json] [额外参数...]

  noteid     目标笔记文档 id，写入 /users/<uid>/notes/<noteid>
  movie.mp4  渲染好的视频文件
  --note     笔记 JSON: { title, body, color, hidden, tags }
  额外参数   原样传给 upload-movie.js（例如 --dry-run、--public、--no-notify）

示例:
  upload-movie.sh 1759700000000 out/movie.mp4 --note 动画说明.json
  AISKILLS_MOVIE_DRY_RUN=1 upload-movie.sh 1 out/movie.mp4 --note 动画说明.json
EOF
}

NOTEID="${1:-}"
MOVIE="${2:-}"
if [ -z "$NOTEID" ] || [ -z "$MOVIE" ]; then
  echo "✖ 缺少参数" >&2
  usage
  exit 1
fi
shift 2

if [ ! -s "$MOVIE" ]; then
  echo "✖ 找不到或为空: $MOVIE" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPLOADER="$SCRIPT_DIR/upload-movie.js"
if [ ! -f "$UPLOADER" ]; then
  echo "✖ 找不到 upload-movie.js: $UPLOADER" >&2
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "✖ 找不到 node" >&2
  exit 1
fi

if [ ! -d "$SCRIPT_DIR/node_modules/firebase-admin" ]; then
  echo "✖ 缺少依赖 firebase-admin，请先安装：" >&2
  echo "    cd \"$SCRIPT_DIR\" && npm install --omit=dev" >&2
  exit 1
fi

KEY="${AISKILLS_FIREBASE_ACCOUNT:-${AISKILLS_MOVIE_KEY:-$HOME/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json}}"
UID_VALUE="${AISKILLS_USERID:-${AISKILLS_MOVIE_UID:-}}"

EXTRA=()
if [ "${AISKILLS_MOVIE_DRY_RUN:-}" = "1" ]; then
  EXTRA+=(--dry-run)
fi
if [ -n "${AISKILLS_FCM_TOPIC:-}" ]; then
  EXTRA+=(--notify-topic "$AISKILLS_FCM_TOPIC")
fi
if [ "$#" -gt 0 ]; then
  EXTRA+=("$@")
fi

echo "→ noteid  : $NOTEID"
echo "→ 视频    : $MOVIE"
echo "→ 密钥    : $KEY"
[ -n "$UID_VALUE" ] && echo "→ 作者 uid: $UID_VALUE"
echo

exec node "$UPLOADER" \
  --file "$MOVIE" \
  --noteid "$NOTEID" \
  --key "$KEY" \
  ${UID_VALUE:+--userid "$UID_VALUE"} \
  ${EXTRA[@]+"${EXTRA[@]}"}
