#!/usr/bin/env python3
"""MeloTTS 音色试听工具：把同一句话用多个 speaker_id 各合成一遍，方便挑音色。

MeloTTS 的中文模型有 **256 个说话人**，`speaker_id=0` 只是其中一个；不同 id 的
音色和朗读气质差别很明显。语气不对时先换这个，比调参数有效。

    <melotts-python> scripts/melo_audition.py \
        --text "九种模式不是九选一，一家公司常同时用好几种。" \
        --speakers 0,16,32,48,64,80,96,112,128,144,160,176,192,208,224,240 \
        --outdir /tmp/melotts-audition

产出：
    <outdir>/spk000.wav …            每个音色一个文件（文件名就是 speaker_id）
    <outdir>/all.wav                 全部按顺序拼成一条，方便连续听
    <outdir>/index.txt               顺序表 + 客观指标（F0 均值/标准差、时长）

客观指标只是辅助：`f0_std` 越小说明语调起伏越小（越平稳、越「念稿」）。
好不好听、气质对不对，请自己听 wav 决定。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import soundfile as sf


def parse_speakers(spec: str, total: int = 256) -> list[int]:
    """支持 '0,16,32' 和 '0-255:16'（起-止:步长）两种写法。"""
    ids: list[int] = []
    for chunk in str(spec).split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        if "-" in chunk:
            rng, _, step = chunk.partition(":")
            start, _, end = rng.partition("-")
            lo, hi = int(start), int(end)
            ids.extend(range(lo, min(hi, total - 1) + 1, int(step or 1)))
        else:
            ids.append(int(chunk))
    seen, ordered = set(), []
    for i in ids:
        if 0 <= i < total and i not in seen:
            seen.add(i)
            ordered.append(i)
    return ordered


def f0_stats(audio: np.ndarray, sr: int) -> tuple[float, float]:
    """用自相关法粗估基频均值/标准差（只用于排序，不做严谨声学分析）。"""
    try:
        import librosa

        f0 = librosa.yin(audio, fmin=70, fmax=400, sr=sr, frame_length=2048)
        f0 = f0[np.isfinite(f0)]
        if f0.size == 0:
            return 0.0, 0.0
        return float(np.mean(f0)), float(np.std(f0))
    except Exception:  # noqa: BLE001 - 估不出来就算了，不影响试听
        return 0.0, 0.0


def main() -> int:
    ap = argparse.ArgumentParser(description="MeloTTS speaker audition")
    ap.add_argument("--text", required=True, help="要试听的句子")
    ap.add_argument("--speakers", default="0,16,32,48,64,80,96,112,128,144,160,176,192,208,224,240")
    ap.add_argument("--outdir", default="/tmp/melotts-audition")
    ap.add_argument("--language", default="ZH")
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--sdp-ratio", type=float, default=0.2)
    ap.add_argument("--noise-scale", type=float, default=0.6)
    ap.add_argument("--gap-s", type=float, default=0.5, help="拼接试听文件时的间隔")
    args = ap.parse_args()

    speakers = parse_speakers(args.speakers)
    outdir = Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)

    print(f"加载 {args.language} 模型…", file=sys.stderr)
    from melo.api import TTS

    model = TTS(language=args.language, device="cpu")
    sr = model.hps.data.sampling_rate
    gap = np.zeros(int(sr * args.gap_s), dtype=np.float32)

    rows: list[tuple[int, float, float, float]] = []
    combined: list[np.ndarray] = []
    for speaker in speakers:
        path = outdir / f"spk{speaker:03d}.wav"
        try:
            model.tts_to_file(
                args.text,
                speaker_id=speaker,
                output_path=str(path),
                speed=args.speed,
                sdp_ratio=args.sdp_ratio,
                noise_scale=args.noise_scale,
                quiet=True,
            )
        except Exception as error:  # noqa: BLE001
            print(f"speaker {speaker}: ✖ {type(error).__name__}: {error}", file=sys.stderr)
            continue
        audio, _ = sf.read(str(path), dtype="float32")
        peak = float(np.abs(audio).max()) if audio.size else 0.0
        duration = len(audio) / sr
        f0_mean, f0_std = f0_stats(audio, sr)
        rows.append((speaker, f0_mean, f0_std, duration))
        combined.append(audio)
        combined.append(gap)
        print(
            f"speaker {speaker:>3}: {duration:.2f}s 峰值{peak:.3f} "
            f"F0均值{f0_mean:.0f}Hz 起伏{f0_std:.1f}Hz -> {path.name}",
            file=sys.stderr,
        )

    if not rows:
        print("没有任何音色合成成功", file=sys.stderr)
        return 1

    if combined:
        sf.write(str(outdir / "all.wav"), np.concatenate(combined), sr)

    table = ["顺序  speaker  F0均值Hz  F0起伏Hz  时长s"]
    for idx, (speaker, f0_mean, f0_std, duration) in enumerate(rows):
        table.append(f"{idx:>4}  {speaker:>7}  {f0_mean:>8.0f}  {f0_std:>8.1f}  {duration:>5.2f}")
    (outdir / "index.txt").write_text("\n".join(table) + "\n", encoding="utf-8")

    print(f"\n全部文件在 {outdir}/", file=sys.stderr)
    print("按 F0 起伏从小到大（越靠前越平稳）：", file=sys.stderr)
    for speaker, _f0_mean, f0_std, _duration in sorted(rows, key=lambda r: r[2]):
        print(f"  speaker {speaker:>3}  起伏 {f0_std:>5.1f} Hz", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
