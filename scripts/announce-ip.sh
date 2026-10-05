#!/usr/bin/env bash
# ============================================================
#  MediaSplitter AI Monitor — 语音播报局域网 IP 与端口
#
#  用途：
#   - GNOME 登录自启（登录后音频就绪，能真正听到）
#   - 或手动执行：./scripts/announce-ip.sh
#
#  播报为英文、数字逐位慢读（espeak-ng 内置 ja 语音读不准日语）：
#   "Media Splitter Monitor started. IP address is one nine two
#    dot one six eight dot zero dot one six one. Port number is
#    one five eight eight eight."
# ============================================================
set -euo pipefail

PORT="${PORT:-15888}"

# 可选：从 .env 读取 AUDIO_DEVICE（若设置了则优先生效）
if [[ -f "$(dirname "$0")/../.env" ]]; then
  DEV_ENV="$(grep -E '^AUDIO_DEVICE=' "$(dirname "$0")/../.env" | head -1 | cut -d= -f2-)"
  [[ -n "$DEV_ENV" ]] && export AUDIO_DEVICE="$DEV_ENV"
fi

# 取局域网 IPv4（第一个非 127 地址）
IP="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' | grep -v '^127\.' | head -1)"
if [[ -z "$IP" ]]; then
  IP="127.0.0.1"
fi

# 数字逐位 → 英文单词
digit_word() {
  case "$1" in
    0) echo -n "zero ";; 1) echo -n "one ";;  2) echo -n "two ";;
    3) echo -n "three ";; 4) echo -n "four ";; 5) echo -n "five ";;
    6) echo -n "six ";;  7) echo -n "seven ";; 8) echo -n "eight ";;
    9) echo -n "nine ";; *) echo -n "$1 ";;
  esac
}

digits_to_words() {
  local v="$1" i ch out=""
  for ((i = 0; i < ${#v}; i++)); do
    ch="${v:i:1}"
    out+="$(digit_word "$ch")"
  done
  echo -n "${out% }"
}

# IP：每段逐位 + dot；端口：逐位
ip_words=""
for octet in ${IP//./ }; do
  ip_words+="$(digits_to_words "$octet") dot "
done
ip_words="${ip_words% dot }"
port_words="$(digits_to_words "$PORT")"

echo "================================================"
echo "MediaSplitter AI Monitor"
echo "IP: $IP | Port: $PORT"
echo "URL: http://$IP:$PORT"
echo "================================================"

# 英文语音播报：espeak-ng 生成 WAV → aplay 直出 ALSA 设备（绕过用户音频会话，未登录也能响）
# 设备候选：$AUDIO_DEVICE > plughw:0,0（自带扬声器）> default > sysdefault
if command -v espeak-ng >/dev/null 2>&1 && command -v aplay >/dev/null 2>&1; then
  WAV="$(mktemp --suffix=.wav)"
  espeak-ng -s 100 -v en-us -w "$WAV" \
    "Media Splitter Monitor started. IP address is ${ip_words}. Port number is ${port_words}." \
    >/dev/null 2>&1 || true
  for dev in "${AUDIO_DEVICE:-}" plughw:0,0 default sysdefault; do
    if [[ -n "$dev" ]] && aplay -D "$dev" "$WAV" >/dev/null 2>&1; then
      break
    fi
  done
  rm -f "$WAV"
fi
