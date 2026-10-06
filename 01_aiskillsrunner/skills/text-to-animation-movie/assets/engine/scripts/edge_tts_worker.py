#!/usr/bin/env python3
"""edge-tts 批处理配音 worker（由 build_audio.py 调用，不单独使用）。

edge-tts = 微软 Edge 的在线 TTS：不需要 API key，韵律稳定、中文音色自然。
默认音色 `zh-CN-YunyangNeural`（男声·新闻播报风，平稳，适合讲解类旁白）。
代价是**合成时必须联网**；离线时 build_audio.py 会自动回退到本地的 MeloTTS。

    <edge-python> scripts/edge_tts_worker.py --jobs <work>/edge_jobs.json

jobs.json:
    {
      "voice": "zh-CN-YunyangNeural",
      "rate": "+0%", "pitch": "+0Hz", "volume": "+0%",
      "retries": 3,
      "jobs": [{"id": "L01", "text": "...", "out": "/abs/path/L01.wav"}, ...]
    }

与 melo_tts.py 的差别：

- **保留标点**。edge 自己会在逗号处给出正常停顿，不需要像 MeloTTS 那样把行内标点去掉
  （MeloTTS 会在逗号处切句、段间只插 50ms，听起来像吞字）。
- **没有模型加载**，所以逐条合成很快，不需要「批处理」省启动成本；这里仍然批处理是为了
  少起进程、统一日志。
- 仍然做静音/有效语音检查：不合格就重试（网络抖动、偶发空音频）。
- 输出先落 mp3（edge 只提供 mp3），再用 ffmpeg 转成 24k 单声道 wav，
  交给 build_audio.py 统一做响度归一化。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf

SILENCE_PEAK = 1e-4
MIN_VOICED_RATIO = 0.02
MIN_DURATION_S = 0.25
# 一条都没成功、失败又攒到这个数，就判定「后端整体不可用」（多半是没网），
# 提前退出让 build_audio.py 回退到本地 MeloTTS —— 否则 31 行会一行行磨好几分钟。
EARLY_ABORT_FAILURES = 3


def inspect(path: Path) -> tuple[bool, str, float, float, float]:
    """返回 (是否合格, 原因, 峰值, 时长秒, 有效样本占比)。"""
    if not path.exists():
        return False, "没有产出文件", 0.0, 0.0, 0.0
    audio, sr = sf.read(str(path), dtype="float32", always_2d=False)
    if audio.ndim > 1:
        audio = audio.mean(axis=1)
    if audio.size == 0:
        return False, "空音频", 0.0, 0.0, 0.0
    peak = float(np.abs(audio).max())
    duration = len(audio) / float(sr)
    voiced = float((np.abs(audio) > 1e-3).mean())
    if peak < SILENCE_PEAK:
        return False, f"整段静音（峰值 {peak:.6f}）", peak, duration, voiced
    if duration < MIN_DURATION_S:
        return False, f"时长异常短（{duration:.2f}s）", peak, duration, voiced
    if voiced < MIN_VOICED_RATIO:
        return False, f"几乎没有有效语音（有效样本 {voiced:.1%}）", peak, duration, voiced
    return True, "ok", peak, duration, voiced


def mp3_to_wav(mp3: Path, wav: Path) -> tuple[bool, str]:
    result = subprocess.run(
        ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(mp3),
         "-ar", "24000", "-ac", "1", "-c:a", "pcm_s16le", str(wav)],
        capture_output=True, text=True,
    )
    return result.returncode == 0, result.stderr.strip()


async def synthesize_one(communicate_cls, text, voice, mp3: Path, rate, pitch, volume) -> None:
    communicate = communicate_cls(text, voice, rate=rate, pitch=pitch, volume=volume)
    await communicate.save(str(mp3))


def main() -> int:
    ap = argparse.ArgumentParser(description="edge-tts batch synthesis worker")
    ap.add_argument("--jobs", required=True, help="批处理描述 JSON 的路径")
    ap.add_argument("--voice", default=None, help="覆盖音色")
    args = ap.parse_args()

    spec = json.loads(Path(args.jobs).read_text(encoding="utf-8"))
    voice = args.voice or spec.get("voice") or "zh-CN-YunyangNeural"
    rate = str(spec.get("rate", "+0%"))
    pitch = str(spec.get("pitch", "+0Hz"))
    volume = str(spec.get("volume", "+0%"))
    retries = max(1, int(spec.get("retries", 3)))
    jobs = spec.get("jobs") or []
    if not jobs:
        print("edge: jobs.json 里没有任务", file=sys.stderr)
        return 2

    print(f"edge: 音色 {voice}（rate={rate} pitch={pitch} volume={volume}）", file=sys.stderr)
    try:
        import edge_tts
    except ImportError as error:
        print(f"edge: 没装 edge-tts（{error}）", file=sys.stderr)
        return 4

    started = time.time()
    done = 0
    retried = 0
    failures: list[tuple[str, str]] = []

    for index, job in enumerate(jobs):
        line_id = str(job.get("id") or index + 1)
        text = str(job.get("text") or "").strip()
        out = Path(job["out"])
        out.parent.mkdir(parents=True, exist_ok=True)
        if not text:
            print(f"edge: {line_id} 文本为空，跳过", file=sys.stderr)
            continue

        mp3 = out.with_suffix(".edge.mp3")
        reason = "未执行"
        try:
            for attempt in range(1, retries + 1):
                try:
                    asyncio.run(synthesize_one(edge_tts.Communicate, text, voice, mp3,
                                               rate, pitch, volume))
                except Exception as error:  # noqa: BLE001 - 网络抖动也重试
                    reason = f"合成异常：{type(error).__name__}: {error}"
                    print(f"edge: {line_id} 第 {attempt}/{retries} 次 {reason}", file=sys.stderr)
                    # 网络抖动的退避：1.5s / 3s / 4.5s…封顶 6s
                    time.sleep(min(1.5 * attempt, 6.0))
                    continue

                ok, detail = mp3_to_wav(mp3, out)
                if not ok:
                    reason = f"mp3 转 wav 失败：{detail[-160:]}"
                    print(f"edge: {line_id} 第 {attempt}/{retries} 次 {reason}", file=sys.stderr)
                    continue

                passed, detail, peak, duration, _voiced = inspect(out)
                if passed:
                    if attempt > 1:
                        retried += 1
                    print(
                        f"edge: {line_id} {out.stat().st_size / 1024:.0f}KB "
                        f"{duration:.2f}s 峰值{peak:.3f}",
                        file=sys.stderr,
                    )
                    done += 1
                    break
                reason = detail
                print(f"edge: {line_id} 第 {attempt}/{retries} 次不合格：{detail}", file=sys.stderr)
            else:
                failures.append((line_id, reason))
        finally:
            mp3.unlink(missing_ok=True)

        if done == 0 and len(failures) >= EARLY_ABORT_FAILURES:
            print(
                f"edge: 连续 {len(failures)} 条失败且没有任何成功 → 判定后端不可用（多半是没网），"
                "提前退出，交给本地 MeloTTS 兜底",
                file=sys.stderr,
            )
            return 5

    print(
        f"edge: {done}/{len(jobs)} 条合成完成（其中 {retried} 条重试后成功），"
        f"总用时 {time.time() - started:.1f}s",
        file=sys.stderr,
    )
    if failures:
        for line_id, reason in failures:
            print(f"edge: ✖ {line_id} 合成失败：{reason}", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
