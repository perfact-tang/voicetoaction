#!/usr/bin/env bash
#
# 在**没有 sudo** 的 Ubuntu 上安装 MeloTTS（中文配音）。
#
# 为什么不用系统 python：Ubuntu 24.04 的 /usr/bin/python3.12 缺 pip 且受 PEP 668
# 保护，机器上也没有 python3-venv / python3-pip 包。所以这里
#   1) 用 python3 -m venv --without-pip 建独立 venv（venv 模块本身是有的）
#   2) 用 get-pip.py 给这个 venv 装 pip
#   3) 只在这个 venv 里装 torch(CPU) + MeloTTS，不碰系统环境
#
# 装到： $HOME/melotts-venv      （可用 MELOTTS_VENV 覆盖）
# 用法： bash scripts/install-melotts.sh
#
set -uo pipefail

VENV="${MELOTTS_VENV:-$HOME/melotts-venv}"
PY="$VENV/bin/python"
PIP="$VENV/bin/pip"

log() { echo "[$(date '+%H:%M:%S')] $*"; }
die() { log "✖ $*"; exit 1; }

log "venv 目标：$VENV"
mkdir -p "$(dirname "$VENV")"

# ---- 1. venv -------------------------------------------------------------
if [ ! -x "$PY" ]; then
  log "创建 venv（--without-pip）"
  python3 -m venv --without-pip "$VENV" || die "venv 创建失败"
else
  log "venv 已存在，复用"
fi

# ---- 2. bootstrap pip ----------------------------------------------------
if ! "$PY" -m pip --version >/dev/null 2>&1; then
  log "给 venv 引导 pip（get-pip.py）"
  curl -fsSL https://bootstrap.pypa.io/get-pip.py -o /tmp/get-pip.py || die "下载 get-pip.py 失败"
  "$PY" /tmp/get-pip.py || die "pip 引导失败"
fi
"$PY" -m pip install --upgrade pip setuptools wheel || die "升级 pip/setuptools/wheel 失败"
log "pip: $("$PY" -m pip --version)"

# ---- 3. torch（CPU 版，避免拉 2.5GB 的 CUDA wheel）----------------------
if ! "$PY" -c "import torch" >/dev/null 2>&1; then
  log "安装 torch + torchaudio（CPU 版）"
  "$PY" -m pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu \
    || die "torch 安装失败"
fi
log "torch: $("$PY" -c 'import torch;print(torch.__version__)')"

# ---- 4. MeloTTS 运行依赖 -------------------------------------------------
# 注意：不照抄上游 requirements.txt —— 它把 transformers 钉在 4.27.4（依赖
# tokenizers 0.13.x），在 Python 3.12 上没有 wheel、要现编 Rust，装不上。
# 这里放开这个钉，其余依赖按需安装；非中文语言（日/韩/德/西/法）装不上只告警。
PIPCOMMON=(-m pip install --no-input)

"$PY" "${PIPCOMMON[@]}" "numpy<2" scipy soundfile librosa tqdm loguru \
  cached_path "transformers<5" num2words inflect unidecode \
  || die "基础依赖安装失败"

"$PY" "${PIPCOMMON[@]}" jieba pypinyin cn2an langid txtsplit pydub \
  || die "中文文本前端依赖安装失败"

"$PY" "${PIPCOMMON[@]}" unidic_lite unidic mecab-python3 pykakasi fugashi \
  || log "⚠ 日语依赖安装失败（中文配音不受影响）"

"$PY" "${PIPCOMMON[@]}" anyascii g2p_en eng_to_ipa jamo "gruut[de,es,fr]" g2pkk \
  || log "⚠ 其他语言依赖安装失败（中文配音不受影响）"

# ---- 5. MeloTTS 本体（--no-deps，依赖已在上面装好）----------------------
if ! "$PY" -c "import melo" >/dev/null 2>&1; then
  log "安装 melotts（git，--no-deps）"
  "$PY" "${PIPCOMMON[@]}" --no-deps "git+https://github.com/myshell-ai/MeloTTS.git" \
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

# ---- 7. 冒烟测试：中文合成 ----------------------------------------------
log "冒烟测试：加载 ZH 模型并合成一句话（首次会从 HuggingFace 下模型，耐心等）"
"$PY" - <<'PYEOF' || die "冒烟测试失败"
import time, os
t0 = time.time()
from melo.api import TTS
model = TTS(language="ZH", device="cpu")
print(f"  ZH 模型加载完成：{time.time()-t0:.1f}s")
out = "/tmp/melotts-smoke-zh.wav"
model.tts_to_file(
    "这是一段中文配音测试，用来确认 MeloTTS 可以正常工作。",
    speaker_id=0, output_path=out, speed=1.0, quiet=True,
)
print(f"  已写出 {out}  ({os.path.getsize(out)/1024:.0f} KB, 用时 {time.time()-t0:.1f}s)")
PYEOF

if command -v ffprobe >/dev/null 2>&1; then
  log "试听文件时长：$(ffprobe -v error -show_entries format=duration -of csv=p=0 /tmp/melotts-smoke-zh.wav)s"
fi

log "✅ MeloTTS 安装完成"
log "   venv   : $VENV"
log "   python : $PY"
log "   试听   : /tmp/melotts-smoke-zh.wav"
