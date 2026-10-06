#!/usr/bin/env bash
#
# 补跑被 SKILLS_RUNNER_TIMEOUT_MS 中途杀掉的 text-to-animation-movie：
# 用已经做完的 engine 目录（Step1–Step4 的分镜/台词/配音都在）直接渲染 → 校验 → 上传。
#
# 用法:
#   bash scripts/finish-movie-render.sh <DocID> [uid]
#
# 例:
#   bash scripts/finish-movie-render.sh 1791249759130
#
# 环境变量:
#   CONCURRENCY   Remotion 渲染并发（默认 8）
#   DRY_RUN=1     只渲染 + 校验，不上传 Firebase
#
set -euo pipefail

DOCID="${1:-}"
UID_ARG="${2:-KYTF9y43qgc39vKn0sI3qWpsjRE2}"
if [ -z "$DOCID" ]; then
  echo "用法: bash scripts/finish-movie-render.sh <DocID> [uid]" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOC_DIR="$HOME/Documents/tmpaiskill/$DOCID"
SKILL_DIR="$ROOT/monitor-data/skills/text-to-animation-movie"
UPLOADER="$SKILL_DIR/scripts/upload-movie.sh"
KEY_PATH="$ROOT/keys/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json"
CONCURRENCY="${CONCURRENCY:-8}"

for f in "$DOC_DIR/engine/src/index.ts" "$DOC_DIR/engine/public/narration.wav" \
         "$DOC_DIR/动画说明.json" "$UPLOADER" "$KEY_PATH"; do
  [ -e "$f" ] || { echo "✖ 缺少前置文件：$f" >&2; exit 1; }
done

echo "=== 1/5 渲染（DocID=$DOCID，concurrency=$CONCURRENCY）==="
cd "$DOC_DIR/engine"
npx remotion render src/index.ts Explainer out/movie.mp4 \
  --codec=h264 --crf=17 --pixel-format=yuv420p --concurrency="$CONCURRENCY"

echo "=== 2/5 复制成品到 $DOC_DIR ==="
cp out/movie.mp4 "$DOC_DIR/movie.mp4"
[ -f out/Explainer.lrc ] && cp out/Explainer.lrc "$DOC_DIR/台词.lrc" || true
ls -lh "$DOC_DIR/movie.mp4"

echo "=== 3/5 ffprobe 校验 ==="
ffprobe -v error -show_entries format=duration \
  -show_entries stream=codec_name,width,height,nb_frames \
  -of default=noprint_wrappers=1 "$DOC_DIR/movie.mp4"

if [ "${DRY_RUN:-}" = "1" ]; then
  echo "=== DRY_RUN=1：跳过上传 ==="
  exit 0
fi

echo "=== 4/5 取 FCM 设备令牌（与 monitor-notify.js 同一数据源）==="
TOKEN_ARGS=()
while IFS= read -r tok; do
  [ -n "$tok" ] && TOKEN_ARGS+=(--notify-token "$tok")
done < <(cd "$SKILL_DIR/scripts" && node -e "
const admin = require('firebase-admin');
admin.initializeApp({ credential: admin.credential.cert(require('$KEY_PATH')) });
admin.firestore().collection('users/$UID_ARG/fcmTokens').get()
  .then((s) => { s.docs.forEach((d) => { const t = String(d.get('token') || d.id || '').trim(); if (t) console.log(t); }); process.exit(0); })
  .catch((e) => { console.error('读取令牌失败: ' + e.message); process.exit(0); });
")
echo "→ 设备令牌数：${#TOKEN_ARGS[@]}"

echo "=== 5/5 上传 + 写 Firestore note + FCM ==="
AISKILLS_FIREBASE_ACCOUNT="$KEY_PATH" AISKILLS_USERID="$UID_ARG" \
  bash "$UPLOADER" "$DOCID" "$DOC_DIR/movie.mp4" \
    --note "$DOC_DIR/动画说明.json" "${TOKEN_ARGS[@]+"${TOKEN_ARGS[@]}"}"

echo "=== 完成：noteid=$DOCID ==="
