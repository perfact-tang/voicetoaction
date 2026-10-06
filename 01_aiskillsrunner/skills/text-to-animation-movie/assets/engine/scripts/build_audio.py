#!/usr/bin/env python3
"""
Builds the narration pipeline for a hand-drawn explainer video.

The script (video settings + scenes + narration lines) lives in a JSON file, so
the same pipeline drives any number of videos:

    scripts/script.json
      -> public/<audioTrack>          one continuous narration track
      -> src/data/timing.ts           scene/line frame timings (runtime truth)
      -> src/data/videoConfig.ts      fps / size / output name (runtime truth)
      -> out/<id>.lrc                 timestamped subtitles
      -> out/<id>-cue-sheet.md        human readable storyboard timeline
      -> out/<id>-timing.json         full diagnostics (onsets, durations)

Steps
  1. Render each narration line to speech with whatever TTS is available:
     **MeloTTS first** when a local install is found (neural, much more natural
     Chinese than espeak), then macOS `say`, then espeak-ng/espeak. If no TTS
     exists, or `--no-audio` is given, silent clips of the right length are
     generated instead so the video still runs as subtitles-only.
  2. Concatenate every line into one continuous track; the trailing silence each
     clip carries becomes the breathing room between lines, so audio and
     on-screen timing come from a single source.
  3. Locate where each line actually starts *inside that finished track* and
     derive the whole timeline from those real onsets. Frame positions are
     snapped once, at the end — never accumulated — so subtitle times cannot
     drift away from the voice.
  4. Write the TypeScript the composition imports, plus the .lrc and cue sheet.

Usage:
  python3 scripts/build_audio.py                    # uses scripts/script.json
  python3 scripts/build_audio.py --script other.json
  python3 scripts/build_audio.py --dry-run          # pacing check, no synthesis
  python3 scripts/build_audio.py --no-audio         # force subtitles-only
  python3 scripts/build_audio.py --tts espeak-ng    # force a TTS backend
  python3 scripts/build_audio.py --tts melo         # force the local MeloTTS

MeloTTS is auto-detected: any interpreter that can `import melo` counts, see
find_melo_python(). Install it with scripts/install-melotts.sh (no sudo needed).
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
PUBLIC = PROJECT / "public"
OUT = PROJECT / "out"

CHARS_PER_SECOND = 3.55     # measured speech rate, used by --dry-run estimates
LINE_PAUSE_MS = 190         # nominal pause, for reporting only
SCENE_PAUSE_MS = 320        # nominal scene pause, for reporting only
FINAL_TAIL_MS = 1100        # silence appended to the end of the track
SCENE_LEAD_FRAMES = 10      # scene starts shortly before its line is spoken
SILENCE_NOISE_DB = -50      # silencedetect threshold used to find boundaries
SILENCE_MIN_S = 0.16

SCENE_MAX_SECONDS = 18.0    # guard rail against runaway scenes
TOTAL_MAX_SECONDS = 240.0   # guard rail against a runaway cut

RATE = 24000                # sample rate for generated audio
MAX_CLIP_SECONDS = 20.0     # silent fallback clips are capped at this

# espeak-ng is asked for these languages, in order, until one works.
ESPEAK_VOICE_CANDIDATES = ["cmn", "zh", "zh-cn"]

# MeloTTS: local neural TTS. It cannot be imported by this script's own
# interpreter (a bare system python3 with no third-party packages), so it is
# driven as a subprocess through scripts/melo_tts.py with whichever interpreter
# on this machine can `import melo`.
MELO_WORKER = Path(__file__).resolve().parent / "melo_tts.py"
# speed=1.0 is MeloTTS' natural pace; tune with MELOTTS_SPEED=1.1 etc.
MELO_SPEED_ENV = "MELOTTS_SPEED"
MELO_SPEED_DEFAULT = 1.0
MELO_SPEED_MIN = 0.5
MELO_SPEED_MAX = 2.0


# --------------------------------------------------------------------------
# Script file
# --------------------------------------------------------------------------


class Script:
    def __init__(self, path: Path) -> None:
        raw = json.loads(path.read_text(encoding="utf-8"))
        self.path = path
        self.id: str = raw["id"]
        self.title: str = raw.get("title", self.id)
        self.fps: int = int(raw.get("fps", 30))
        self.width: int = int(raw.get("width", 1080))
        self.height: int = int(raw.get("height", 1920))
        self.audio_track: str = raw.get("audioTrack", "narration.wav")
        self.out_file: str = raw.get("outFile", "out/movie.mp4")
        self.style: str = raw.get("style", "whiteboard")
        self.voice: str = raw.get("voice", "auto")
        self.rate: int = int(raw.get("rate", 180))
        self.scenes: list[dict] = raw["scenes"]

        self.timing_ts: Path = PROJECT / raw.get("timingTs", "src/data/timing.ts")
        self.config_ts: Path = PROJECT / raw.get("configTs", "src/data/videoConfig.ts")
        self.lrc: Path = OUT / raw.get("lrc", f"{self.id}.lrc")
        self.cue_sheet: Path = OUT / raw.get("cueSheet", f"{self.id}-cue-sheet.md")

        ids = [l[0] for s in self.scenes for l in s["lines"]]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate line ids in script")
        self.flat = [
            (s["id"], s["visual"], l[0], l[1]) for s in self.scenes for l in s["lines"]
        ]


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------


def run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, check=False)


def probe_duration_ms(path: Path) -> float:
    res = run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", str(path)]
    )
    if res.returncode != 0:
        raise RuntimeError(f"ffprobe failed for {path}: {res.stderr}")
    return float(res.stdout.strip()) * 1000.0


def silence_segments(path: Path) -> list[tuple[float, float]]:
    """Silent stretches of `path` as (start_ms, end_ms), from silencedetect."""
    res = run(
        ["ffmpeg", "-hide_banner", "-nostats", "-i", str(path),
         "-af", f"silencedetect=noise={SILENCE_NOISE_DB}dB:d={SILENCE_MIN_S}",
         "-f", "null", "-"]
    )
    starts = [float(m) * 1000 for m in re.findall(r"silence_start: ([\d.]+)", res.stderr)]
    ends = [float(m) * 1000 for m in re.findall(r"silence_end: ([\d.]+)", res.stderr)]
    return list(zip(starts, ends))


def han_len(text: str) -> int:
    """Rough 'how long does this take to say' weight."""
    return len(re.findall(r"[\u4e00-\u9fff]", text)) + len(
        re.findall(r"[A-Za-z0-9]+", text)
    )


# --------------------------------------------------------------------------
# Text to speech
# --------------------------------------------------------------------------


_melo_python: str | None = None
_melo_probed = False


def _melo_python_candidates() -> list[str]:
    """Interpreters that might have MeloTTS, most specific first."""
    home = Path.home()
    raw = [
        os.environ.get("MELOTTS_PYTHON"),
        os.environ.get("AISKILLS_MELOTTS_PYTHON"),
        sys.executable,
        shutil.which("python3"),
        shutil.which("python"),
        str(home / "melotts-venv" / "bin" / "python"),
        str(home / ".local" / "share" / "melotts" / "venv" / "bin" / "python"),
        str(home / ".venvs" / "melotts" / "bin" / "python"),
        str(home / "melotts" / ".venv" / "bin" / "python"),
    ]
    seen: set[str] = set()
    ordered: list[str] = []
    for item in raw:
        if not item:
            continue
        candidate = str(item)
        if candidate in seen:
            continue
        seen.add(candidate)
        ordered.append(candidate)
    return ordered


def find_melo_python() -> str | None:
    """Path of an interpreter that can `import melo`, or None if there is none.

    Probe order: $MELOTTS_PYTHON → $AISKILLS_MELOTTS_PYTHON → the interpreter
    running this script → python3/python on PATH → the conventional venv
    locations. The first one that actually imports MeloTTS wins, so a stale
    path on that list costs a fraction of a second and nothing else.
    """
    global _melo_python, _melo_probed
    if not _melo_probed:
        _melo_probed = True
        for candidate in _melo_python_candidates():
            if not (os.path.isfile(candidate) and os.access(candidate, os.X_OK)):
                continue
            if run([candidate, "-c", "import melo"]).returncode == 0:
                _melo_python = candidate
                break
    return _melo_python


def _melo_language(voice: str) -> str:
    """script.json 的 voice 顺便兼职 MeloTTS 的语言名（默认中文）。

    MeloTTS 支持 ZH / ZH_MIX_EN / EN / JP / KR / ES / FR；这里认不出来的值一律
    回落到 ZH，因为本 skill 的台词默认就是中文。
    """
    table = {
        "": "ZH",
        "auto": "ZH",
        "default": "ZH",
        "zh": "ZH",
        "cn": "ZH",
        "zh-cn": "ZH",
        "chinese": "ZH",
        "中文": "ZH",
        "zh_mix_en": "ZH_MIX_EN",
        "zh-mix-en": "ZH_MIX_EN",
        "mix": "ZH_MIX_EN",
        "en": "EN",
        "english": "EN",
        "jp": "JP",
        "ja": "JP",
        "japanese": "JP",
        "kr": "KR",
        "ko": "KR",
        "korean": "KR",
        "es": "ES",
        "spanish": "ES",
        "fr": "FR",
        "french": "FR",
    }
    return table.get(str(voice or "").strip().lower(), "ZH")


def _melo_speed() -> float:
    """MeloTTS 语速（MELOTTS_SPEED，默认 1.0）；时间轴由实测音轨反推，改它不会让字幕错位。"""
    raw = os.environ.get(MELO_SPEED_ENV)
    try:
        speed = float(raw) if raw else MELO_SPEED_DEFAULT
    except ValueError:
        speed = MELO_SPEED_DEFAULT
    return max(MELO_SPEED_MIN, min(MELO_SPEED_MAX, speed))


def _tts_candidates(preferred: str) -> list[str]:
    """TTS backends to try, best first, filtered to what is installed."""
    have = {n: shutil.which(n) for n in ("say", "espeak-ng", "espeak")}
    # 本机装了 MeloTTS 就用它：espeak 的中文机械音太差，是这次要换掉的东西。
    have["melo"] = find_melo_python() is not None
    if preferred and preferred != "auto":
        aliases = {"macos": "say", "espeakng": "espeak-ng", "melotts": "melo"}
        order = [aliases.get(preferred, preferred)]
    elif platform.system() == "Darwin" and have["say"]:
        order = ["melo", "say", "espeak-ng", "espeak"]
    else:
        order = ["melo", "espeak-ng", "espeak", "say"]
    return [n for n in order if have.get(n)]


class Tts:
    """Wraps whichever speech engine exists on this machine.

    Subclasses implement `synthesize` (produce one raw audio file) and may
    override `intermediate_path` / `prepare`. The shared `render` then runs the
    same ffmpeg normalisation for every backend, so the assembled track sounds
    consistent no matter which engine produced the clips.
    """

    def __init__(self, backend: str, espeak_voice: str | None = None) -> None:
        self.backend = backend
        self.espeak_voice = espeak_voice

    @property
    def name(self) -> str:
        if self.backend in ("espeak-ng", "espeak") and self.espeak_voice:
            return f"{self.backend} ({self.espeak_voice})"
        return self.backend

    def prepare(self, items: list[tuple[str, str]], work: Path, voice: str, rate: int) -> None:
        """Optional batch pre-pass; `items` is [(line_id, text), ...].

        Backends that synthesise per line (say, espeak) leave this alone.
        MeloTTS overrides it so its model is loaded once for the whole script
        instead of once per narration line.
        """

    def intermediate_path(self, out_wav: Path) -> Path:
        """Where `synthesize` writes, before the ffmpeg normalisation pass."""
        return out_wav

    def synthesize(self, text: str, intermediate: Path, voice: str, rate: int) -> None:
        raise NotImplementedError

    def render(self, text: str, out_wav: Path, voice: str, rate: int) -> None:
        """Synthesise `text` into `out_wav` (mono pcm_s16le)."""
        intermediate = self.intermediate_path(out_wav)
        self.synthesize(text, intermediate, voice, rate)

        res = run([
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(intermediate),
            "-af", "highpass=f=90", "-ar", str(RATE), "-ac", "1",
            "-c:a", "pcm_s16le", str(out_wav),
        ])
        if res.returncode != 0:
            raise RuntimeError(f"ffmpeg failed converting {intermediate}: {res.stderr.strip()}")
        if intermediate != out_wav:
            intermediate.unlink(missing_ok=True)


class SayTts(Tts):
    """macOS `say`."""

    def __init__(self) -> None:
        super().__init__("say")

    def intermediate_path(self, out_wav: Path) -> Path:
        return out_wav.with_suffix(".aiff")

    def synthesize(self, text: str, intermediate: Path, voice: str, rate: int) -> None:
        v = "Tingting" if voice in ("", "auto", "default") else voice
        res = run(["say", "-v", v, "-r", str(rate), "-o", str(intermediate), text])
        if res.returncode != 0:
            raise RuntimeError(f"say failed: {res.stderr.strip()}")


class EspeakTts(Tts):
    """espeak-ng / espeak."""

    def __init__(self, backend: str, espeak_voice: str) -> None:
        super().__init__(backend, espeak_voice)

    def synthesize(self, text: str, intermediate: Path, voice: str, rate: int) -> None:
        # espeak takes words per minute; clamp into its useful range.
        wpm = max(120, min(280, int(rate)))
        res = run([
            self.backend, "-v", self.espeak_voice or "cmn", "-s", str(wpm),
            "-p", "48", "-a", "170", "-w", str(intermediate), text,
        ])
        if res.returncode != 0:
            raise RuntimeError(f"{self.backend} failed: {res.stderr.strip()}")


class MeloTts(Tts):
    """Local MeloTTS, driven as a subprocess by an interpreter that has it."""

    def __init__(self, python: str, language: str, speed: float) -> None:
        super().__init__("melo")
        self.python = python
        self.language = language
        self.speed = speed
        self.raw_dir: Path | None = None

    @property
    def name(self) -> str:
        return f"melo ({self.language}, speed {self.speed:g})"

    def intermediate_path(self, out_wav: Path) -> Path:
        # Never point this at out_wav: `render` hands it to ffmpeg as the input,
        # so it must be a different file.
        base = self.raw_dir if self.raw_dir is not None else out_wav.parent
        return base / out_wav.name

    def prepare(self, items: list[tuple[str, str]], work: Path, voice: str, rate: int) -> None:
        self.raw_dir = work / "_melo_raw"
        if self.raw_dir.exists():
            shutil.rmtree(self.raw_dir)
        self.raw_dir.mkdir(parents=True, exist_ok=True)
        spec = {
            "language": self.language,
            "speed": self.speed,
            "speaker": 0,
            "jobs": [
                {"id": line_id, "text": text, "out": str(self.raw_dir / f"{line_id}.wav")}
                for line_id, text in items
            ],
        }
        job_file = work / "melo_jobs.json"
        job_file.write_text(json.dumps(spec, ensure_ascii=False), encoding="utf-8")
        print(
            f"  melo: 预合成 {len(spec['jobs'])} 条"
            f"（{self.language}, speed {self.speed:g}）-> {self.raw_dir}"
        )
        res = run([self.python, str(MELO_WORKER), "--jobs", str(job_file)])
        if res.returncode != 0:
            detail = (res.stderr or res.stdout or "").strip()[-800:]
            raise RuntimeError(f"MeloTTS failed (exit {res.returncode}): {detail}")
        missing = [line_id for line_id, _ in items if not (self.raw_dir / f"{line_id}.wav").exists()]
        if missing:
            raise RuntimeError(f"MeloTTS 没有产出这些行：{', '.join(missing[:6])}")

    def synthesize(self, text: str, intermediate: Path, voice: str, rate: int) -> None:
        # prepare() already synthesised every clip in one model-load pass.
        if not intermediate.exists():
            raise RuntimeError(f"MeloTTS 未产出 {intermediate}")


def _fallback_tts() -> Tts | None:
    """MeloTTS 装上了却跑挂时的退路：espeak-ng → espeak → macOS say。"""
    for backend in ("espeak-ng", "espeak", "say"):
        if shutil.which(backend) is None:
            continue
        if backend in ("espeak-ng", "espeak"):
            for candidate in ESPEAK_VOICE_CANDIDATES:
                if run([backend, "-v", candidate, "-w", os.devnull, "test"]).returncode == 0:
                    return EspeakTts(backend, candidate)
            # an English-accented reading beats no narration at all
            if run([backend, "-v", "en", "-w", os.devnull, "test"]).returncode == 0:
                return EspeakTts(backend, "en")
            continue
        return SayTts()
    return None


def detect_tts(preferred: str, voice: str = "auto") -> Tts | None:
    for backend in _tts_candidates(preferred):
        if backend == "melo":
            python = find_melo_python()
            if python:
                return MeloTts(python, _melo_language(voice), _melo_speed())
            continue
        if backend == "say":
            return SayTts()
        if backend in ("espeak-ng", "espeak"):
            for candidate in ESPEAK_VOICE_CANDIDATES:
                if run([backend, "-v", candidate, "-w", os.devnull, "test"]).returncode == 0:
                    return EspeakTts(backend, candidate)
            # an English-accented reading beats no narration at all
            if run([backend, "-v", "en", "-w", os.devnull, "test"]).returncode == 0:
                return EspeakTts(backend, "en")
            continue
    return None


def silent_clip(text: str, out_wav: Path) -> None:
    """Subtitles-only fallback: silence as long as the line would be spoken."""
    seconds = min(max(0.8, han_len(text) / CHARS_PER_SECOND), MAX_CLIP_SECONDS)
    res = run([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "lavfi", "-i", f"anullsrc=r={RATE}:cl=mono",
        "-t", f"{seconds:.3f}", "-c:a", "pcm_s16le", str(out_wav),
    ])
    if res.returncode != 0:
        raise RuntimeError(f"ffmpeg failed generating silence: {res.stderr.strip()}")


# --------------------------------------------------------------------------
# Dry run
# --------------------------------------------------------------------------


def dry_run_report(script: Script) -> int:
    line_pause = LINE_PAUSE_MS / 1000.0
    scene_pause = SCENE_PAUSE_MS / 1000.0
    total = 0.0
    problems: list[str] = []

    print(f"{script.id}: dry run @ {CHARS_PER_SECOND} chars/s, rate={script.rate}")
    print(f"  canvas {script.width}x{script.height} @ {script.fps}fps, style={script.style}")
    print("")
    for scene in script.scenes:
        seconds = 0.0
        chars = 0
        lines = scene["lines"]
        for i, (_, text) in enumerate(lines):
            c = han_len(text)
            chars += c
            seconds += c / CHARS_PER_SECOND
            seconds += scene_pause if i == len(lines) - 1 else line_pause
        total += seconds
        flag = ""
        if seconds > SCENE_MAX_SECONDS:
            flag = "  <-- too long"
            problems.append(f"{scene['id']} {scene['visual']}: {seconds:.1f}s")
        print(
            f"  {scene['id']} {scene['visual']:<13} {seconds:5.1f}s  {chars:>3} chars "
            f"{'#' * max(1, round(seconds * 2))}{flag}"
        )

    total += FINAL_TAIL_MS / 1000.0
    print("")
    print(f"  scenes={len(script.scenes)} lines={len(script.flat)} "
          f"chars={sum(han_len(t) for _, _, _, t in script.flat)}")
    print(f"  estimated total: {total:.1f}s")
    if total > TOTAL_MAX_SECONDS:
        print(f"  !! over budget by {total - TOTAL_MAX_SECONDS:.1f}s", file=sys.stderr)
        problems.append(f"total {total:.1f}s")
    for p in problems:
        print(f"  !! {p}", file=sys.stderr)
    return 1 if problems else 0


# --------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------


def build(script: Script, force_no_audio: bool, preferred_tts: str) -> int:
    fps = script.fps
    work = OUT / f"_tts_{script.id}"
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True, exist_ok=True)
    PUBLIC.mkdir(parents=True, exist_ok=True)
    script.timing_ts.parent.mkdir(parents=True, exist_ok=True)
    (PROJECT / script.out_file).parent.mkdir(parents=True, exist_ok=True)
    script.lrc.parent.mkdir(parents=True, exist_ok=True)

    last_line_of_scene = {s["id"]: s["lines"][-1][0] for s in script.scenes}
    line_pause_frames = round(LINE_PAUSE_MS / 1000.0 * fps)
    scene_pause_frames = round(SCENE_PAUSE_MS / 1000.0 * fps)

    tts = None if force_no_audio else detect_tts(preferred_tts, script.voice)
    if tts is not None:
        print(f"{script.id}: TTS = {tts.name}")
        try:
            # 批量型后端（MeloTTS）在这里一次加载模型、把所有台词合成好；
            # say / espeak 的这个钩子是空的，仍然逐行合成。
            tts.prepare(
                [(line_id, text) for _, _, line_id, text in script.flat],
                work,
                script.voice,
                script.rate,
            )
        except RuntimeError as error:
            print(f"warning: {tts.name} 不可用：{error}", file=sys.stderr)
            tts = _fallback_tts()
            if tts is not None:
                print(f"{script.id}: TTS = {tts.name}（回退）", file=sys.stderr)
    if tts is None:
        print(
            f"{script.id}: no TTS available -> generating silent clips; "
            "the video will run as subtitles-only",
            file=sys.stderr,
        )

    print(
        f"{script.id}: scenes={len(script.scenes)} lines={len(script.flat)} "
        f"weight={sum(han_len(t) for _, _, _, t in script.flat)}"
    )

    # ---- 1. synthesise every line -------------------------------------
    clips: list[dict] = []
    for scene_id, visual, line_id, text in script.flat:
        wav = work / f"{line_id}.wav"
        if tts is not None:
            tts.render(text, wav, script.voice, script.rate)
        else:
            silent_clip(text, wav)

        gap_frames = (
            scene_pause_frames if line_id == last_line_of_scene[scene_id] else line_pause_frames
        )
        clips.append(
            {
                "sceneId": scene_id,
                "visual": visual,
                "id": line_id,
                "text": text,
                "path": wav,
                "durationMs": probe_duration_ms(wav),
                "gapFrames": gap_frames,
                "gapMs": gap_frames / fps * 1000.0,
                "chars": han_len(text),
            }
        )
        print(f"  {line_id}  {clips[-1]['durationMs']/1000:5.2f}s  {text[:26]}")

    # ---- 2. assemble the continuous narration track -------------------
    concat_list = work / "concat.txt"
    concat_list.write_text(
        "".join(f"file '{c['path'].resolve().as_posix()}'\n" for c in clips),
        encoding="utf-8",
    )

    tail_frames = round(FINAL_TAIL_MS / 1000.0 * fps)
    track = PUBLIC / script.audio_track
    has_audio = tts is not None
    afilter = f"apad=pad_dur={tail_frames / fps:.3f}"
    if has_audio:
        afilter = (
            "highpass=f=90,"
            "acompressor=threshold=-18dB:ratio=3:attack=7:release=160,"
            "loudnorm=I=-16:TP=-1.5:LRA=11," + afilter
        )
    res = run([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "concat", "-safe", "0", "-i", str(concat_list),
        "-af", afilter, "-ar", str(RATE), "-ac", "1",
        "-c:a", "pcm_s16le", str(track),
    ])
    if res.returncode != 0:
        print(f"error: concat failed: {res.stderr}", file=sys.stderr)
        return 1

    track_ms = probe_duration_ms(track)

    # ---- 3. locate each line inside the finished track ----------------
    #
    # Every clip already carries trailing silence, which becomes the breathing
    # room between lines once concatenated. So the expected onset of line i is
    # the sum of the preceding clip durations; the real onset is the end of
    # whichever detected silence segment contains that boundary. Measuring the
    # finished audio instead of accumulating frame-rounded durations is what
    # keeps the .lrc glued to the voice.
    segments = silence_segments(track) if has_audio else []

    expected_ms = 0.0
    for i, clip in enumerate(clips):
        if i == 0:
            clip["onsetMs"] = 0.0
        else:
            boundary = expected_ms
            hit = None
            for start, end in segments:
                # Requiring the matched silence to be a plausible inter-line
                # pause stops the long trailing silence at the end of the track
                # from swallowing the last few lines.
                if start <= boundary <= end and (end - start) <= 1200:
                    hit = end
                    break
            clip["onsetMs"] = hit if hit is not None else boundary
        expected_ms += clip["durationMs"]

    # ---- 3b. snap the timeline onto the frame grid ---------------------
    #
    # Onsets are snapped once, from the measured values — not accumulated from
    # previous lines — so rounding error can never compound. A line only moves
    # if its snapped onset would collide with the previous line. Line ends come
    # from the *next* onset, which keeps every line inside the audio instead of
    # stretching the video past the end of the voice track.
    total_frames = max(int(round(track_ms / 1000.0 * fps)), len(clips))

    prev_frame = -1
    for i, clip in enumerate(clips):
        f = int(round(clip["onsetMs"] / 1000.0 * fps))
        if i > 0:
            f = max(f, prev_frame + 1)
        clip["startFrame"] = f
        prev_frame = f

    for i, clip in enumerate(clips):
        onset_frame = clip["startFrame"]
        end_frame = clips[i + 1]["startFrame"] - 2 if i + 1 < len(clips) else total_frames
        end_frame = max(onset_frame + 1, min(end_frame, total_frames))

        clip["index"] = i
        clip["endFrame"] = end_frame
        clip["startMs"] = round(onset_frame / fps * 1000.0, 2)
        clip["endMs"] = round(end_frame / fps * 1000.0, 2)
        clip["speechFrames"] = end_frame - onset_frame
        clip["speechMs"] = round(clip["speechFrames"] / fps * 1000.0, 2)
        clip["totalFrames"] = clip["speechFrames"] + clip["gapFrames"]
        clip["totalMs"] = round(clip["totalFrames"] / fps * 1000.0, 2)

    total_ms = total_frames / fps * 1000.0

    # ---- 4. scene timings --------------------------------------------
    scenes: list[dict] = []
    for scene in script.scenes:
        own = [c for c in clips if c["sceneId"] == scene["id"]]
        if not own:
            continue
        scenes.append(
            {
                "id": scene["id"],
                "visual": scene["visual"],
                "index": len(scenes),
                "lineIds": [c["id"] for c in own],
                "firstLineIndex": own[0]["index"],
                "lastLineIndex": own[-1]["index"],
                "rawStartFrame": max(
                    0, own[0]["startFrame"] - (SCENE_LEAD_FRAMES if scenes else 0)
                ),
                "lineStartFrame": own[0]["startFrame"],
                "speechFrames": sum(c["speechFrames"] for c in own),
                "chars": sum(c["chars"] for c in own),
            }
        )

    starts: list[int] = []
    for scene in scenes:
        start = scene.pop("rawStartFrame")
        if starts:
            start = max(start, starts[-1] + 1)
        starts.append(start)

    for i, scene in enumerate(scenes):
        end = starts[i + 1] if i + 1 < len(scenes) else total_frames
        scene["startFrame"] = starts[i]
        scene["endFrame"] = end
        scene["durationInFrames"] = max(1, end - starts[i])
        scene["durationSeconds"] = round(scene["durationInFrames"] / fps, 2)
        scene["startMs"] = round(starts[i] / fps * 1000.0, 2)
        scene["lineStartMs"] = round(scene["lineStartFrame"] / fps * 1000.0, 2)

    # ---- 5. guard rails ----------------------------------------------
    problems: list[str] = []
    for scene in scenes:
        if scene["durationSeconds"] > SCENE_MAX_SECONDS:
            problems.append(
                f"{scene['id']} ({scene['visual']}) runs {scene['durationSeconds']}s "
                f"~ {scene['chars']} chars — trim it or split the scene"
            )
    if total_ms / 1000.0 > TOTAL_MAX_SECONDS:
        problems.append(
            f"total runtime {total_ms/1000:.1f}s exceeds the {TOTAL_MAX_SECONDS:.0f}s budget"
        )

    # ---- 6. outputs ---------------------------------------------------
    #
    # The generated TypeScript carries only what the composition reads at
    # render time. The full record (clip durations, measured onsets, char
    # counts) goes to JSON next to the other build artifacts.
    config_ts = (
        "/* GENERATED by scripts/build_audio.py — do not edit by hand. */\n\n"
        "export type VideoConfig = {\n"
        "  id: string;\n"
        "  fps: number;\n"
        "  width: number;\n"
        "  height: number;\n"
        "  audioTrack: string;\n"
        "  outFile: string;\n"
        '  style: "whiteboard";\n'
        "  voice: string;\n"
        "  rate: number;\n"
        "  hasAudio: boolean;\n"
        "};\n\n"
        "export const VIDEO: VideoConfig = "
        + json.dumps(
            {
                "id": script.id,
                "fps": fps,
                "width": script.width,
                "height": script.height,
                "audioTrack": script.audio_track,
                "outFile": script.out_file,
                "style": script.style,
                "voice": script.voice,
                "rate": script.rate,
                "hasAudio": has_audio,
            },
            ensure_ascii=False,
            indent=2,
        )
        + ";\n"
    )
    script.config_ts.write_text(config_ts, encoding="utf-8")

    public_lines = [
        {
            "id": c["id"],
            "index": c["index"],
            "sceneId": c["sceneId"],
            "text": c["text"],
            "startFrame": c["startFrame"],
            "endFrame": c["endFrame"],
        }
        for c in clips
    ]
    timing = {
        "fps": fps,
        "width": script.width,
        "height": script.height,
        "durationInFrames": total_frames,
        "audioTrack": script.audio_track,
        "lines": public_lines,
        "scenes": [
            {
                "id": s["id"],
                "visual": s["visual"],
                "index": s["index"],
                "lineIds": s["lineIds"],
                "startFrame": s["startFrame"],
                "endFrame": s["endFrame"],
                "durationInFrames": s["durationInFrames"],
            }
            for s in scenes
        ],
    }
    script.timing_ts.write_text(
        "/* GENERATED by scripts/build_audio.py — do not edit by hand. */\n"
        'import type { Timing } from "../whiteboard/types";\n\n'
        "export const TIMING: Timing = "
        + json.dumps(timing, ensure_ascii=False, indent=2)
        + ";\n",
        encoding="utf-8",
    )

    (OUT / f"{script.id}-timing.json").write_text(
        json.dumps(
            {
                "id": script.id,
                "title": script.title,
                "fps": fps,
                "width": script.width,
                "height": script.height,
                "durationInFrames": total_frames,
                "durationMs": round(total_ms, 2),
                "audioTrackMs": round(track_ms, 2),
                "speechMs": round(sum(c["speechMs"] for c in clips), 2),
                "voice": script.voice,
                "rate": script.rate,
                "tts": tts.name if tts else "none (silent)",
                "hasAudio": has_audio,
                "style": script.style,
                "outFile": script.out_file,
                "lines": [{k: v for k, v in c.items() if k != "path"} for c in clips],
                "scenes": scenes,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    def stamp(ms: float) -> str:
        cs = int(round(ms / 10.0))
        return f"[{cs // 6000:02d}:{cs // 100 % 60:02d}.{cs % 100:02d}]"

    lrc = [
        f"[ti:{script.title}]",
        "[ar:讲解动画]",
        f"[al:{script.audio_track}]",
        "[by:sketch explainer]",
        f"[length:{int(total_ms // 60000):02d}:{int(total_ms // 1000) % 60:02d}]",
        "",
    ]
    for clip in clips:
        lrc.append(f"{stamp(clip['startMs'])}{clip['text']}")
    script.lrc.write_text("\n".join(lrc) + "\n", encoding="utf-8")

    sheet = [
        f"# 分镜台词时间轴 / cue sheet — {script.id}",
        "",
        f"- 标题: {script.title}",
        f"- 画幅: {script.width}x{script.height} @ {fps}fps（style: {script.style}）",
        f"- 总时长: {total_ms/1000:.2f}s ({total_frames} frames)",
        f"- 音轨实测时长: {track_ms/1000:.2f}s",
        f"- 纯语音时长: {sum(c['speechMs'] for c in clips)/1000:.2f}s",
        f"- 场景数: {len(scenes)} / 台词行数: {len(clips)}",
        f"- 配音: {tts.name if tts else '无（纯字幕）'}",
        f"- 入点来源: 成品音轨实测（silencedetect {SILENCE_NOISE_DB}dB）",
        "",
        "| # | 场景 | 台词 | LRC 入点 | 语音 | 画面帧 |",
        "|---|---|---|---|---|---|",
    ]
    scene_of = {s["id"]: s for s in scenes}
    for clip in clips:
        scene = scene_of[clip["sceneId"]]
        sheet.append(
            f"| {clip['index']+1} | {scene['id']} {scene['visual']} | {clip['text']} "
            f"| {stamp(clip['startMs'])} | {clip['speechMs']/1000:.2f}s "
            f"| {scene['startFrame']}–{scene['endFrame']} |"
        )
    script.cue_sheet.write_text("\n".join(sheet) + "\n", encoding="utf-8")

    # ---- 7. report ----------------------------------------------------
    print("")
    for scene in scenes:
        bar = "#" * max(1, round(scene["durationSeconds"] * 2))
        print(
            f"  {scene['id']} {scene['visual']:<13} {scene['durationSeconds']:5.1f}s "
            f"f{scene['startFrame']:>5}-{scene['endFrame']:<5} {bar}"
        )
    worst = max(abs(c["startMs"] - c["onsetMs"]) for c in clips)
    print("")
    print(f"max |网格入点 - 实测入点|: {worst:.1f}ms")
    print(f"track     -> {track}  ({track_ms/1000:.2f}s)")
    print(f"timing    -> {script.timing_ts}  ({total_frames} frames, {len(scenes)} scenes)")
    print(f"config    -> {script.config_ts}")
    print(f"lrc       -> {script.lrc}  ({len(clips)} cues)")
    print(f"cue sheet -> {script.cue_sheet}")
    print(f"TOTAL     -> {total_ms/1000:.2f}s")

    if problems:
        print("", file=sys.stderr)
        print("!! pacing guard rails:", file=sys.stderr)
        for p in problems:
            print(f"   - {p}", file=sys.stderr)
        return 1
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--script", default=str(PROJECT / "scripts" / "script.json"))
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--no-audio", action="store_true",
                    help="skip TTS entirely and generate silent clips")
    ap.add_argument("--tts", default="auto",
                    help="auto | melo | say | espeak-ng | espeak")
    args = ap.parse_args()

    script = Script(Path(args.script))

    if args.dry_run:
        return dry_run_report(script)

    for tool in ("ffmpeg", "ffprobe"):
        if shutil.which(tool) is None:
            print(f"error: {tool} not found on PATH", file=sys.stderr)
            return 1

    return build(script, args.no_audio, args.tts)


if __name__ == "__main__":
    raise SystemExit(main())
