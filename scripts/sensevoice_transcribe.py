#!/usr/bin/env python3
"""SenseVoice-Small STT 后端（sherpa-onnx / ONNX Runtime）。

为什么用它：SenseVoice-Small 是阿里 FunAudioLLM 的非自回归（encoder-only）
语音识别模型，234M 参数，支持中文 / 粤语 / 英文 / 日文 / 韩文，且自带标点与
ITN（数字规范化）。CPU 上用 int8 ONNX 推理就足够快，不需要 torch / GPU。

与 faster_whisper_transcribe.py 的接口保持一致：
  - stdin/stdout 只走 JSON，过程日志全部写 stderr；
  - 输出 {"runtime": {...}, "segments": [{"startCs", "endCs", "text"}]}；
  - 长音频先用 silero-VAD 切句，再逐句送模型（模型单次输入上限约 30 秒）。

用法：
  sensevoice_transcribe.py --probe                    # 健康检查（不加载模型）
  sensevoice_transcribe.py --download-model           # 预下载模型
  sensevoice_transcribe.py --audio a.wav --language auto --model-dir models/sensevoice
"""

import argparse
import json
import os
import re
import shutil
import sys
import time
import urllib.request
import wave
from pathlib import Path

import numpy as np

MODEL_REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
MODEL_FILES = ("model.int8.onnx", "tokens.txt")
VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
SAMPLE_RATE = 16000

# 模型输出里可能带 <|zh|><|NEUTRAL|><|Speech|><|woitn|> 之类的富文本标签
RICH_TAG = re.compile(r"<\|[^|>]*\|>")
# 中日文用到的字符区间（不含韩文：韩文要用空格分词）
CJK = "\u3000-\u303f\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff"


def configure_stdio():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


configure_stdio()


def log(message):
    print(f"[sensevoice] {message}", file=sys.stderr, flush=True)


def normalize_language(value):
    key = str(value or "").strip().lower()
    if key in ("", "auto", "detect", "unknown"):
        return ""
    aliases = {
        "zh": "zh",
        "cn": "zh",
        "zh-cn": "zh",
        "zh_tw": "zh",
        "zh-tw": "zh",
        "chinese": "zh",
        "ja": "ja",
        "jp": "ja",
        "japanese": "ja",
        "en": "en",
        "english": "en",
        "ko": "ko",
        "kr": "ko",
        "korean": "ko",
        "yue": "yue",
        "cantonese": "yue",
    }
    if key in aliases:
        return aliases[key]
    raise RuntimeError(
        f"SenseVoice 不支持语种 {value!r}（支持 zh / yue / en / ja / ko 或 auto）"
    )


def model_dir_for(value):
    if value:
        return Path(value).expanduser()
    env = os.environ.get("SENSEVOICE_MODEL_DIR")
    if env:
        return Path(env).expanduser()
    return Path(__file__).resolve().parent.parent / "models" / "sensevoice"


def model_files_present(directory):
    return all((directory / name).is_file() for name in MODEL_FILES)


def ensure_model(directory, allow_download=True):
    """确保 model.int8.onnx + tokens.txt 存在；缺失时从 HuggingFace 拉取。"""
    directory.mkdir(parents=True, exist_ok=True)
    if model_files_present(directory):
        return
    if not allow_download:
        missing = [name for name in MODEL_FILES if not (directory / name).is_file()]
        raise RuntimeError(
            f"SenseVoice 模型缺失（{directory}）：{', '.join(missing)}。"
            "请运行 npm run setup:sensevoice，或手工下载后放到该目录。"
        )
    try:
        from huggingface_hub import snapshot_download
    except Exception as exc:  # pragma: no cover - 依赖缺失时才走这里
        raise RuntimeError(
            "缺少 huggingface_hub，无法自动下载 SenseVoice 模型。"
            "请运行 npm run setup:sensevoice 安装依赖。"
        ) from exc

    log(f"首次运行，正在下载 SenseVoice 模型（约 240MB，仓库 {MODEL_REPO}）...")
    started = time.time()
    snapshot_download(
        repo_id=MODEL_REPO,
        local_dir=str(directory),
        allow_patterns=list(MODEL_FILES),
    )
    log(f"模型下载完成（{time.time() - started:.1f}s）")
    if not model_files_present(directory):
        raise RuntimeError(f"SenseVoice 模型下载后仍不完整：{directory}")


