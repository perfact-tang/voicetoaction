#!/usr/bin/env bash
#
# 安装 MeloTTS（中文配音），**不需要 sudo**。
#
# 为什么优先用 uv 管理的 Python，而不是系统 python3：
#   Ubuntu 26.04 升级时把 python3.12 从系统里删掉了，/usr/bin/python3 变成 3.14，
#   于是 venv（包在 lib/python3.12/ 下）当场失效 —— 2026-10-07 就是这么坏的。
#   uv 装的 Python 放在 ~/.local/share/uv/python/ 下，跟系统升级解耦。
#   没有 uv 时才回退「系统 python3 -m venv --without-pip + get-pip.py」。
#
# 装到：   $HOME/melotts-venv     （MELOTTS_VENV 覆盖）
# Python： 3.12                   （MELOTTS_PY_VERSION 覆盖；3.13/3.14 上 numpy<2 没有 wheel）
# 用法：   bash scripts/install-melotts.sh            # 安装/修复
#          bash scripts/install-melotts.sh --check    # 只体检，不改任何东西
#
set -uo pipefail

VENV="${MELOTTS_VENV:-$HOME/melotts-venv}"
PY_VERSION="${MELOTTS_PY_VERSION:-3.12}"
PY="$VENV/bin/python"
UV="${UV:-$(command -v uv 2>/dev/null || true)}"
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

log() { echo "[$(date '+%H:%M:%S')] $*"; }
die() { log "✖ $*"; exit 1; }
have_uv() { [ -n "$UV" ] && [ -x "$UV" ]; }

# uv 有就用 uv pip（快、自带解析），否则用 venv 里的 pip
pip_install() {
  if have_uv; then
    "$UV" pip install --python "$PY" "$@"
  else
    "$PY" -m pip install --no-input "$@"
  fi
}

# ---- 0. 体检模式 ---------------------------------------------------------
if [ "$CHECK_ONLY" = "1" ]; then
  echo "venv        : $VENV"
  echo "期望 Python : $PY_VERSION"
  if [ ! -x "$PY" ]; then
    echo "状态        : ✖ venv 不存在"
    exit 1
  fi
  CUR="$("$PY" -c 'import sys;print("%d.%d"%sys.version_info[:2])' 2>/dev/null || echo '?')"
  echo "实际 Python : $CUR  ($("$PY" -c 'import sys;print(sys.executable)' 2>/dev/null))"
  if [ "$CUR" != "$PY_VERSION" ]; then
    echo "状态        : ✖ 解释器版本不匹配（系统 python 被升级过？）→ 跑一次不带 --check 的本脚本即可重建"
    exit 1
  fi
  if "$PY" -c "import melo" 2>/dev/null; then
    echo "melo        : ✓ $("$PY" -c 'import melo,os;print(os.path.dirname(melo.__file__))')"
    echo "torch       : $("$PY" -c 'import torch;print(torch.__version__)')"
    echo "状态        : ✓ 可用"
    exit 0
  fi
  echo "状态        : ✖ 解释器对但 import melo 失败 → 跑一次不带 --check 的本脚本重装"
  exit 1
fi

log "venv 目标：$VENV（Python $PY_VERSION）"
mkdir -p "$(dirname "$VENV")"

# ---- 1. 解释器自检：版本不对就重建 --------------------------------------
# venv 的 bin/python3 通常是指向系统解释器的符号链接，系统 python 一升级
# （3.12 → 3.14）链接就指到新版本，而包还在 lib/python3.12/ 下，全部 import 失败。
if [ -x "$PY" ]; then
  CURRENT="$("$PY" -c 'import sys;print("%d.%d"%sys.version_info[:2])' 2>/dev/null || echo '?')"
  if [ "$CURRENT" != "$PY_VERSION" ]; then
    log "⚠ venv 里的解释器是 Python $CURRENT，期望 $PY_VERSION（系统 python 被升级过）"
    log "  删除并重建：$VENV"
    rm -rf "$VENV"
  fi
fi

# ---- 2. 建 venv ----------------------------------------------------------
if [ ! -x "$PY" ]; then
  if have_uv; then
    log "用 uv 准备 Python $PY_VERSION（与系统 python 解耦，升级打不坏）"
    "$UV" python install "$PY_VERSION" >/dev/null 2>&1 || die "uv python install 失败"
    "$UV" venv --python "$PY_VERSION" "$VENV" || die "uv venv 失败"
  else
    log "没有 uv，回退系统 python3 -m venv --without-pip"
    python3 -m venv --without-pip "$VENV" || die "venv 创建失败"
    if ! "$PY" -m pip --version >/dev/null 2>&1; then
      log "给 venv 引导 pip（get-pip.py）"
      curl -fsSL https://bootstrap.pypa.io/get-pip.py -o /tmp/get-pip.py || die "下载 get-pip.py 失败"
      "$PY" /tmp/get-pip.py || die "pip 引导失败"
    fi
  fi
fi
log "解释器：$("$PY" -c 'import sys;print(sys.version.split()[0], sys.executable)')"

