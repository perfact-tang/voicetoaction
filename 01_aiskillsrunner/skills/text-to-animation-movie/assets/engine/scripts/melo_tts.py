#!/usr/bin/env python3
"""MeloTTS 批处理配音 worker（由 build_audio.py 调用，不单独使用）。

引擎目录里的 `python3` 是裸的系统解释器（没有第三方包），MeloTTS 装在单独的
venv 里，所以本文件要用「能 import melo 的那个解释器」来跑。

    <melotts-python> scripts/melo_tts.py --jobs <work>/melo_jobs.json

jobs.json:
    {
      "language": "ZH", "speed": 1.0, "speaker": 1, "seed": 1234,
      "fallback_speakers": "1,2,3,4,5", "attempts": 2,
      "sdp_ratio": 0.2, "noise_scale": 0.6, "noise_scale_w": 0.8,
      "retries": 4, "strip_punct": "，,；;", "gap_s": 0.25,
      "jobs": [{"id": "L01", "text": "...", "out": "/abs/path/L01.wav"}, ...]
    }

为什么是批处理：模型（含 BERT）加载要十几秒到几十秒，逐行起进程完全不可行，
所以一次性加载、逐条合成。进度打到 stderr，方便调用方实时看到。

这个 worker 比直接调 `tts_to_file` 多做了三件事，都是踩过的坑：

1. **静音检测 + 换音色兜底**。MeloTTS 会输出「长度正确、但采样几乎全是 0」的波形
   （实测 31 行里中招 6~10 行，峰值恒为 3e-5）。实测结论：**这跟随机种子无关**
   （换 seed、把 sdp_ratio 和 noise_scale 都归零、关掉 BERT，结果一模一样），而是由
   「文本 × speaker」这个组合决定的 —— 同一句在 speaker 1 上有声、speaker 0 上是静音，
   而换一句两边都正常；短句也可能中招（"同样在卖东西" 静音，"…为什么利润差别" 有声）。
   所以兜底是**换 speaker 重试**，不是换种子。合成完查峰值电平和有效样本占比，
   不合格就换下一个候选音色。
2. **逗号不再被它切开**。`melo.split_utils.split_sentences_zh` 会在 `，` 处切句，
   然后段与段之间只插 **0.05 秒**静音（正常逗号停顿是 0.2~0.3 秒），听起来就是
   「词没说完就下一句」。这里默认先把行内 `，`/`,`/`；`/`;` 去掉，让模型自己断句；
   万一还有别的切点，就用我们自己的停顿（gap_s）拼接。
3. **可调语气**。`speaker`（这个中文模型有 256 个音色）、`sdp_ratio`、`noise_scale`
   都从 jobs.json 传进来，用来找更平稳的叙述感。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf

# 低于这个峰值电平就认为「没出声」（约 -80 dBFS）
SILENCE_PEAK = 1e-4
# 有效语音样本占比低于这个值也算失败
MIN_VOICED_RATIO = 0.02
# 短于这个时长认为是异常
MIN_DURATION_S = 0.25
# 我们自己拼接段落时插入的停顿（MeloTTS 内部只插 0.05s）
DEFAULT_GAP_S = 0.25
# 行内会让 MeloTTS 切句的标点（切完只剩 50ms 停顿 → 听起来像吞字）
DEFAULT_STRIP_PUNCT = "，,；;"


def normalize_text(text: str, strip_chars: str) -> str:
    """去掉会让 MeloTTS 在句中切句的标点，让它自己决定断句。"""
    if not strip_chars:
        return text
    out = text
    for ch in strip_chars:
        out = out.replace(ch, "")
    return out


def inspect(path: Path) -> tuple[bool, str, float, float, float]:
    """检查一段合成结果。返回 (是否合格, 原因, 峰值, 时长秒, 有效样本占比)。"""
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


def synthesize(
    model,
    text: str,
    out_path: Path,
    *,
    speaker_id: int,
    speed: float,
    sdp_ratio: float,
    noise_scale: float,
    noise_scale_w: float,
    gap_s: float,
) -> int:
    """合成一行，返回 MeloTTS 把它切成了几段。

    单段时直接交给 `tts_to_file`；多段时逐段合成、用我们自己的停顿拼接，
    避免它内部 0.05 秒的粘连。
    """
    pieces = model.split_sentences_into_pieces(text, model.language, quiet=True)
    sample_rate = model.hps.data.sampling_rate
    kwargs = dict(
        speaker_id=speaker_id,
        sdp_ratio=sdp_ratio,
        noise_scale=noise_scale,
        noise_scale_w=noise_scale_w,
        speed=speed,
        quiet=True,
    )
    if len(pieces) <= 1:
        model.tts_to_file(text, output_path=str(out_path), **kwargs)
        return len(pieces)

    gap = np.zeros(int(sample_rate * gap_s), dtype=np.float32)
    chunks: list[np.ndarray] = []
    for piece in pieces:
        audio = model.tts_to_file(piece, output_path=None, **kwargs)
        chunks.append(np.asarray(audio, dtype=np.float32))
        chunks.append(gap)
    sf.write(str(out_path), np.concatenate(chunks), sample_rate)
    return len(pieces)


def main() -> int:
    ap = argparse.ArgumentParser(description="MeloTTS batch synthesis worker")
    ap.add_argument("--jobs", required=True, help="批处理描述 JSON 的路径")
    ap.add_argument("--language", default=None, help="覆盖 jobs.json 里的 language")
    ap.add_argument("--speaker", type=int, default=None, help="覆盖 speaker id")
    ap.add_argument("--speed", type=float, default=None, help="覆盖语速")
    args = ap.parse_args()

    spec = json.loads(Path(args.jobs).read_text(encoding="utf-8"))
    language = args.language or spec.get("language") or "ZH"
    speed = args.speed if args.speed is not None else float(spec.get("speed", 1.0))
    speaker = args.speaker if args.speaker is not None else int(spec.get("speaker", 0))
    sdp_ratio = float(spec.get("sdp_ratio", 0.2))
    noise_scale = float(spec.get("noise_scale", 0.6))
    noise_scale_w = float(spec.get("noise_scale_w", 0.8))
    strip_punct = spec.get("strip_punct", DEFAULT_STRIP_PUNCT)
    gap_s = float(spec.get("gap_s", DEFAULT_GAP_S))
    seed = int(spec.get("seed", 1234))
    attempts = max(1, int(spec.get("attempts", 2)))
    fallback_speakers = [
        int(item)
        for item in str(spec.get("fallback_speakers", "1,2,3,4,5")).replace(" ", "").split(",")
        if item.strip().lstrip("-").isdigit()
    ]
    jobs = spec.get("jobs") or []
    if not jobs:
        print("melo: jobs.json 里没有任务", file=sys.stderr)
        return 2

    t0 = time.time()
    print(f"melo: 加载 {language} 模型（首次运行会从 HuggingFace 下模型）…", file=sys.stderr)
    import torch
    from melo.api import TTS

    model = TTS(language=language, device="cpu")
    print(
        f"melo: 模型就绪 {time.time() - t0:.1f}s，开始合成 {len(jobs)} 条"
        f"（speaker={speaker}, 兜底音色={fallback_speakers}, speed={speed:g}, "
        f"sdp_ratio={sdp_ratio:g}, noise_scale={noise_scale:g}）",
        file=sys.stderr,
    )

    done = 0
    retried = 0
    fallback_used: list[tuple[str, int]] = []
    failures: list[tuple[str, str]] = []

    for index, job in enumerate(jobs):
        line_id = str(job.get("id") or index + 1)
        raw_text = str(job.get("text") or "").strip()
        out = Path(job["out"])
        out.parent.mkdir(parents=True, exist_ok=True)
        if not raw_text:
            print(f"melo: {line_id} 文本为空，跳过", file=sys.stderr)
            continue

        text = normalize_text(raw_text, strip_punct)
        if text != raw_text:
            print(f"melo: {line_id} 去掉行内标点：{raw_text!r} -> {text!r}", file=sys.stderr)
        if not text:
            failures.append((line_id, "去掉标点后没有内容"))
            continue

        # 静音是「文本 × speaker」决定的，所以兜底靠**换音色**，不是换种子。
        candidates = [speaker] + [item for item in fallback_speakers if item != speaker]
        reason = "未执行"
        used: int | None = None
        for candidate in candidates:
            for attempt in range(1, attempts + 1):
                torch.manual_seed(seed + index * 97 + attempt + candidate * 13)
                try:
                    pieces = synthesize(
                        model,
                        text,
                        out,
                        speaker_id=candidate,
                        speed=speed,
                        sdp_ratio=sdp_ratio,
                        noise_scale=noise_scale,
                        noise_scale_w=noise_scale_w,
                        gap_s=gap_s,
                    )
                except Exception as error:  # noqa: BLE001 - 合成异常也换个音色再试
                    reason = f"合成异常：{type(error).__name__}: {error}"
                    print(
                        f"melo: {line_id} speaker {candidate} 第 {attempt}/{attempts} 次 {reason}",
                        file=sys.stderr,
                    )
                    continue

                ok, detail, peak, duration, _voiced = inspect(out)
                if ok:
                    used = candidate
                    if attempt > 1:
                        retried += 1
                    print(
                        f"melo: {line_id} speaker {candidate} "
                        f"{out.stat().st_size / 1024:.0f}KB {duration:.2f}s "
                        f"峰值{peak:.3f} 段数{pieces}",
                        file=sys.stderr,
                    )
                    break

                reason = detail
                print(
                    f"melo: {line_id} speaker {candidate} 第 {attempt}/{attempts} 次不合格：{detail}",
                    file=sys.stderr,
                )
            if used is not None:
                break

        if used is None:
            failures.append((line_id, reason))
            continue
        done += 1
        if used != speaker:
            fallback_used.append((line_id, used))
            print(
                f"melo: ⚠ {line_id} 主音色 {speaker} 一直静音，改用兜底音色 {used}"
                f"（这一行的音色会略有不同）",
                file=sys.stderr,
            )

    print(
        f"melo: {done}/{len(jobs)} 条合成完成（其中 {retried} 条同音色重试后才合格），"
        f"总用时 {time.time() - t0:.1f}s",
        file=sys.stderr,
    )
    if fallback_used:
        detail = ", ".join(f"{line_id}→speaker {spk}" for line_id, spk in fallback_used)
        print(
            f"melo: ⚠ {len(fallback_used)} 条用了兜底音色：{detail}",
            file=sys.stderr,
        )
        print(
            "melo:   建议换一个主音色（用 scripts/melo_audition.py 试听挑选），"
            "避免成片里音色跳变",
            file=sys.stderr,
        )
    if failures:
        for line_id, reason in failures:
            print(f"melo: ✖ {line_id} 合成失败：{reason}", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