def ensure_vad(directory, allow_download=True):
    """silero-VAD（可选）：拿不到就返回 None，由调用方退化成定长切窗。"""
    path = directory / "silero_vad.onnx"
    if path.is_file() and path.stat().st_size > 0:
        return path
    if not allow_download:
        return None
    tmp = path.with_suffix(".onnx.part")
    try:
        log("正在下载 silero-VAD 模型（约 0.6MB）...")
        with urllib.request.urlopen(VAD_URL, timeout=60) as response, open(tmp, "wb") as handle:
            shutil.copyfileobj(response, handle)
        tmp.replace(path)
        return path
    except Exception as exc:
        log(f"silero-VAD 下载失败（{exc}）；本次改用定长切窗，准确率会略降")
        try:
            tmp.unlink()
        except OSError:
            pass
        return None


def require_sherpa():
    try:
        import sherpa_onnx  # noqa: F401
        import numpy  # noqa: F401
    except Exception as exc:
        raise RuntimeError(
            "sherpa-onnx 未安装。请运行 npm run setup:sensevoice（安装到项目 .venv）。"
        ) from exc
    import sherpa_onnx

    return sherpa_onnx


def read_wav(path):
    """monitor.js 已用 ffmpeg 转成 16k / 单声道 / pcm_s16le，这里只做校验。"""
    with wave.open(str(path), "rb") as handle:
        channels = handle.getnchannels()
        width = handle.getsampwidth()
        rate = handle.getframerate()
        frames = handle.getnframes()
        raw = handle.readframes(frames)
    if width != 2:
        raise RuntimeError(f"只支持 16-bit PCM wav：{path}（sampwidth={width}）")
    if rate != SAMPLE_RATE:
        raise RuntimeError(f"采样率必须是 {SAMPLE_RATE}Hz：{path}（实际 {rate}Hz）")
    samples = np.frombuffer(raw, dtype="<i2").astype("float32") / 32768.0
    if channels > 1:
        samples = samples.reshape(-1, channels).mean(axis=1)
    return samples


def clean_text(text):
    text = RICH_TAG.sub("", text or "")
    text = re.sub(r"\s+", " ", text).strip()
    # 日文 / 中文的 SenseVoice 输出偶尔会带分词空格（「うち の 中学 は」「50 円」），
    # 中日文本身不用空格分词，去掉更贴近可发表文本；韩文 / 英文的空格保持不动。
    text = re.sub(f"[ \t]+(?=[{CJK}])", "", text)
    text = re.sub(f"(?<=[{CJK}])[ \t]+", "", text)
    return text.strip()


class Decoder:
    """回收 VAD 切出的句子，凑批解码（batch 比逐句快，长音频也不吃内存）。"""

    def __init__(self, recognizer, batch_size):
        self.recognizer = recognizer
        self.batch_size = max(1, batch_size)
        self.pending = []
        self.segments = []
        self.suppressed = 0
        self.languages = set()

    def add(self, start_sample, samples):
        self.pending.append((int(start_sample), samples))
        if len(self.pending) >= self.batch_size:
            self.flush()

    def flush(self):
        if not self.pending:
            return
        streams = []
        for start_sample, samples in self.pending:
            stream = self.recognizer.create_stream()
            stream.accept_waveform(SAMPLE_RATE, samples)
            streams.append((start_sample, len(samples), stream))
        self.recognizer.decode_streams([item[2] for item in streams])
        for start_sample, length, stream in streams:
            result = stream.result
            if result.lang:
                self.languages.add(result.lang)
            text = clean_text(result.text)
            if not text:
                self.suppressed += 1
                continue
            self.segments.append(
                {
                    "startCs": round(start_sample / SAMPLE_RATE * 100),
                    "endCs": round((start_sample + length) / SAMPLE_RATE * 100),
                    "text": text,
                }
            )
        self.pending = []


