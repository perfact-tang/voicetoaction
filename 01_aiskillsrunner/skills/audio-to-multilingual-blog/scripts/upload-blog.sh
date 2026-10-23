#!/usr/bin/env bash
#
# 调用一次 vibecodingjapan-upblog，上传 ~/Documents/tmpaiskill/<DocID>/ 下的 4 个文件。
#
# 用法:
#   upload-blog.sh <DocID> [传给 vibecodingjapan-upblog 的额外参数...]
#
# 环境变量（优先级从高到低）:
#   AISKILLS_FIREBASE_ACCOUNT  Firebase 服务账号 JSON 路径（aiskillsrunner --firebasekey 会设置它）
#   AISKILLS_BLOG_KEY      同上；全局默认值可用这个（直接运行本脚本时生效）
#   AISKILLS_USERID        作者 Firebase uid（aiskillsrunner --userid 会设置它）
#   AISKILLS_BLOG_UID      同上；全局默认值可用这个
#   AISKILLS_BLOG_TMPDIR   临时根目录，默认 $HOME/Documents/tmpaiskill
#   AISKILLS_BLOG_CLI      vibecodingjapan-upblog 可执行文件（默认从 PATH 查找）
#   AISKILLS_BLOG_DRY_RUN  设为 1 时追加 --dry-run（不写入 Firebase）
#
# 退出码: 与 vibecodingjapan-upblog 一致（0=成功 1=输入错误 2=上传失败）

set -euo pipefail

usage() {
  cat >&2 <<'EOF'
用法: upload-blog.sh <DocID> [额外参数...]

  DocID   纯数字（Unix 毫秒），对应目录 $AISKILLS_BLOG_TMPDIR/<DocID>/
  额外参数 原样传给 vibecodingjapan-upblog（例如 --dry-run、--json、--private）
EOF
}

DOCID="${1:-}"
if [ -z "$DOCID" ]; then
  echo "✖ 缺少 DocID" >&2
  usage
  exit 2
fi
shift

case "$DOCID" in
  *[!0-9]*)
    echo "✖ DocID 必须是纯数字（Unix 毫秒），收到: $DOCID" >&2
    exit 2
    ;;
esac

TMP_ROOT="${AISKILLS_BLOG_TMPDIR:-$HOME/Documents/tmpaiskill}"
DOC_DIR="$TMP_ROOT/$DOCID"
# 优先级：aiskillsrunner 传参 > 全局环境变量 > 内置默认值
KEY="${AISKILLS_FIREBASE_ACCOUNT:-${AISKILLS_BLOG_KEY:-${AISKILLS_FIREBASE_KEY:-$HOME/Downloads/vibecodingjapan-firebase-adminsdk-fbsvc-f376a3b494.json}}}"
BLOG_UID="${AISKILLS_USERID:-${AISKILLS_BLOG_UID:-KYTF9y43qgc39vKn0sI3qWpsjRE2}}"

# 定位 vibecodingjapan-upblog：PATH 优先，其次 nvm 下已全局安装的版本。
UPBLOG="${AISKILLS_BLOG_CLI:-}"
if [ -z "$UPBLOG" ]; then
  if command -v vibecodingjapan-upblog >/dev/null 2>&1; then
    UPBLOG="$(command -v vibecodingjapan-upblog)"
  else
    UPBLOG="$(find "$HOME/.nvm/versions/node" -maxdepth 3 \( -type f -o -type l \) -name vibecodingjapan-upblog 2>/dev/null | sort | tail -n 1)"
  fi
fi
if [ -z "$UPBLOG" ]; then
  echo "✖ 找不到 vibecodingjapan-upblog。请先安装：" >&2
  echo "    cd <vibecodingjapan>/09_cli_upblog_tool && npm install" >&2
  echo "    cd <vibecodingjapan> && npm i -g ./09_cli_upblog_tool" >&2
  exit 1
fi

missing=0
for name in 日文.md 中文.md 英文.md 文章信息.json; do
  if [ ! -s "$DOC_DIR/$name" ]; then
    echo "✖ 缺失或为空: $DOC_DIR/$name" >&2
    missing=1
  fi
done
if [ "$missing" -ne 0 ]; then
  echo "✖ 上传前检查未通过，未调用 vibecodingjapan-upblog。" >&2
  exit 1
fi

EXTRA=()
if [ "${AISKILLS_BLOG_DRY_RUN:-}" = "1" ]; then
  EXTRA+=(--dry-run)
fi
if [ "$#" -gt 0 ]; then
  EXTRA+=("$@")
fi

echo "→ DocID   : $DOCID"
echo "→ 目录    : $DOC_DIR"
echo "→ 密钥    : $KEY"
echo "→ 作者 uid: $BLOG_UID"
echo "→ CLI     : $UPBLOG"
if [ "${#EXTRA[@]}" -gt 0 ]; then
  echo "→ 额外参数: ${EXTRA[*]}"
fi
echo

if [ ! -f "$KEY" ]; then
  echo "✖ 找不到 Firebase 密钥文件: $KEY" >&2
  echo "  可用 AISKILLS_BLOG_KEY 指定其它路径。" >&2
  exit 1
fi

# ispublicsiteshow 必须为 false：站点把“缺这个字段”当成展示，只有显式 false 才不展示。
# 这里不依赖文章信息 JSON 写得对不对：上传前统一生成一份强制 false 的临时副本。
UPLOAD_INFO="$DOC_DIR/.文章信息.upload.json"
if ! node -e '
const fs = require("fs");
const [src, out] = process.argv.slice(1);
const json = JSON.parse(fs.readFileSync(src, "utf8"));
json.ispublicsiteshow = false;
fs.writeFileSync(out, JSON.stringify(json, null, 2));
' "$DOC_DIR/文章信息.json" "$UPLOAD_INFO"; then
  echo "✖ 无法生成上传用文章信息 JSON（ispublicsiteshow 强制 false 失败）" >&2
  exit 1
fi
trap 'rm -f "$UPLOAD_INFO"' EXIT
echo "→ 公开设置: ispublicsiteshow=false（已强制）"
echo

# 只调用一次。
STATUS=0
"$UPBLOG" \
  --ja "$DOC_DIR/日文.md" \
  --zh "$DOC_DIR/中文.md" \
  --en "$DOC_DIR/英文.md" \
  --info "$UPLOAD_INFO" \
  --key "$KEY" \
  --uid "$BLOG_UID" \
  --docid "$DOCID" \
  ${EXTRA[@]+"${EXTRA[@]}"} || STATUS=$?
exit "$STATUS"
