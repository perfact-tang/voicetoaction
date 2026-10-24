#!/usr/bin/env bash
#
# One entry point for every Remotion render in this project, so the tuned
# flags can never drift between scenes/stills/docs.
#
#   bash scripts/render.sh movie [out/movie.mp4]
#   bash scripts/render.sh still <frame> [check/S01.png]
#
# Env overrides:
#   GL=angle|swangle|angle-egl  Chromium GL backend (default: angle)
#   CONCURRENCY=<n>             parallel browser tabs  (default: min(4, cores))
#   FRAMES=<start-end>          render a slice, e.g. FRAMES=900-1019
#   CRF=<n>                     x264 quality (default: 18)
#
# Why these defaults: the composition is SVG-filter heavy, so a single frame
# already keeps every core busy and more than ~4 tabs gives no speedup. `angle`
# is ~1.7x faster than `swangle` here and renders identically. `egl` and
# `swiftshader` are NOT safe on GPU-less Linux: they drop filtered strokes.
set -euo pipefail

cd "$(dirname "$0")/.."

MODE="${1:-movie}"
GL="${GL:-angle}"
CRF="${CRF:-18}"
FRAMES="${FRAMES:-}"

if ! command -v npx >/dev/null 2>&1; then
  echo "render.sh: npx not found (Node 18+ required)" >&2
  exit 127
fi

CORES="$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
DEFAULT_CONCURRENCY=$(( CORES < 4 ? (CORES < 2 ? 1 : 2) : 4 ))
CONCURRENCY="${CONCURRENCY:-$DEFAULT_CONCURRENCY}"

# The headless browser is downloaded once per node_modules (about 100MB).
npx remotion browser ensure >/dev/null 2>&1 || true

started=$(date +%s)

case "$MODE" in
  movie)
    OUT="${2:-out/movie.mp4}"
    mkdir -p "$(dirname "$OUT")"
    args=(
      render src/index.ts Explainer "$OUT"
      --codec=h264
      --crf="$CRF"
      --pixel-format=yuv420p
      --x264-preset=fast
      --concurrency="$CONCURRENCY"
      --gl="$GL"
    )
    if [[ -n "$FRAMES" ]]; then
      args+=(--frames="$FRAMES")
    fi
    echo "render.sh: movie  gl=$GL concurrency=$CONCURRENCY crf=$CRF ${FRAMES:+frames=$FRAMES}"
    npx remotion "${args[@]}"
    ;;
  still)
    FRAME="${2:-}"
    if [[ -z "$FRAME" ]]; then
      echo "usage: bash scripts/render.sh still <frame> [out.png]" >&2
      exit 2
    fi
    OUT="${3:-check/frame_$FRAME.png}"
    mkdir -p "$(dirname "$OUT")"
    echo "render.sh: still  frame=$FRAME gl=$GL"
    npx remotion still src/index.ts Explainer "$OUT" --frame="$FRAME" --gl="$GL"
    ;;
  *)
    echo "usage: bash scripts/render.sh movie [out.mp4] | still <frame> [out.png]" >&2
    exit 2
    ;;
esac

echo "render.sh: done in $(( $(date +%s) - started ))s -> $OUT"