def segment_with_vad(sherpa_onnx, vad_path, samples, max_speech_seconds, padding_seconds=0.2):
    """按 silero-VAD 检测到的句子边界切分；yield (start_sample, samples)。

    **必须留 padding。** VAD 的边界比真实发音边界紧，直接拿 segment.samples 解码
    会把句首辅音切掉：实测「开放时间」会被切成「开饭时间」/「派饭时间」，加上
    0.2s 前后余量后 8/8 句全部正确。min_silence=0.5s，0.2s+0.2s 仍不会让相邻
    两句重叠。
    """
    config = sherpa_onnx.VadModelConfig()
    config.silero_vad.model = str(vad_path)
    config.silero_vad.threshold = 0.5
    config.silero_vad.min_silence_duration = 0.5
    config.silero_vad.min_speech_duration = 0.25
    config.silero_vad.max_speech_duration = max_speech_seconds
    config.sample_rate = SAMPLE_RATE
    config.num_threads = 1

    pad = max(0, int(padding_seconds * SAMPLE_RATE))

    def padded(segment):
        start = max(0, int(segment.start) - pad)
        end = min(len(samples), int(segment.start) + len(segment.samples) + pad)
        return start, np.array(samples[start:end], dtype=np.float32)

    vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=60)
    window = config.silero_vad.window_size or 512
    for offset in range(0, len(samples), window):
        vad.accept_waveform(samples[offset : offset + window])
        while not vad.empty():
            # front 返回的引用在下一次调用后失效，先复制成独立数组
            yield padded(vad.front)
            vad.pop()
    vad.flush()
    while not vad.empty():
        yield padded(vad.front)
        vad.pop()


def segment_fixed_window(samples, window_seconds):
    """没有 VAD 时的退路：定长切窗（音频本身很干净时够用）。"""
    step = int(window_seconds * SAMPLE_RATE)
    for offset in range(0, len(samples), step):
        yield offset, np.array(samples[offset : offset + step], dtype=np.float32)


def build_recognizer(sherpa_onnx, directory, language, threads, use_itn):
    model_path = directory / "model.int8.onnx"
    log(f"加载 SenseVoice 模型：{model_path.name}（threads={threads}, itn={use_itn}）")
    return sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(model_path),
        tokens=str(directory / "tokens.txt"),
        num_threads=threads,
        language=language,
        use_itn=use_itn,
    )


def resolve_threads(value):
    if value and value > 0:
        return value
    try:
        cores = os.cpu_count() or 4
    except Exception:
        cores = 4
    return max(1, min(4, cores))