# ---- 3. torch（CPU 版，避免拉 2.5GB 的 CUDA wheel）----------------------
if ! "$PY" -c "import torch" >/dev/null 2>&1; then
  log "安装 torch + torchaudio（CPU 版）"
  pip_install torch torchaudio --index-url https://download.pytorch.org/whl/cpu \
    || die "torch 安装失败"
fi
log "torch: $("$PY" -c 'import torch;print(torch.__version__)')"

# ---- 4. MeloTTS 运行依赖 -------------------------------------------------
# 注意：不照抄上游 requirements.txt —— 它把 transformers 钉在 4.27.4（依赖
# tokenizers 0.13.x），在 Python 3.12 上没有 wheel、要现编 Rust，装不上。
# 这里放开这个钉；非中文语言（日/韩/德/西/法）装不上只告警。
pip_install "numpy<2" scipy soundfile librosa tqdm loguru \
  cached_path "transformers<5" num2words inflect unidecode \
  || die "基础依赖安装失败"

pip_install jieba pypinyin cn2an langid txtsplit pydub \
  || die "中文文本前端依赖安装失败"

pip_install unidic_lite unidic mecab-python3 pykakasi fugashi \
  || log "⚠ 日语依赖安装失败（中文配音不受影响）"

pip_install anyascii g2p_en eng_to_ipa jamo "gruut[de,es,fr]" g2pkk \
  || log "⚠ 其他语言依赖安装失败（中文配音不受影响）"

# ---- 5. MeloTTS 本体（--no-deps，依赖已在上面装好）----------------------
if ! "$PY" -c "import melo" >/dev/null 2>&1; then
  log "安装 melotts（git，--no-deps）"
  pip_install --no-deps "git+https://github.com/myshell-ai/MeloTTS.git" \
    || die "melotts 安装失败"
fi
log "melo: $("$PY" -c 'import melo,os;print(os.path.dirname(melo.__file__))')"

# ---- 6. 数据文件：unidic 词典 + nltk 语料 ---------------------------------
# 上游 setup.py 的 post-install 会调 `python -m unidic download`，但那句用的是
# 裸 `python`（本机没有这个命令），而且每次重跑会白下 526MB，所以这里自己控制。
UNIDIC_DIR="$("$PY" -c 'import unidic,os;print(os.path.join(os.path.dirname(unidic.__file__),"dicdir"))' 2>/dev/null)"
if [ -n "$UNIDIC_DIR" ] && [ -d "$UNIDIC_DIR" ]; then
  log "unidic 词典已存在，跳过"
else
  log "unidic download（约 526MB；失败不致命，只影响日语）"
  "$PY" -m unidic download || log "⚠ unidic download 失败"
fi

# g2p_en 的英文词性标注要这些语料；新版 nltk 用的是 *_eng 后缀那个名字。
log "nltk 语料（g2p_en 的英文 POS 标注用）"
"$PY" -m nltk.downloader -q averaged_perceptron_tagger_eng averaged_perceptron_tagger \
  cmudict punkt 2>/dev/null || log "⚠ nltk 语料下载失败（中文台词若含英文单词会报错）"

# ---- 7. 冒烟测试：中文合成 + 静音检查 ------------------------------------
# 只测「有没有声音」不够：MeloTTS 偶发会输出**长度正确但全是 0** 的波形，
# 所以这里同时检查峰值电平（正式流程里由 melo_tts.py 的同一套逻辑兜底）。
log "冒烟测试：加载 ZH 模型并合成一句话（首次会从 HuggingFace 下模型，耐心等）"
"$PY" - <<'PYEOF' || die "冒烟测试失败"
import os, time
import numpy as np
import soundfile as sf

t0 = time.time()
from melo.api import TTS
model = TTS(language="ZH", device="cpu")
print(f"  ZH 模型加载完成：{time.time()-t0:.1f}s")

out = "/tmp/melotts-smoke-zh.wav"
model.tts_to_file(
    "这是一段中文配音测试，用来确认 MeloTTS 可以正常工作。",
    speaker_id=0, output_path=out, speed=1.0, quiet=True,
)
audio, sr = sf.read(out, dtype="float32")
peak = float(np.abs(audio).max()) if audio.size else 0.0
print(f"  已写出 {out}  ({os.path.getsize(out)/1024:.0f} KB, {len(audio)/sr:.2f}s, 峰值 {peak:.4f})")
if peak < 1e-4:
    raise SystemExit(f"✖ 合成结果是静音（峰值 {peak:.6f}）")
PYEOF

if command -v ffprobe >/dev/null 2>&1; then
  log "试听文件时长：$(ffprobe -v error -show_entries format=duration -of csv=p=0 /tmp/melotts-smoke-zh.wav)s"
fi

log "✅ MeloTTS 安装完成"
log "   venv   : $VENV"
log "   python : $PY"
log "   试听   : /tmp/melotts-smoke-zh.wav"
log "   体检   : bash scripts/install-melotts.sh --check"
