#!/usr/bin/env python3
"""MeloTTS 批处理配音 worker（由 build_audio.py 调用，不单独使用）。

引擎目录里的 `python3` 是裸的系统解释器（没有第三方包），MeloTTS 装在单独的
venv 里，所以本文件要用「能 import melo 的那个解释器」来跑。

    <melotts-python> scripts/melo_tts.py --jobs <work>/melo_jobs.json

jobs.json:
    {
      "language": "ZH",
      "speed": 1.0,
      "speaker": 0,
      "jobs": [{"id": "L01", "text": "...", "out": "/abs/path/L01.wav"}, ...]
    }

为什么是批处理：模型（含 BERT）加载要十几秒到几十秒，逐行起进程完全不可行，
所以一次性加载、逐条合成。进度打到 stderr，方便调用方实时看到。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser(description="MeloTTS batch synthesis worker")
    ap.add_argument("--jobs", required=True, help="批处理描述 JSON 的路径")
    ap.add_argument("--language", default=None, help="覆盖 jobs.json 里的 language")
    ap.add_argument("--speed", type=float, default=None, help="覆盖 jobs.json 里的 speed")
    ap.add_argument("--speaker", type=int, default=None, help="覆盖 jobs.json 里的 speaker")
    args = ap.parse_args()

    spec = json.loads(Path(args.jobs).read_text(encoding="utf-8"))
    language = args.language or spec.get("language") or "ZH"
    speed = args.speed if args.speed is not None else float(spec.get("speed", 1.0))
    speaker = args.speaker if args.speaker is not None else int(spec.get("speaker", 0))
    jobs = spec.get("jobs") or []
    if not jobs:
        print("melo: jobs.json 里没有任务", file=sys.stderr)
        return 2

    t0 = time.time()
    print(f"melo: 加载 {language} 模型（首次运行会从 HuggingFace 下模型）…", file=sys.stderr)
    from melo.api import TTS

    model = TTS(language=language, device="cpu")
    print(f"melo: 模型就绪 {time.time() - t0:.1f}s，开始合成 {len(jobs)} 条", file=sys.stderr)

    done = 0
    for job in jobs:
        line_id = str(job.get("id") or done + 1)
        text = str(job.get("text") or "").strip()
        out = Path(job["out"])
        out.parent.mkdir(parents=True, exist_ok=True)
        if not text:
            print(f"melo: {line_id} 文本为空，跳过", file=sys.stderr)
            continue
        model.tts_to_file(
            text,
            speaker_id=speaker,
            output_path=str(out),
            speed=speed,
            quiet=True,
        )
        if not out.exists() or out.stat().st_size == 0:
            print(f"melo: {line_id} 没有产出音频：{out}", file=sys.stderr)
            return 3
        done += 1
        print(f"melo: {line_id} {out.stat().st_size / 1024:.0f}KB", file=sys.stderr)

    print(
        f"melo: {done}/{len(jobs)} 条合成完成，总用时 {time.time() - t0:.1f}s",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