def transcribe(args):
    sherpa_onnx = require_sherpa()
    directory = model_dir_for(args.model_dir)
    ensure_model(directory, allow_download=not args.no_download)
    vad_path = None if args.no_vad else ensure_vad(directory, allow_download=not args.no_download)
    language = normalize_language(args.language)
    threads = resolve_threads(args.threads)

    load_started = time.time()
    recognizer = build_recognizer(sherpa_onnx, directory, language, threads, not args.no_itn)
    load_ms = int((time.time() - load_started) * 1000)

    samples = read_wav(args.audio)
    audio_ms = int(len(samples) / SAMPLE_RATE * 1000)

    decoder = Decoder(recognizer, args.batch_size)
    decode_started = time.time()
    if vad_path is not None:
        for start_sample, chunk in segment_with_vad(
            sherpa_onnx, vad_path, samples, args.max_speech_seconds, args.vad_padding
        ):
            decoder.add(start_sample, chunk)
    else:
        for start_sample, chunk in segment_fixed_window(samples, args.max_speech_seconds):
            decoder.add(start_sample, chunk)
    decoder.flush()
    decode_ms = int((time.time() - decode_started) * 1000)

    payload = {
        "runtime": {
            "backend": "sherpa-onnx:sensevoice-small",
            "device": "cpu",
            "model": (directory / "model.int8.onnx").name,
            "modelDir": str(directory),
            "language": language or "auto",
            "detectedLanguages": sorted(decoder.languages),
            "useItn": not args.no_itn,
            "threads": threads,
            "batchSize": max(1, args.batch_size),
            "vad": "silero" if vad_path is not None else "fixed-window",
            "vadPadding": args.vad_padding if vad_path is not None else 0,
            "audioMs": audio_ms,
            "loadMs": load_ms,
            "decodeMs": decode_ms,
            "rtf": round(decode_ms / audio_ms, 4) if audio_ms else None,
        },
        "segments": decoder.segments,
        "suppressedEmpty": decoder.suppressed,
    }
    print(json.dumps(payload, ensure_ascii=False))


def probe(args):
    sherpa_onnx = require_sherpa()
    directory = model_dir_for(args.model_dir)
    vad_path = directory / "silero_vad.onnx"
    ready = model_files_present(directory)
    payload = {
        "ok": True,
        "backend": "sherpa-onnx:sensevoice-small",
        "version": getattr(sherpa_onnx, "__version__", None),
        "modelDir": str(directory),
        "modelReady": ready,
        "vadReady": vad_path.is_file(),
        "detail": "SenseVoice 可用" if ready else f"模型未下载（{directory}），首次转录会自动下载或运行 npm run setup:sensevoice",
    }
    print(json.dumps(payload, ensure_ascii=False))


def download(args):
    require_sherpa()
    directory = model_dir_for(args.model_dir)
    started = time.time()
    ensure_model(directory, allow_download=True)
    vad_path = ensure_vad(directory, allow_download=True)
    payload = {
        "ok": True,
        "modelDir": str(directory),
        "modelReady": model_files_present(directory),
        "vadReady": vad_path is not None,
        "seconds": round(time.time() - started, 1),
    }
    print(json.dumps(payload, ensure_ascii=False))


def build_parser():
    parser = argparse.ArgumentParser(description="SenseVoice-Small STT back-end")
    parser.add_argument("--probe", action="store_true", help="健康检查后退出")
    parser.add_argument("--download-model", action="store_true", help="只下载模型后退出")
    parser.add_argument("--audio", help="16kHz 单声道 16-bit PCM wav")
    parser.add_argument("--language", default="auto", help="auto / zh / yue / en / ja / ko")
    parser.add_argument("--model-dir", default=None, help="模型目录（默认 models/sensevoice）")
    parser.add_argument("--threads", type=int, default=0, help="推理线程数（默认 min(4, CPU)）")
    parser.add_argument("--batch-size", type=int, default=8, help="批量解码句数（默认 8）")
    parser.add_argument("--max-speech-seconds", type=float, default=20.0, help="单句最长秒数（<30）")
    parser.add_argument("--vad-padding", type=float, default=0.2, help="VAD 切句前后各留的余量秒数（默认 0.2）")
    parser.add_argument("--no-itn", action="store_true", help="关闭标点 / 数字规范化")
    parser.add_argument("--no-vad", action="store_true", help="不使用 VAD，改用定长切窗")
    parser.add_argument("--no-download", action="store_true", help="模型缺失时不要联网下载")
    return parser


def main():
    args = build_parser().parse_args()
    try:
        if args.probe:
            probe(args)
            return
        if args.download_model:
            download(args)
            return
        if not args.audio:
            raise RuntimeError("--audio is required")
        if not Path(args.audio).is_file():
            raise RuntimeError(f"音频文件不存在：{args.audio}")
        transcribe(args)
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
